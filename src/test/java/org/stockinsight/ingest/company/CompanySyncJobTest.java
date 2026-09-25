package org.stockinsight.ingest.company;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
import org.stockinsight.company.Company;
import org.stockinsight.company.CompanyService;
import org.stockinsight.company.CompanyStatus;
import org.stockinsight.company.ExclusionReason;
import org.stockinsight.company.Market;
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
 * 인증키 없이 가짜 OpenDART로 동기화 규칙을 검증한다. 한 실행의 호출 상한은 3건(고유번호 1 + 기업개황 2)이다.
 */
@SpringBootTest(properties = {
        "app.ingest.company-sync.max-calls-per-run=3",
        "app.ingest.company-sync.min-listed-count=1",
        "app.ingest.company-sync.max-delisted-ratio=0.5"
})
@Import({TestcontainersConfiguration.class, CompanySyncJobTest.FakeDartConfig.class})
class CompanySyncJobTest {

    @Autowired
    CompanySyncJob job;

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

    @BeforeEach
    void setUp() {
        jdbc.sql("truncate company_alias, security, company, ingest_checkpoint, pipeline_run restart identity cascade")
                .update();
        dart.reset();
        clock.set(Instant.parse("2026-09-24T20:00:00Z"));

        dart.put(corp("00126380", "005930", "20250101"), overview("00126380", "삼성전자", "삼성전자(주)", "005930", "Y"));
        dart.put(corp("00000002", "123450", "20250101"), overview("00000002", "코넥스기업", "코넥스기업(주)", "123450", "N"));
        dart.put(corp("00000003", "123460", "20250101"), overview("00000003", "하나33호스팩", "하나33호기업인수목적(주)", "123460", "K"));
        dart.put(corp("00000004", "0010V0", "20250101"), overview("00000004", "신규상장테크", "(주)신규상장테크", "0010V0", "K"));
        dart.putUnlisted(new DartCorpCode("00434003", "다코", null, "20170630"));
    }

    @Test
    void resumesAcrossRunsWithinCallLimitAndClassifiesScope() {
        CompanySyncJob.Result first = job.run();
        assertThat(first.status()).isEqualTo(RunStatus.PARTIAL);
        assertThat(dart.companyCalls).hasSize(2);

        CompanySyncJob.Result second = job.run();
        assertThat(second.status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(dart.companyCalls).hasSize(4).doesNotHaveDuplicates().doesNotContain("00434003");

        Company samsung = companyService.findByDartCorpCode("00126380").orElseThrow();
        assertThat(samsung.getName()).isEqualTo("삼성전자");
        assertThat(samsung.getLegalName()).isEqualTo("삼성전자(주)");
        assertThat(samsung.getNameInitials()).isEqualTo("ㅅㅅㅈㅈ");
        assertThat(samsung.getStatus()).isEqualTo(CompanyStatus.ACTIVE);
        assertThat(samsung.getIndustryCode()).isEqualTo("264");
        assertThat(samsung.getFiscalMonth()).isEqualTo(12);
        assertThat(companyService.commonSecurityOf(samsung).orElseThrow())
                .satisfies(security -> {
                    assertThat(security.getTicker()).isEqualTo("005930");
                    assertThat(security.getMarket()).isEqualTo(Market.KOSPI);
                    assertThat(security.getCurrency()).isEqualTo("KRW");
                });

        assertThat(companyService.findByDartCorpCode("00000002").orElseThrow().getExclusionReason())
                .isEqualTo(ExclusionReason.KONEX);
        assertThat(companyService.findByDartCorpCode("00000003").orElseThrow().getExclusionReason())
                .isEqualTo(ExclusionReason.SPAC);
        assertThat(companyService.findByDartCorpCode("00000004").orElseThrow().getStatus())
                .isEqualTo(CompanyStatus.ACTIVE);
        assertThat(companyService.findByDartCorpCode("00434003")).isEmpty();

        dart.companyCalls.clear();
        assertThat(job.run().status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(dart.companyCalls).isEmpty();

        assertThat(runStatuses()).containsExactly("PARTIAL", "SUCCEEDED", "SUCCEEDED");
    }

    @Test
    void requestLimitStopsRunAndNextRunContinues() {
        dart.failWith("00000002", DartStatus.REQUEST_LIMIT_EXCEEDED);

        assertThat(job.run().status()).isEqualTo(RunStatus.FAILED);
        assertThat(checkpoints.findAllBySource(CompanySyncJob.CHECKPOINT_SOURCE)).doesNotContainKey("00000002");

        dart.failures.clear();
        runUntilDone();

        assertThat(checkpoints.findAllBySource(CompanySyncJob.CHECKPOINT_SOURCE)).hasSize(4)
                .allSatisfy((code, checkpoint) -> assertThat(checkpoint.result()).isEqualTo(IngestCheckpoint.Result.SUCCESS));
        assertThat(runStatuses()).first().isEqualTo("FAILED");
    }

    @Test
    void itemErrorIsRecordedAndRetriedFirstNextRun() {
        dart.failWith("00000002", DartStatus.INVALID_FIELD);
        runUntilDone();

        IngestCheckpoint failed = checkpoints.findAllBySource(CompanySyncJob.CHECKPOINT_SOURCE).get("00000002");
        assertThat(failed.result()).isEqualTo(IngestCheckpoint.Result.ERROR);
        // 첫 실행에서 실패하고, 이후 실행마다 다시 시도해 횟수가 쌓인다.
        assertThat(failed.attemptCount()).isGreaterThanOrEqualTo(1);

        dart.failures.clear();
        dart.companyCalls.clear();
        job.run();

        assertThat(dart.companyCalls).containsExactly("00000002");
        assertThat(checkpoints.findAllBySource(CompanySyncJob.CHECKPOINT_SOURCE).get("00000002").result())
                .isEqualTo(IngestCheckpoint.Result.SUCCESS);
    }

    @Test
    void refetchesWhenCorpCodeFileChangesAndKeepsPreviousNameAsAlias() {
        runUntilDone();

        dart.put(corp("00126380", "005930", "20260920"), overview("00126380", "삼성전자신", "삼성전자신(주)", "005930", "Y"));
        dart.companyCalls.clear();
        job.run();

        assertThat(dart.companyCalls).containsExactly("00126380");
        Company renamed = companyService.findByDartCorpCode("00126380").orElseThrow();
        assertThat(renamed.getName()).isEqualTo("삼성전자신");
        assertThat(companyService.aliasesOf(renamed)).containsExactly("삼성전자");
    }

    @Test
    void refreshesStaleOverviewsToCatchMarketTransfers() {
        runUntilDone();

        dart.put(corp("00000002", "123450", "20250101"), overview("00000002", "코넥스기업", "코넥스기업(주)", "123450", "K"));
        clock.advance(Duration.ofDays(31));
        runUntilDone();

        assertThat(companyService.findByDartCorpCode("00000002").orElseThrow().getStatus())
                .isEqualTo(CompanyStatus.ACTIVE);
    }

    @Test
    void marksCompaniesMissingFromListAsDelisted() {
        runUntilDone();

        dart.remove("00000004");
        job.run();

        Company delisted = companyService.findByDartCorpCode("00000004").orElseThrow();
        assertThat(delisted.getStatus()).isEqualTo(CompanyStatus.DELISTED);
        assertThat(companyService.commonSecurityOf(delisted).orElseThrow().getDelistedOn()).isEqualTo("2026-09-25");
    }

    @Test
    void refusesMassDelistingFromSuspiciousList() {
        runUntilDone();

        dart.remove("00000002");
        dart.remove("00000003");
        dart.remove("00000004");

        assertThat(job.run().status()).isEqualTo(RunStatus.FAILED);
        assertThat(companyService.findByDartCorpCode("00000004").orElseThrow().getStatus())
                .isEqualTo(CompanyStatus.ACTIVE);
    }

    private void runUntilDone() {
        for (int i = 0; i < 5; i++) {
            if (job.run().status() == RunStatus.SUCCEEDED) {
                return;
            }
        }
        throw new AssertionError("동기화가 끝나지 않았습니다");
    }

    private List<String> runStatuses() {
        return jdbc.sql("select status from pipeline_run order by id").query(String.class).list();
    }

    private static DartCorpCode corp(String corpCode, String stockCode, String modifyDate) {
        return new DartCorpCode(corpCode, "이름", stockCode, modifyDate);
    }

    private static DartCompanyOverview overview(String corpCode, String stockName, String corpName, String stockCode,
            String corpCls) {
        return new DartCompanyOverview("000", "정상", corpCode, corpName, stockName, stockCode, corpCls, "264", "12");
    }

    static class FakeDartApi implements DartApi {

        final Map<String, DartCorpCode> corpCodes = new LinkedHashMap<>();
        final Map<String, DartCompanyOverview> overviews = new HashMap<>();
        final Map<String, DartStatus> failures = new HashMap<>();
        final List<String> companyCalls = new ArrayList<>();

        void reset() {
            corpCodes.clear();
            overviews.clear();
            failures.clear();
            companyCalls.clear();
        }

        void put(DartCorpCode corp, DartCompanyOverview overview) {
            corpCodes.put(corp.corpCode(), corp);
            overviews.put(corp.corpCode(), overview);
        }

        void putUnlisted(DartCorpCode corp) {
            corpCodes.put(corp.corpCode(), corp);
        }

        void remove(String corpCode) {
            corpCodes.remove(corpCode);
        }

        void failWith(String corpCode, DartStatus status) {
            failures.put(corpCode, status);
        }

        @Override
        public List<DartCorpCode> fetchCorpCodes() {
            return List.copyOf(corpCodes.values());
        }

        @Override
        public Optional<DartCompanyOverview> fetchCompany(String corpCode) {
            companyCalls.add(corpCode);
            DartStatus failure = failures.get(corpCode);
            if (failure != null) {
                throw new DartApiException(failure, "fake " + failure.code());
            }
            return Optional.ofNullable(overviews.get(corpCode));
        }

        @Override
        public DartDisclosurePage fetchDisclosures(LocalDate receivedOn, String disclosureType, int pageNo) {
            throw new UnsupportedOperationException("기업 목록 동기화는 공시 목록을 읽지 않는다");
        }

        @Override
        public List<DartKeyAccount> fetchKeyAccounts(List<String> corpCodes, int bsnsYear, String reportCode) {
            throw new UnsupportedOperationException("기업 목록 동기화는 재무 정보를 읽지 않는다");
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
