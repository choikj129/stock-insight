package org.stockinsight.signal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
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
import org.stockinsight.company.CompanyService;
import org.stockinsight.company.CompanyService.ListedCompany;
import org.stockinsight.company.Market;
import org.stockinsight.financial.FinancialService;
import org.stockinsight.financial.RawAccountLine;

/**
 * "최신 재무 미확인"(FIN_DATA_STALE)의 시간 기반 재판정을 검증한다(D-43). 최신 기간 = 2026년 1분기(종료
 * 2026-03-31)일 때, 다음 기간 종료월 말일(6/30) + 기한 60일(분기) = 8/29, 유예 +7일이므로 9/6부터 미확인이다.
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, FinancialSignalStaleJobTest.FixedClockConfig.class})
class FinancialSignalStaleJobTest {

    @Autowired
    FinancialSignalJob job;

    @Autowired
    FinancialService financialService;

    @Autowired
    CompanySignalService signalService;

    @Autowired
    CompanyService companyService;

    @Autowired
    MutableClock clock;

    @Autowired
    JdbcClient jdbc;

    long companyId;

    @BeforeEach
    void setUp() {
        // 클록은 테스트 간 공유되는 싱글턴이다. 이전 테스트가 남긴 값과 무관하게 항상 같은 상태로 시작한다.
        clock.setDate(LocalDate.of(2026, 9, 5));
        jdbc.sql("truncate company_signal, financial_report, company_alias, security, company, ingest_checkpoint, pipeline_run restart identity cascade")
                .update();
        companyId = companyService.upsertListed(
                new ListedCompany("00126380", "삼성전자", "삼성전자(주)", "005930", Market.KOSPI, "264", 12)).getId();
        seedQ1();
    }

    @Test
    void oneDayBeforeGraceEndsIsNotStaleAndTheDayAfterIs() {
        clock.setDate(LocalDate.of(2026, 9, 5));
        job.run();
        assertThat(staleSignal()).isEmpty();

        clock.setDate(LocalDate.of(2026, 9, 6));
        job.run();
        assertThat(staleSignal()).map(CompanySignal::status).contains(SignalStatus.ACTIVE);
    }

    @Test
    void onceStaleRerunningTheSameDayRecomputesNothing() {
        clock.setDate(LocalDate.of(2026, 9, 6));
        job.run();
        assertThat(staleSignal()).map(CompanySignal::status).contains(SignalStatus.ACTIVE);

        FinancialSignalJob.Result second = job.run();

        assertThat(second.summary()).contains("대상 0");
    }

    @Test
    void recheckDateAloneTriggersReassessmentWithoutFinancialChange() {
        clock.setDate(LocalDate.of(2026, 9, 5));
        FinancialSignalJob.Result before = job.run();
        assertThat(before.summary()).doesNotContain("대상 0");
        assertThat(staleSignal()).isEmpty();

        // 같은 날 다시 실행: 재무도, 재판정일도 그대로라 대상이 아니다.
        FinancialSignalJob.Result sameDay = job.run();
        assertThat(sameDay.summary()).contains("대상 0");

        // 재판정일(9/6) 도래: 재무 변경 없이도 다시 판정되어 미확인이 된다.
        clock.setDate(LocalDate.of(2026, 9, 6));
        job.run();
        assertThat(staleSignal()).map(CompanySignal::status).contains(SignalStatus.ACTIVE);
    }

    @Test
    void newerQuarterResolvesStaleAsPastNotWithdrawn() {
        clock.setDate(LocalDate.of(2026, 9, 6));
        job.run();
        assertThat(staleSignal()).map(CompanySignal::status).contains(SignalStatus.ACTIVE);

        // 2분기(반기 보고서)가 들어온다. 아직 그 자체는 미확인 기한 전이다.
        seedQuarter("11012", "2026.01.01 ~ 2026.06.30", "2026.06.30 현재", "2,100,000,000", "2,000,000,000", "R2");
        job.run();

        // 해소는 철회가 아니다(D-43): STALE 행은 PAST이지 WITHDRAWN이 아니다.
        assertThat(staleSignal()).map(CompanySignal::status).contains(SignalStatus.PAST);
        assertThat(signalService.findByCompany(companyId))
                .filteredOn(s -> s.signalType() == SignalType.FIN_DATA_STALE)
                .noneMatch(s -> s.status() == SignalStatus.WITHDRAWN);
    }

    private Optional<CompanySignal> staleSignal() {
        return signalService.findByCompany(companyId).stream()
                .filter(s -> s.signalType() == SignalType.FIN_DATA_STALE)
                .findFirst();
    }

    private void seedQ1() {
        seedQuarter("11013", "2026.01.01 ~ 2026.03.31", "2026.03.31 현재", "4,000,000,000", "2,000,000,000", "R1");
    }

    private void seedQuarter(String reportCode, String period, String balancePeriod, String current, String prior,
            String receiptNo) {
        List<RawAccountLine> rows = List.of(
                new RawAccountLine("CFS", "IS", "매출액", "23", period, current, null, prior, null, null, "KRW", receiptNo),
                new RawAccountLine("CFS", "BS", "유동자산", "1", balancePeriod, "3,000,000,000", null, "2,500,000,000", null,
                        null, "KRW", receiptNo),
                new RawAccountLine("CFS", "BS", "자산총계", "5", balancePeriod, "9,000,000,000", null, "8,000,000,000", null,
                        null, "KRW", receiptNo),
                new RawAccountLine("CFS", "BS", "부채총계", "9", balancePeriod, "4,000,000,000", null, "3,500,000,000", null,
                        null, "KRW", receiptNo),
                new RawAccountLine("CFS", "BS", "자본총계", "13", balancePeriod, "5,000,000,000", null, "4,500,000,000", null,
                        null, "KRW", receiptNo));
        String endDate = period.substring(period.length() - 10).replace(".", "-");
        int bsnsYear = LocalDate.parse(endDate).getYear();
        financialService.replace(companyId, bsnsYear, reportCode, rows, null);
    }

    static class MutableClock extends Clock {

        private Instant instant = Instant.EPOCH;

        void setDate(LocalDate date) {
            this.instant = date.atTime(3, 0).atZone(ZoneId.of("UTC")).toInstant();
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
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
    static class FixedClockConfig {

        @Bean
        @Primary
        MutableClock mutableClock() {
            MutableClock clock = new MutableClock();
            clock.setDate(LocalDate.of(2026, 9, 5));
            return clock;
        }
    }
}
