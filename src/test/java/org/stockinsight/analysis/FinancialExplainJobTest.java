package org.stockinsight.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.stockinsight.TestcontainersConfiguration;
import org.stockinsight.analysis.llm.FakeLlmClient;
import org.stockinsight.analysis.llm.LlmException;
import org.stockinsight.analysis.llm.LlmOutputRejectedException;
import org.stockinsight.analysis.llm.LlmResult;
import org.stockinsight.common.pipeline.PipelineRunRecorder.RunStatus;
import org.stockinsight.company.CompanyService;
import org.stockinsight.company.CompanyService.ListedCompany;
import org.stockinsight.company.Market;
import org.stockinsight.financial.FinancialService;
import org.stockinsight.financial.RawAccountLine;
import org.stockinsight.signal.FinancialSignalJob;

import tools.jackson.databind.json.JsonMapper;

/**
 * 재무 쉬운 설명 동기화 작업을 검증한다(docs/spec/analyses.md §6.1). 실제 LLM 대신 {@link FakeLlmClient}를 쓴다.
 */
@SpringBootTest(properties = "app.analysis.financial-explain.publish=true")
@Import({TestcontainersConfiguration.class, FinancialExplainJobTest.FakeLlmConfig.class})
class FinancialExplainJobTest {

    @Autowired
    FinancialExplainJob job;

    @Autowired
    FinancialExplainInputBuilder inputBuilder;

    @Autowired
    AnalysisService analysisService;

    @Autowired
    FinancialService financialService;

    @Autowired
    FinancialSignalJob signalJob;

    @Autowired
    CompanyService companyService;

    @Autowired
    FakeLlmClient llm;

    @Autowired
    JdbcClient jdbc;

    long companyId;

    @BeforeEach
    void setUp() {
        jdbc.sql("truncate analysis, company_signal, financial_report, company_alias, security, company, ingest_checkpoint, pipeline_run restart identity cascade")
                .update();
        llm.reset();
        companyId = companyService.upsertListed(
                new ListedCompany("00126380", "삼성전자", "삼성전자(주)", "005930", Market.KOSPI, "264", 12)).getId();
        jdbc.sql("update company set ai_covered = true where id = :id").param("id", companyId).update();
        seedQuarter("2,000,000,000", "1,500,000,000", "R1");
        signalJob.run();
    }

    @Test
    void publishesOnFirstValidResponse() {
        llm.thenReturn(validResult());

        FinancialExplainJob.Result result = job.run();

        assertThat(result.status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(llm.callCount()).isEqualTo(1);
        Analysis current = analysisService.findCurrent(TargetType.COMPANY, String.valueOf(companyId), AnalysisKind.FINANCIAL_EXPLAIN)
                .orElseThrow();
        assertThat(current.status()).isEqualTo(AnalysisStatus.PUBLISHED);
        assertThat(current.isCurrent()).isTrue();
        assertThat(current.attemptCount()).isEqualTo(1);
    }

    @Test
    void rerunWithSameFingerprintDoesNotCallLlmAgain() {
        llm.thenReturn(validResult());
        job.run();
        assertThat(llm.callCount()).isEqualTo(1);

        FinancialExplainJob.Result second = job.run();

        assertThat(second.summary()).contains("건너뜀(최신) 1");
        assertThat(llm.callCount()).isEqualTo(1);
    }

    @Test
    void retriesOnceThenSucceedsAfterValidationFailure() {
        // 1차: 개요는 배정된 사실을 완전하게 쓰되(D-57), 금지 표현(투자 권유)으로 검증 실패. 2차: 통과.
        llm.thenReturn(new LlmResult(
                        "{\"overview\":\"매출이 전년 같은 분기보다 {fin.revenue_yoy.2026-01.Q1} 늘어 매수 추천 문구가 있어요.\","
                                + "\"sales_profit\":\"매출과 영업이익 흐름을 살펴봤어요.\",\"structure\":\"재무 구조를 확인했어요.\",\"history\":null}",
                        "claude-sonnet-5", 100, 50, 0, BigDecimal.ONE))
                .thenReturn(validResult());

        FinancialExplainJob.Result result = job.run();

        assertThat(llm.callCount()).isEqualTo(2);
        assertThat(llm.calls().get(0).userInput()).doesNotContain("이전 시도");
        // 재시도는 검증기 번호가 아니라 프롬프트가 아는 표현으로 위반 내용을 알려 준다(D-53).
        assertThat(llm.calls().get(1).userInput())
                .contains(FinancialExplainPrompt.retryGuidance("9"))
                .doesNotContain("규칙 번호를 위반");
        Analysis current = analysisService.findCurrent(TargetType.COMPANY, String.valueOf(companyId), AnalysisKind.FINANCIAL_EXPLAIN)
                .orElseThrow();
        assertThat(current.status()).isEqualTo(AnalysisStatus.PUBLISHED);
        assertThat(current.attemptCount()).isEqualTo(2);
        assertThat(result.status()).isEqualTo(RunStatus.SUCCEEDED);

        AnalysisAttempt[] attempts = savedAttempts();
        assertThat(attempts).hasSize(2);
        assertThat(attempts[0].outcome()).isEqualTo(AnalysisAttempt.Outcome.REJECTED);
        assertThat(attempts[0].failedRules()).containsExactly("9");
        assertThat(attempts[0].output().overview()).contains("매수");
        assertThat(attempts[0].validatorVersion()).isEqualTo(FinancialExplainValidator.VERSION);
        assertThat(attempts[1].outcome()).isEqualTo(AnalysisAttempt.Outcome.SUCCESS);
        assertThat(attempts[1].failedRules()).isEmpty();
    }

    @Test
    void rejectsAfterTwoFailedValidationAttempts() {
        LlmResult invalid = new LlmResult(
                "{\"overview\":\"매수 추천 문구가 있어요.\",\"sales_profit\":\"매출과 영업이익 흐름을 살펴봤어요.\",\"structure\":\"재무 구조를 확인했어요.\",\"history\":null}",
                "claude-sonnet-5", 100, 50, 0, BigDecimal.ONE);
        llm.thenReturn(invalid).thenReturn(invalid);

        job.run();

        assertThat(llm.callCount()).isEqualTo(2);
        List<Analysis> attempts = analysisService.findByTarget(TargetType.COMPANY, String.valueOf(companyId), AnalysisKind.FINANCIAL_EXPLAIN);
        assertThat(attempts).hasSize(1);
        assertThat(attempts.get(0).status()).isEqualTo(AnalysisStatus.REJECTED);
        assertThat(attempts.get(0).attemptCount()).isEqualTo(2);
        assertThat(attempts.get(0).failureReasons()).contains("9");
        assertThat(analysisService.findCurrent(TargetType.COMPANY, String.valueOf(companyId), AnalysisKind.FINANCIAL_EXPLAIN)).isEmpty();

        // 거절돼도 게시용 결과(result_json)는 비우고, 시도별 원문은 attempts에만 남긴다.
        assertThat(attempts.get(0).resultJson()).isNull();
        AnalysisAttempt[] saved = savedAttempts();
        assertThat(saved).hasSize(2);
        assertThat(saved).allSatisfy(a -> {
            assertThat(a.outcome()).isEqualTo(AnalysisAttempt.Outcome.REJECTED);
            assertThat(a.output().overview()).contains("매수");
        });
    }

    @Test
    void malformedJsonIsRetriedThenRejected() {
        llm.thenReturn(new LlmResult("이건 JSON이 아니다", "claude-sonnet-5", 10, 5, 0, BigDecimal.ZERO))
                .thenReturn(new LlmResult("여전히 JSON이 아니다", "claude-sonnet-5", 10, 5, 0, BigDecimal.ZERO));

        job.run();

        assertThat(llm.callCount()).isEqualTo(2);
        Analysis saved = analysisService.findByTarget(TargetType.COMPANY, String.valueOf(companyId), AnalysisKind.FINANCIAL_EXPLAIN).get(0);
        assertThat(saved.status()).isEqualTo(AnalysisStatus.REJECTED);
        assertThat(saved.failureReasons()).contains("INVALID_JSON");

        AnalysisAttempt[] attempts = savedAttempts();
        assertThat(attempts[0].outcome()).isEqualTo(AnalysisAttempt.Outcome.INVALID_JSON);
        assertThat(attempts[0].output()).isNull();
        assertThat(attempts[0].rawOutput()).isEqualTo("이건 JSON이 아니다");
        assertThat(attempts[0].validatorVersion()).isNull();
        assertThat(attempts[1].rawOutput()).isEqualTo("여전히 JSON이 아니다");
    }

    @Test
    void callFailureIsRecordedAsFailedWithoutRetry() {
        llm.thenThrow(new LlmException("Anthropic 인증키가 없습니다"));

        FinancialExplainJob.Result result = job.run();

        assertThat(llm.callCount()).isEqualTo(1);
        Analysis saved = analysisService.findByTarget(TargetType.COMPANY, String.valueOf(companyId), AnalysisKind.FINANCIAL_EXPLAIN).get(0);
        assertThat(saved.status()).isEqualTo(AnalysisStatus.FAILED);
        assertThat(saved.attemptCount()).isEqualTo(1);
        assertThat(result.status()).isEqualTo(RunStatus.PARTIAL);

        AnalysisAttempt[] attempts = savedAttempts();
        assertThat(attempts).hasSize(1);
        assertThat(attempts[0].outcome()).isEqualTo(AnalysisAttempt.Outcome.CALL_FAILED);
        assertThat(attempts[0].detail()).isEqualTo("LlmException");
    }

    @Test
    void outputRejectedByStopReasonIsRetriedLikeValidationFailure() {
        llm.thenThrow(new LlmOutputRejectedException("모델 출력이 MAX_TOKENS(으)로 끝났습니다",
                        new LlmResult(null, "claude-sonnet-5", 100, 2000, 0, BigDecimal.TEN)))
                .thenReturn(validResult());

        job.run();

        assertThat(llm.callCount()).isEqualTo(2);
        Analysis current = analysisService.findCurrent(TargetType.COMPANY, String.valueOf(companyId), AnalysisKind.FINANCIAL_EXPLAIN)
                .orElseThrow();
        assertThat(current.status()).isEqualTo(AnalysisStatus.PUBLISHED);
        // 잘린 응답도 과금되었으므로 두 시도의 비용이 모두 누적된다.
        assertThat(current.costUsd()).isGreaterThan(BigDecimal.TEN);

        AnalysisAttempt[] attempts = savedAttempts();
        assertThat(attempts[0].outcome()).isEqualTo(AnalysisAttempt.Outcome.LLM_OUTPUT_REJECTED);
        assertThat(attempts[0].detail()).contains("MAX_TOKENS");
        assertThat(attempts[1].outcome()).isEqualTo(AnalysisAttempt.Outcome.SUCCESS);
    }

    @Test
    void savingADraftAloneNeverPublishesIt() {
        // AnalysisService.save()만으로는 게시되지 않는다. FinancialExplainJob은 properties.publish()가 true일 때만
        // 별도로 publish()를 호출한다(§4.4.8 골든셋 통과 전 초안 전용 저장). 이 테스트는 그 하위 불변을 확인한다.
        FinancialExplainInputBuilder.BuildResult built = inputBuilder.build(companyId).orElseThrow();
        NewAnalysis draft = new NewAnalysis(TargetType.COMPANY, String.valueOf(companyId), AnalysisKind.FINANCIAL_EXPLAIN,
                built.fingerprint(), AnalysisStatus.DRAFT, null, built.input(), built.snapshot(), "fx-schema-1", "fx-v1",
                "claude-sonnet-5", FinancialExplainInputBuilder.INPUT_BUILDER_VERSION, "fin-1", 100, 50, 0, BigDecimal.ONE,
                null, 1, null);
        analysisService.save(draft, java.time.Instant.now());

        assertThat(analysisService.findCurrent(TargetType.COMPANY, String.valueOf(companyId), AnalysisKind.FINANCIAL_EXPLAIN)).isEmpty();
    }

    private AnalysisAttempt[] savedAttempts() {
        String json = jdbc.sql("select attempts::text from analysis where target_key = :key")
                .param("key", String.valueOf(companyId))
                .query(String.class)
                .single();
        return JsonMapper.builder().build().readValue(json, AnalysisAttempt[].class);
    }

    private LlmResult validResult() {
        // seedQuarter(2.0B/1.5B, +33.3%)는 CHANGED다. 개요는 배정된 revenue_yoy를 완전성 규칙대로 써야 한다(D-57).
        return new LlmResult(
                "{\"overview\":\"매출이 전년 같은 분기보다 {fin.revenue_yoy.2026-01.Q1} 늘었어요.\","
                        + "\"sales_profit\":\"매출은 {fin.revenue.2026-01.Q1}이었어요.\",\"structure\":\"부채비율은 {fin.debt_ratio.2026-01.Q1}예요.\",\"history\":null}",
                "claude-sonnet-5", 3000, 400, 1200, new BigDecimal("0.01"));
    }

    private void seedQuarter(String current, String prior, String receiptNo) {
        List<RawAccountLine> rows = List.of(
                line("BS", "유동자산", "1", "2026.03.31 현재", "3,000,000,000", "2,500,000,000", receiptNo),
                line("BS", "자산총계", "5", "2026.03.31 현재", "9,000,000,000", "8,000,000,000", receiptNo),
                line("BS", "부채총계", "9", "2026.03.31 현재", "4,000,000,000", "3,500,000,000", receiptNo),
                line("BS", "자본총계", "13", "2026.03.31 현재", "5,000,000,000", "4,500,000,000", receiptNo),
                line("IS", "매출액", "23", "2026.01.01 ~ 2026.03.31", current, prior, receiptNo),
                line("IS", "영업이익", "27", "2026.01.01 ~ 2026.03.31", "500,000,000", "400,000,000", receiptNo));
        financialService.replace(companyId, 2026, "11013", rows, null);
    }

    private static RawAccountLine line(String statement, String accountName, String ord, String period,
            String current, String prior, String receiptNo) {
        return new RawAccountLine("CFS", statement, accountName, ord, period, current, null, prior, null, null,
                "KRW", receiptNo);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FakeLlmConfig {

        @Bean
        @Primary
        FakeLlmClient fakeLlmClient() {
            return new FakeLlmClient();
        }
    }
}
