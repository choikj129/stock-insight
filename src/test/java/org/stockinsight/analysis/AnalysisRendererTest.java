package org.stockinsight.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.stockinsight.TestcontainersConfiguration;
import org.stockinsight.company.CompanyService;
import org.stockinsight.company.CompanyService.ListedCompany;
import org.stockinsight.company.Market;
import org.stockinsight.financial.FinancialService;
import org.stockinsight.financial.RawAccountLine;
import org.stockinsight.signal.FinancialSignalJob;

/**
 * 게시본 무효화를 검증한다(D-40). 참조한 신호가 철회되면 재생성 전이라도 즉시 무효화되어야 한다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class AnalysisRendererTest {

    @Autowired
    AnalysisRenderer renderer;

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
    JdbcClient jdbc;

    long companyId;

    @BeforeEach
    void setUp() {
        jdbc.sql("truncate analysis, company_signal, financial_report, company_alias, security, company, ingest_checkpoint, pipeline_run restart identity cascade")
                .update();
        companyId = companyService.upsertListed(
                new ListedCompany("00126380", "삼성전자", "삼성전자(주)", "005930", Market.KOSPI, "264", 12)).getId();
    }

    @Test
    void stateAndTurnFactsRenderFromSnapshotDisplayWithoutRendererChange() {
        // D-55 점검표 17: 상태·전환 사실도 다른 사실처럼 값 스냅샷의 표시 값으로 바뀐다(렌더러는 그대로). 흑자·적자 글자는 코드가 쓴다.
        ValueSnapshot snapshot = new ValueSnapshot(
                java.util.Map.of(
                        "fin.net_income_turn.2026-01.Q2", new ValueSnapshot.FactSnapshot("적자에서 흑자로",
                                new java.math.BigDecimal("300000000"), FinancialExplainInputBuilder.STATE_UNIT, "2026-01.Q2",
                                "2026-06-30", "R1", "CFS", "KRW"),
                        "fin.net_income_status.2026-01.Q2", new ValueSnapshot.FactSnapshot("적자",
                                new java.math.BigDecimal("-5"), FinancialExplainInputBuilder.STATE_UNIT, "2026-01.Q2",
                                "2026-06-30", "R1", "CFS", "KRW")),
                java.util.Map.of(), java.util.Map.of(), List.of(),
                new ValueSnapshot.Header("CFS", "KRW", "GENERAL", "2026-01.Q2"));

        assertThat(renderer.render("당기순이익은 {fin.net_income_turn.2026-01.Q2} 바뀌었어요.", snapshot))
                .isEqualTo("당기순이익은 적자에서 흑자로 바뀌었어요.");
        assertThat(renderer.render("영업이익과 달리 {fin.net_income_status.2026-01.Q2}였어요.", snapshot))
                .isEqualTo("영업이익과 달리 적자였어요.");
    }

    @Test
    void renderEscapesAiTextBetweenTokensNotOnlyTokenValues() {
        // 보안 점검(2026-09-27): 이전에는 토큰 값만 이스케이프하고 토큰 사이의 AI 문장은 그대로 붙였다. AI 출력도 외부 입력이다.
        ValueSnapshot snapshot = new ValueSnapshot(
                java.util.Map.of("fin.revenue.2026-01.Q2", new ValueSnapshot.FactSnapshot("40.0억원",
                        new java.math.BigDecimal("4000000000"), "KRW", "2026-01.Q2", "2026-06-30", "R1", "CFS", "KRW")),
                java.util.Map.of(), java.util.Map.of(), List.of(),
                new ValueSnapshot.Header("CFS", "KRW", "GENERAL", "2026-01.Q2"));

        assertThat(renderer.render("<script>x</script>매출은 {fin.revenue.2026-01.Q2}이에요 & \"끝\"", snapshot))
                .isEqualTo("&lt;script&gt;x&lt;/script&gt;매출은 40.0억원이에요 &amp; &quot;끝&quot;");
    }

    @Test
    void publishedAnalysisIsInvalidatedAssoonAsItsReferencedSignalIsWithdrawn() {
        seedQuarter("4,000,000,000", "2,000,000,000", "R1"); // +100%: FIN_REVENUE_CHANGE 활성
        signalJob.run();
        Analysis published = publish(inputBuilder.build(companyId).orElseThrow());

        assertThat(renderer.isInvalidated(published, companyId)).isFalse();

        // 정정: 증가율이 문턱값 아래로 내려가 신호가 철회된다.
        seedQuarter("2,200,000,000", "2,000,000,000", "R2");
        signalJob.run();

        assertThat(renderer.isInvalidated(published, companyId)).isTrue();
    }

    @Test
    void newQuarterArrivalAloneDoesNotInvalidate() {
        // D-42: 새 보고서 도착만으로는 내리지 않는다. 참조한 신호·사실이 그대로면 무효화가 아니다.
        seedQuarter("4,000,000,000", "2,000,000,000", "R1");
        signalJob.run();
        Analysis published = publish(inputBuilder.build(companyId).orElseThrow());
        assertThat(renderer.isInvalidated(published, companyId)).isFalse();

        // 다음 분기(반기) 보고서가 새로 들어온다. Q1 신호·사실은 그대로다.
        List<RawAccountLine> h1 = List.of(
                line("BS", "유동자산", "1", "2026.06.30 현재", "3,200,000,000", "2,600,000,000", "R2"),
                line("BS", "자산총계", "5", "2026.06.30 현재", "9,200,000,000", "8,200,000,000", "R2"),
                line("BS", "부채총계", "9", "2026.06.30 현재", "4,100,000,000", "3,600,000,000", "R2"),
                line("BS", "자본총계", "13", "2026.06.30 현재", "5,100,000,000", "4,600,000,000", "R2"),
                line("IS", "매출액", "23", "2026.01.01 ~ 2026.06.30", "2,100,000,000", "1,900,000,000", "R2"),
                line("IS", "영업이익", "27", "2026.01.01 ~ 2026.06.30", "300,000,000", "250,000,000", "R2"));
        financialService.replace(companyId, 2026, "11012", h1, null);
        signalJob.run();

        assertThat(renderer.isInvalidated(published, companyId)).isFalse();
    }

    @Test
    void severityOrRuleVersionChangeAloneDoesNotInvalidate() {
        // D-42: 참조 신호의 심각도·규칙 버전 변경, 활성→이력 전환은 무효화 조건이 아니다(방향 변경·철회만 본다).
        seedQuarter("4,000,000,000", "2,000,000,000", "R1");
        signalJob.run();
        Analysis published = publish(inputBuilder.build(companyId).orElseThrow());
        assertThat(renderer.isInvalidated(published, companyId)).isFalse();

        jdbc.sql("update company_signal set severity = 'LOW', rule_version = 'fin-9', status = 'PAST' "
                        + "where company_id = :companyId and signal_type = 'FIN_REVENUE_CHANGE' and direction = 'POSITIVE'")
                .param("companyId", companyId)
                .update();

        assertThat(renderer.isInvalidated(published, companyId)).isFalse();
    }

    @Test
    void referencedSignalDirectionChangeInvalidates() {
        // D-42: 참조한 신호의 방향이 바뀌면(철회가 아니어도) 무효화한다.
        seedQuarter("4,000,000,000", "2,000,000,000", "R1");
        signalJob.run();
        Analysis published = publish(inputBuilder.build(companyId).orElseThrow());
        assertThat(renderer.isInvalidated(published, companyId)).isFalse();

        jdbc.sql("update company_signal set direction = 'NEGATIVE' "
                        + "where company_id = :companyId and signal_type = 'FIN_REVENUE_CHANGE' and direction = 'POSITIVE'")
                .param("companyId", companyId)
                .update();

        assertThat(renderer.isInvalidated(published, companyId)).isTrue();
    }

    private Analysis publish(FinancialExplainInputBuilder.BuildResult built) {
        NewAnalysis draft = new NewAnalysis(TargetType.COMPANY, String.valueOf(companyId), AnalysisKind.FINANCIAL_EXPLAIN,
                built.fingerprint(), AnalysisStatus.DRAFT, null, built.input(), built.snapshot(), "fx-schema-1", "fx-v1",
                "claude-sonnet-5", FinancialExplainInputBuilder.INPUT_BUILDER_VERSION, "fin-2", 100, 50, 0,
                java.math.BigDecimal.ONE, null, 1, null);
        long id = analysisService.save(draft, Instant.now());
        analysisService.publish(id, TargetType.COMPANY, String.valueOf(companyId), AnalysisKind.FINANCIAL_EXPLAIN, Instant.now());
        return analysisService.findCurrent(TargetType.COMPANY, String.valueOf(companyId), AnalysisKind.FINANCIAL_EXPLAIN)
                .orElseThrow();
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
}
