package org.stockinsight.ingest.disclosure;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
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
import org.stockinsight.disclosure.Disclosure;
import org.stockinsight.disclosure.DisclosureService;
import org.stockinsight.disclosure.DisclosureService.NewDisclosure;
import org.stockinsight.disclosure.DisclosureType;
import org.stockinsight.ingest.checkpoint.IngestCheckpoint;
import org.stockinsight.ingest.checkpoint.IngestCheckpointRepository;
import org.stockinsight.ingest.dart.DartApi;
import org.stockinsight.ingest.dart.DartApiException;
import org.stockinsight.ingest.dart.DartCompanyOverview;
import org.stockinsight.ingest.dart.DartCorpCode;
import org.stockinsight.ingest.dart.DartDisclosure;
import org.stockinsight.ingest.dart.DartDisclosurePage;
import org.stockinsight.ingest.dart.DartStatus;

/**
 * 인증키 없이 가짜 OpenDART로 공시 목록 수집 규칙을 검증한다.
 * 적재 기간은 2일(오늘 포함 3일 × 3유형 = 9단위)이고, 가짜 응답은 한 페이지에 2건씩 준다.
 * 공시 내용은 실제 응답(2026-08-14)의 형태를 따른다.
 */
@SpringBootTest(properties = {
        "app.ingest.disclosure-sync.max-calls-per-run=100",
        "app.ingest.disclosure-sync.initial-load-period=2d"
})
@Import({TestcontainersConfiguration.class, DisclosureSyncJobTest.FakeDartConfig.class})
class DisclosureSyncJobTest {

    static final LocalDate TODAY = LocalDate.of(2026, 9, 24);
    static final LocalDate D1 = TODAY.minusDays(1);
    static final LocalDate D2 = TODAY.minusDays(2);

    static final String SAMSUNG = "00126380";
    static final String ICURE = "00554352";
    static final String KONEX = "00000002";

    @Autowired
    DisclosureSyncJob job;

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

    long icureId;

    @BeforeEach
    void setUp() {
        icureId = seed(jdbc, clock, dart, companyService);
    }

    /** 테스트 데이터를 초기화하고 아이큐어의 기업 ID를 돌려준다. 호출 한도 테스트와 함께 쓴다. */
    static long seed(JdbcClient jdbc, MutableClock clock, FakeDartApi dart, CompanyService companyService) {
        jdbc.sql("truncate disclosure, company_alias, security, company, ingest_checkpoint, pipeline_run restart identity cascade")
                .update();
        dart.reset();
        // KST 2026-09-24 10:00
        clock.set(Instant.parse("2026-09-24T01:00:00Z"));

        companyService.upsertListed(listed(SAMSUNG, "삼성전자", "005930", Market.KOSPI));
        long icureId = companyService.upsertListed(listed(ICURE, "아이큐어", "175250", Market.KOSDAQ)).getId();
        companyService.upsertListed(listed(KONEX, "코넥스기업", "123450", Market.KONEX));

        dart.add(D1, "B", item(SAMSUNG, "주요사항보고서(자기주식취득결정)", "20260923000100", ""));
        dart.add(D1, "B", item(KONEX, "주요사항보고서(유상증자결정)", "20260923000110", ""));
        dart.add(D1, "B", item("01979275", "유상증자결정", "20260923000120", "공"));
        dart.add(D1, "B", item(ICURE, "[기재정정]주요사항보고서(유상증자결정)", "20260923000300", ""));
        dart.add(D2, "B", item(ICURE, "주요사항보고서(유상증자결정)", "20260922000200", "정"));
        for (int i = 1; i <= 5; i++) {
            dart.add(D2, "I", item(SAMSUNG, "기업설명회(IR)개최(안내공시)", "2026092290000" + i, "유"));
        }
        return icureId;
    }

    @Test
    void initialLoadStoresActiveCompaniesOnlyAndLinksAmendments() {
        DisclosureSyncJob.Result result = job.run();

        assertThat(result.status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(result.summary()).contains("대상 9(당일 3, 미확정·오류 0, 초기 적재 6)");
        assertThat(storedReceiptNos()).containsExactlyInAnyOrder(
                "20260923000100", "20260923000300", "20260922000200",
                "2026092290000" + 1, "2026092290000" + 2, "2026092290000" + 3, "2026092290000" + 4, "2026092290000" + 5);

        Disclosure amendment = disclosureService.findByReceiptNo("20260923000300").orElseThrow();
        assertThat(amendment.companyId()).isEqualTo(icureId);
        assertThat(amendment.type()).isEqualTo(DisclosureType.MAJOR_EVENT);
        assertThat(amendment.amendmentLabel()).isEqualTo("기재정정");
        assertThat(amendment.baseReportName()).isEqualTo("주요사항보고서(유상증자결정)");
        assertThat(amendment.receivedOn()).isEqualTo(D1);
        assertThat(amendment.originalReceiptNo()).isEqualTo("20260922000200");

        Disclosure original = disclosureService.findByReceiptNo("20260922000200").orElseThrow();
        assertThat(original.originalReceiptNo()).isNull();
        assertThat(original.remark()).isEqualTo("정");

        // 5건을 2건씩 3페이지로 읽는다.
        assertThat(dart.calls).contains(D2 + ":I:1", D2 + ":I:2", D2 + ":I:3").doesNotContain(D2 + ":I:4");
        assertThat(checkpoints.findAllBySource(DisclosureSyncJob.CHECKPOINT_SOURCE))
                .hasSize(9)
                .hasEntrySatisfying(D1 + ":B", checkpoint -> {
                    assertThat(checkpoint.result()).isEqualTo(IngestCheckpoint.Result.SUCCESS);
                    assertThat(checkpoint.message()).isEqualTo("공시 4건 중 ACTIVE 기업 2건");
                })
                .hasEntrySatisfying(D1 + ":A",
                        checkpoint -> assertThat(checkpoint.result()).isEqualTo(IngestCheckpoint.Result.NO_DATA));
    }

    @Test
    void dayIsReadAgainOnceAfterItEnds() {
        job.run();
        // 오늘 오후에 공시가 더 들어온다.
        dart.add(TODAY, "A", item(SAMSUNG, "반기보고서 (2026.06)", "20260924000500", ""));

        clock.advance(Duration.ofDays(1));
        dart.calls.clear();
        DisclosureSyncJob.Result nextDay = job.run();

        assertThat(nextDay.summary()).contains("대상 6(당일 3, 미확정·오류 3, 초기 적재 0)");
        assertThat(disclosureService.findByReceiptNo("20260924000500")).isPresent();

        dart.calls.clear();
        job.run();
        assertThat(dart.calls).allMatch(call -> call.startsWith(TODAY.plusDays(1) + ":"));
    }

    @Test
    void rereadUpdatesRemarkWithoutDuplicating() {
        dart.add(TODAY, "B", item(SAMSUNG, "주요사항보고서(회사분할결정)", "20260924000700", ""));
        job.runToday();

        dart.replace(TODAY, "B", item(SAMSUNG, "주요사항보고서(회사분할결정)", "20260924000700", "정"));
        DisclosureSyncJob.Result reread = job.runToday();

        assertThat(reread.summary()).contains("공시 신규 0·갱신 1");
        assertThat(disclosureService.findByReceiptNo("20260924000700").orElseThrow().remark()).isEqualTo("정");
        assertThat(countDisclosures()).isEqualTo(1);
    }

    @Test
    void intradayRunReadsTodayOnly() {
        assertThat(job.runToday().status()).isEqualTo(RunStatus.SUCCEEDED);

        assertThat(dart.calls).containsExactlyInAnyOrder(TODAY + ":A:1", TODAY + ":B:1", TODAY + ":I:1");
        assertThat(checkpoints.findAllBySource(DisclosureSyncJob.CHECKPOINT_SOURCE)).hasSize(3);
    }

    @Test
    void refusesToRunWithoutActiveCompanies() {
        jdbc.sql("truncate company_alias, security, company restart identity cascade").update();

        assertThat(job.run().status()).isEqualTo(RunStatus.FAILED);
        assertThat(dart.calls).isEmpty();
        assertThat(checkpoints.findAllBySource(DisclosureSyncJob.CHECKPOINT_SOURCE)).isEmpty();
    }

    @Test
    void itemErrorIsRecordedAndRetriedNextRun() {
        dart.failWith(D1, "B", DartStatus.INVALID_FIELD);
        assertThat(job.run().status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(checkpoints.findAllBySource(DisclosureSyncJob.CHECKPOINT_SOURCE).get(D1 + ":B").result())
                .isEqualTo(IngestCheckpoint.Result.ERROR);

        dart.failures.clear();
        dart.calls.clear();
        DisclosureSyncJob.Result retry = job.run();

        assertThat(retry.summary()).contains("미확정·오류 1");
        assertThat(dart.calls).contains(D1 + ":B:1");
        assertThat(disclosureService.findByReceiptNo("20260923000100")).isPresent();
    }

    @Test
    void requestLimitStopsRunWithoutRecordingTheDay() {
        dart.failWith(D1, "A", DartStatus.REQUEST_LIMIT_EXCEEDED);

        assertThat(job.run().status()).isEqualTo(RunStatus.FAILED);
        assertThat(checkpoints.findAllBySource(DisclosureSyncJob.CHECKPOINT_SOURCE))
                .containsOnlyKeys(TODAY + ":A", TODAY + ":B", TODAY + ":I");
    }

    @Test
    void amendmentLinksToClosestEarlierOriginalWithSameName() {
        String name = "주요사항보고서(유상증자결정)";
        disclosureService.saveAll(List.of(
                disclosure("20260105000001", "[기재정정]" + name),
                disclosure("20260301000001", name),
                disclosure("20260620000001", name),
                disclosure("20260701000001", "[기재정정]" + name),
                disclosure("20260702000001", "[첨부정정]" + name)));

        // 원 공시가 수집 범위 밖이면 연결하지 않는다.
        assertThat(disclosureService.findByReceiptNo("20260105000001").orElseThrow().originalReceiptNo()).isNull();
        // 같은 이름의 원 공시가 여럿이면 앞선 것 중 가장 최근 것에 연결한다. 정정이 거듭돼도 모두 최초 제출에 연결한다.
        assertThat(disclosureService.findByReceiptNo("20260701000001").orElseThrow().originalReceiptNo())
                .isEqualTo("20260620000001");
        assertThat(disclosureService.findByReceiptNo("20260702000001").orElseThrow().originalReceiptNo())
                .isEqualTo("20260620000001");
    }

    private NewDisclosure disclosure(String receiptNo, String reportName) {
        return new NewDisclosure(receiptNo, icureId, DisclosureType.MAJOR_EVENT, reportName,
                LocalDate.parse(receiptNo.substring(0, 8), DateTimeFormatter.BASIC_ISO_DATE),
                "아이큐어", "");
    }

    private List<String> storedReceiptNos() {
        return jdbc.sql("select receipt_no from disclosure").query(String.class).list();
    }

    private long countDisclosures() {
        return jdbc.sql("select count(*) from disclosure").query(Long.class).single();
    }

    private static ListedCompany listed(String corpCode, String name, String ticker, Market market) {
        return new ListedCompany(corpCode, name, name + "(주)", ticker, market, "264", 12);
    }

    private static DartDisclosure item(String corpCode, String reportName, String receiptNo, String remark) {
        return new DartDisclosure(corpCode, "이름", "", "Y", reportName, receiptNo, "제출인",
                receiptNo.substring(0, 8), remark);
    }

    static class FakeDartApi implements DartApi {

        final Map<String, List<DartDisclosure>> disclosures = new HashMap<>();
        final Map<String, DartStatus> failures = new HashMap<>();
        final List<String> calls = new ArrayList<>();
        final int pageSize = 2;

        void reset() {
            disclosures.clear();
            failures.clear();
            calls.clear();
        }

        void add(LocalDate date, String type, DartDisclosure item) {
            disclosures.computeIfAbsent(date + ":" + type, key -> new ArrayList<>()).add(item);
        }

        void replace(LocalDate date, String type, DartDisclosure item) {
            disclosures.get(date + ":" + type).replaceAll(old -> old.receiptNo().equals(item.receiptNo()) ? item : old);
        }

        void failWith(LocalDate date, String type, DartStatus status) {
            failures.put(date + ":" + type, status);
        }

        @Override
        public DartDisclosurePage fetchDisclosures(LocalDate receivedOn, String disclosureType, int pageNo) {
            String key = receivedOn + ":" + disclosureType;
            calls.add(key + ":" + pageNo);
            DartStatus failure = failures.get(key);
            if (failure != null) {
                throw new DartApiException(failure, "fake " + failure.code());
            }
            List<DartDisclosure> all = disclosures.getOrDefault(key, List.of());
            if (all.isEmpty()) {
                return new DartDisclosurePage("013", "조회된 데이타가 없습니다.", pageNo, 0, 0, List.of());
            }
            int totalPage = (all.size() + pageSize - 1) / pageSize;
            int from = Math.min(all.size(), (pageNo - 1) * pageSize);
            List<DartDisclosure> page = all.subList(from, Math.min(all.size(), from + pageSize));
            return new DartDisclosurePage("000", "정상", pageNo, all.size(), totalPage, page);
        }

        @Override
        public List<DartCorpCode> fetchCorpCodes() {
            throw new UnsupportedOperationException("공시 목록 수집은 고유번호 파일을 읽지 않는다");
        }

        @Override
        public Optional<DartCompanyOverview> fetchCompany(String corpCode) {
            throw new UnsupportedOperationException("공시 목록 수집은 기업개황을 읽지 않는다");
        }
    }

    static class MutableClock extends Clock {

        private Instant instant = Instant.EPOCH;

        void set(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
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
