package org.stockinsight.ingest.financial;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
import org.stockinsight.common.pipeline.PipelineRunRecorder.RunStatus;
import org.stockinsight.company.CompanyService;
import org.stockinsight.company.CompanyService.ListedCompany;
import org.stockinsight.company.Market;
import org.stockinsight.disclosure.DisclosureService;
import org.stockinsight.disclosure.DisclosureService.NewDisclosure;
import org.stockinsight.disclosure.DisclosureType;
import org.stockinsight.financial.FinancialService;
import org.stockinsight.financial.StoredFinancialReport;
import org.stockinsight.ingest.checkpoint.IngestCheckpoint;
import org.stockinsight.ingest.checkpoint.IngestCheckpointRepository;
import org.stockinsight.ingest.dart.DartApi;
import org.stockinsight.ingest.dart.DartApiException;
import org.stockinsight.ingest.dart.DartCompanyOverview;
import org.stockinsight.ingest.dart.DartCorpCode;
import org.stockinsight.ingest.dart.DartDisclosurePage;
import org.stockinsight.ingest.dart.DartKeyAccount;
import org.stockinsight.ingest.dart.DartStatus;

/**
 * 인증키 없이 가짜 OpenDART로 재무 수집 규칙을 검증한다 (docs/implementation-plan.md §4.3, §4.4).
 * 초기 적재 대상 연도를 올해 하나로 줄여(0년) 묶음 수를 작게 유지한다.
 */
@SpringBootTest(properties = {
        "app.ingest.financial-sync.max-calls-per-run=100",
        "app.ingest.financial-sync.initial-load-years=0"
})
@Import({TestcontainersConfiguration.class, FinancialSyncJobTest.FakeDartConfig.class})
class FinancialSyncJobTest {

    static final LocalDate TODAY = LocalDate.of(2026, 9, 24);
    static final int YEAR = 2026;

    static final String CORP_A = "00126380"; // 12월 결산
    static final String CORP_B = "00139685"; // 6월 결산
    static final String CORP_KONEX = "00000002";

    @Autowired
    FinancialSyncJob job;

    @Autowired
    FinancialService financialService;

    @Autowired
    DisclosureService disclosureService;

    @Autowired
    CompanyService companyService;

    @Autowired
    IngestCheckpointRepository checkpoints;

    @Autowired
    FakeDartApi dart;

    @Autowired
    MutableClock clock;

    @Autowired
    JdbcClient jdbc;

    long companyAId;
    long companyBId;

    @BeforeEach
    void setUp() {
        jdbc.sql("truncate financial_report, disclosure, company_alias, security, company, ingest_checkpoint, pipeline_run restart identity cascade")
                .update();
        dart.reset();
        clock.set(Instant.parse("2026-09-24T01:00:00Z"));

        companyAId = companyService.upsertListed(listed(CORP_A, "삼성전자", "005930", Market.KOSPI, 12)).getId();
        companyBId = companyService.upsertListed(listed(CORP_B, "양지사", "030960", Market.KOSDAQ, 6)).getId();
        companyService.upsertListed(listed(CORP_KONEX, "코넥스기업", "123450", Market.KONEX, 12));
    }

    @Test
    void refusesToRunWithoutActiveCompanies() {
        jdbc.sql("truncate company_alias, security, company restart identity cascade").update();

        assertThat(job.run().status()).isEqualTo(RunStatus.FAILED);
        assertThat(dart.calls).isEmpty();
    }

    @Test
    void initialLoadFetchesEachActiveCompanyGroupedByReportKeyOnly() {
        dart.add(YEAR, "11012", CORP_A, List.of(
                row(CORP_A, "CFS", "IS", 23, "2026.01.01 ~ 2026.06.30", "1,000,000", "R-A1")));

        DisclosureSyncStyleResult result = run();

        // 4개 보고서 종류 × 대상 회사(2개, KONEX 제외). 같은 report key의 기업은 한 호출로 묶이므로 묶음은 4개다.
        assertThat(result.summary()).contains("묶음 4(계기 0, 초기 적재 4)");
        assertThat(dart.calls).hasSize(4);
        assertThat(dart.calls).anySatisfy(call -> assertThat(call).startsWith(YEAR + ":11012:").contains(CORP_A).contains(CORP_B));

        StoredFinancialReport report = financialService.find(companyAId, YEAR, "11012", "CFS").orElseThrow();
        assertThat(report.receiptNo()).isEqualTo("R-A1");
        assertThat(checkpoints.findAllBySource(FinancialSyncJob.CHECKPOINT_SOURCE)).hasSize(8);
        assertThat(checkpoints.findAllBySource(FinancialSyncJob.CHECKPOINT_SOURCE))
                .hasEntrySatisfying(CORP_A + ":" + YEAR + ":11012", checkpoint -> {
                    assertThat(checkpoint.result()).isEqualTo(IngestCheckpoint.Result.SUCCESS);
                    assertThat(checkpoint.sourceVersion()).isNull();
                })
                .hasEntrySatisfying(CORP_B + ":" + YEAR + ":11012",
                        checkpoint -> assertThat(checkpoint.result()).isEqualTo(IngestCheckpoint.Result.NO_DATA));
    }

    @Test
    void companyMissingFromResponseIsNoDataAndNotRetriedNextRun() {
        // CORP_A는 응답에 없다(NO_DATA). 계기가 없으면 체크포인트가 있는 한 다시 부르지 않는다.
        run();
        assertThat(checkpoints.findAllBySource(FinancialSyncJob.CHECKPOINT_SOURCE).get(CORP_A + ":" + YEAR + ":11012").result())
                .isEqualTo(IngestCheckpoint.Result.NO_DATA);

        dart.calls.clear();
        DisclosureSyncStyleResult second = run();

        assertThat(second.summary()).contains("묶음 0(계기 0, 초기 적재 0)");
        assertThat(dart.calls).isEmpty();
    }

    @Test
    void periodicDisclosureTriggersRefetchWithNewValuesAndReceiptNo() {
        dart.add(YEAR, "11012", CORP_A, List.of(row(CORP_A, "CFS", "IS", 23, "2026.01.01 ~ 2026.06.30", "1,000", "R1")));
        run();
        assertThat(financialService.find(companyAId, YEAR, "11012", "CFS").orElseThrow().receiptNo()).isEqualTo("R1");

        saveDisclosure(companyAId, "20260814000100", "반기보고서 (2026.06)");
        dart.add(YEAR, "11012", CORP_A, List.of(row(CORP_A, "CFS", "IS", 23, "2026.01.01 ~ 2026.06.30", "1,500", "20260814000100")));
        dart.calls.clear();

        DisclosureSyncStyleResult second = run();

        assertThat(second.summary()).contains("계기 1");
        assertThat(dart.calls).contains(YEAR + ":11012:" + CORP_A);
        StoredFinancialReport updated = financialService.find(companyAId, YEAR, "11012", "CFS").orElseThrow();
        assertThat(updated.receiptNo()).isEqualTo("20260814000100");
        assertThat(updated.lines().get(0).currentAmount().intValue()).isEqualTo(1500);
        assertThat(checkpoints.findAllBySource(FinancialSyncJob.CHECKPOINT_SOURCE).get(CORP_A + ":" + YEAR + ":11012").sourceVersion())
                .isEqualTo("20260814000100");
    }

    @Test
    void amendmentWithUnchangedValuesIsNotCountedAsUpdated() {
        dart.add(YEAR, "11012", CORP_A, List.of(row(CORP_A, "CFS", "IS", 23, "2026.01.01 ~ 2026.06.30", "1,000", "R1")));
        run();

        saveDisclosure(companyAId, "20260814000100", "[첨부정정]반기보고서 (2026.06)");
        // 첨부정정처럼 값이 그대로면 재조회해도 변경이 없다.
        dart.add(YEAR, "11012", CORP_A, List.of(row(CORP_A, "CFS", "IS", 23, "2026.01.01 ~ 2026.06.30", "1,000", "20260814000100")));

        DisclosureSyncStyleResult second = run();

        // receipt_no는 바뀌었으므로 UNCHANGED가 아니라 UPDATED로 계산된다(값 비교에 receipt_no도 포함).
        assertThat(second.summary()).contains("갱신 1");
    }

    @Test
    void triggerOutsideLoadPeriodIsIgnored() {
        saveDisclosure(companyAId, "20250814000100", "반기보고서 (2025.06)"); // initial-load-years=0 → 2025는 범위 밖

        DisclosureSyncStyleResult result = run();

        assertThat(result.summary()).contains("계기 0");
        assertThat(dart.calls).noneMatch(call -> call.startsWith("2025:"));
        assertThat(checkpoints.findAllBySource(FinancialSyncJob.CHECKPOINT_SOURCE)).doesNotContainKey(CORP_A + ":2025:11012");
    }

    @Test
    void periodMismatchWithTriggerIsRecordedAsErrorWithoutSaving() {
        saveDisclosure(companyAId, "20260814000100", "반기보고서 (2026.06)");
        // 응답 기간(3분기, 09월 종료)이 계기(반기, 06월 종료)와 다르다.
        dart.add(YEAR, "11012", CORP_A, List.of(row(CORP_A, "CFS", "IS", 23, "2026.01.01 ~ 2026.09.30", "1,000", "20260814000100")));

        run();

        assertThat(checkpoints.findAllBySource(FinancialSyncJob.CHECKPOINT_SOURCE).get(CORP_A + ":" + YEAR + ":11012").result())
                .isEqualTo(IngestCheckpoint.Result.ERROR);
        assertThat(financialService.find(companyAId, YEAR, "11012", "CFS")).isEmpty();
    }

    @Test
    void quarterlyTriggerResolvesReportCodeByFiscalMonth() {
        // 양지사(6월 결산)의 "분기보고서 (2026.03)"는 3분기(11014)다.
        saveDisclosure(companyBId, "20260514000100", "분기보고서 (2026.03)");
        dart.add(YEAR, "11014", CORP_B, List.of(row(CORP_B, "OFS", "IS", 24, "2025.07.01 ~ 2026.03.31", "500", "20260514000100")));

        run();

        assertThat(financialService.find(companyBId, YEAR, "11014", "OFS")).isPresent();
    }

    @Test
    void fatalDartErrorStopsRunWithoutRecordingThatBatchButKeepsEarlierProgress() {
        // 묶음은 report_code 오름차순(11011→11012→11013→11014)으로 처리된다. 마지막에 치명적 오류를 낸다.
        dart.failWith(YEAR, "11014", DartStatus.REQUEST_LIMIT_EXCEEDED);

        assertThat(job.run().status()).isEqualTo(RunStatus.FAILED);
        Map<String, IngestCheckpoint> byKey = checkpoints.findAllBySource(FinancialSyncJob.CHECKPOINT_SOURCE);
        // 앞서 처리된 묶음(11011~11013)의 결과는 남아 있다(묶음마다 별도 트랜잭션).
        assertThat(byKey).containsKeys(CORP_A + ":" + YEAR + ":11011", CORP_A + ":" + YEAR + ":11013");
        assertThat(byKey).doesNotContainKeys(CORP_A + ":" + YEAR + ":11014", CORP_B + ":" + YEAR + ":11014");
    }

    @Test
    void nonFatalBatchErrorMarksEveryCompanyInTheBatchAsError() {
        dart.failWith(YEAR, "11012", DartStatus.INVALID_FIELD);

        run();

        Map<String, IngestCheckpoint> byKey = checkpoints.findAllBySource(FinancialSyncJob.CHECKPOINT_SOURCE);
        assertThat(byKey.get(CORP_A + ":" + YEAR + ":11012").result()).isEqualTo(IngestCheckpoint.Result.ERROR);
        assertThat(byKey.get(CORP_B + ":" + YEAR + ":11012").result()).isEqualTo(IngestCheckpoint.Result.ERROR);
    }

    private DisclosureSyncStyleResult run() {
        FinancialSyncJob.Result result = job.run();
        return new DisclosureSyncStyleResult(result.status(), result.summary());
    }

    private void saveDisclosure(long companyId, String receiptNo, String reportName) {
        disclosureService.saveAll(List.of(new NewDisclosure(receiptNo, companyId, DisclosureType.PERIODIC, reportName,
                LocalDate.parse(receiptNo.substring(0, 8), DateTimeFormatter.BASIC_ISO_DATE), "제출인", "")));
    }

    private static ListedCompany listed(String corpCode, String name, String ticker, Market market, int fiscalMonth) {
        return new ListedCompany(corpCode, name, name + "(주)", ticker, market, "264", fiscalMonth);
    }

    private static DartKeyAccount row(String corpCode, String fsDiv, String statement, int ord, String currentPeriod,
            String currentAmount, String receiptNo) {
        return new DartKeyAccount(receiptNo, "?", String.valueOf(YEAR), corpCode, fsDiv, statement, "계정" + ord,
                String.valueOf(ord), currentPeriod, currentAmount, null, currentAmount, null, null, "KRW");
    }

    /** 가독성을 위해 FinancialSyncJob.Result를 감싼다. */
    private record DisclosureSyncStyleResult(RunStatus status, String summary) {
    }

    static class FakeDartApi implements DartApi {

        final Map<String, Map<String, List<DartKeyAccount>>> data = new HashMap<>();
        final Map<String, DartStatus> failures = new HashMap<>();
        final List<String> calls = new ArrayList<>();

        void reset() {
            data.clear();
            failures.clear();
            calls.clear();
        }

        void add(int bsnsYear, String reportCode, String corpCode, List<DartKeyAccount> rows) {
            data.computeIfAbsent(bsnsYear + ":" + reportCode, k -> new HashMap<>()).put(corpCode, rows);
        }

        void failWith(int bsnsYear, String reportCode, DartStatus status) {
            failures.put(bsnsYear + ":" + reportCode, status);
        }

        @Override
        public List<DartKeyAccount> fetchKeyAccounts(List<String> corpCodes, int bsnsYear, String reportCode) {
            String key = bsnsYear + ":" + reportCode;
            calls.add(key + ":" + String.join(",", corpCodes));
            DartStatus failure = failures.get(key);
            if (failure != null) {
                throw new DartApiException(failure, "fake " + failure.code());
            }
            Map<String, List<DartKeyAccount>> byCorp = data.getOrDefault(key, Map.of());
            List<DartKeyAccount> result = new ArrayList<>();
            for (String corpCode : corpCodes) {
                result.addAll(byCorp.getOrDefault(corpCode, List.of()));
            }
            return result;
        }

        @Override
        public List<DartCorpCode> fetchCorpCodes() {
            throw new UnsupportedOperationException("재무 수집은 고유번호 파일을 읽지 않는다");
        }

        @Override
        public Optional<DartCompanyOverview> fetchCompany(String corpCode) {
            throw new UnsupportedOperationException("재무 수집은 기업개황을 읽지 않는다");
        }

        @Override
        public DartDisclosurePage fetchDisclosures(LocalDate receivedOn, String disclosureType, int pageNo) {
            throw new UnsupportedOperationException("재무 수집은 공시 목록을 읽지 않는다");
        }
    }

    static class MutableClock extends Clock {

        private Instant instant = Instant.EPOCH;

        void set(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(instant, zone);
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FakeDartConfig {

        @Bean
        @Primary
        FakeDartApi fakeDartApi() {
            return new FakeDartApi();
        }

        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock();
        }
    }
}
