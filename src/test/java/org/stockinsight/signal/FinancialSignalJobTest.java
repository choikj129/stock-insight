package org.stockinsight.signal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.stockinsight.TestcontainersConfiguration;
import org.stockinsight.common.pipeline.PipelineRunRecorder.RunStatus;
import org.stockinsight.company.CompanyService;
import org.stockinsight.company.CompanyService.ListedCompany;
import org.stockinsight.company.Market;
import org.stockinsight.financial.FinancialService;
import org.stockinsight.financial.RawAccountLine;

/**
 * 재무 신호 계산 작업을 검증한다(D-36, architecture.md §4.4). 재무 데이터는 FinancialService.replace로 직접 seed해
 * OpenDART 없이 신호 계층만 검증한다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class FinancialSignalJobTest {

    @Autowired
    FinancialSignalJob job;

    @Autowired
    FinancialService financialService;

    @Autowired
    CompanySignalService signalService;

    @Autowired
    CompanyService companyService;

    @Autowired
    JdbcClient jdbc;

    long companyId;

    @BeforeEach
    void setUp() {
        jdbc.sql("truncate company_signal, financial_report, company_alias, security, company, ingest_checkpoint, pipeline_run restart identity cascade")
                .update();
        companyId = companyService.upsertListed(
                new ListedCompany("00126380", "삼성전자", "삼성전자(주)", "005930", Market.KOSPI, "264", 12)).getId();
    }

    @Test
    void computesAndStoresRevenueChangeSignal() {
        seedQuarter("11013", "2026.01.01 ~ 2026.03.31", "2026.03.31 현재", "4,000,000,000", "2,000,000,000", "R1");

        FinancialSignalJob.Result result = job.run();

        assertThat(result.status()).isEqualTo(RunStatus.SUCCEEDED);
        List<CompanySignal> signals = signalService.findByCompany(companyId);
        assertThat(signals).anySatisfy(s -> {
            assertThat(s.signalType()).isEqualTo(SignalType.FIN_REVENUE_CHANGE);
            assertThat(s.status()).isEqualTo(SignalStatus.ACTIVE);
            assertThat(s.direction()).isEqualTo(SignalDirection.POSITIVE);
        });
    }

    @Test
    void rerunWithoutFinancialChangesRecomputesNothing() {
        seedQuarter("11013", "2026.01.01 ~ 2026.03.31", "2026.03.31 현재", "4,000,000,000", "2,000,000,000", "R1");
        job.run();

        FinancialSignalJob.Result second = job.run();

        assertThat(second.summary()).contains("대상 0");
    }

    @Test
    void correctionThatRemovesConditionWithdrawsSignal() {
        seedQuarter("11013", "2026.01.01 ~ 2026.03.31", "2026.03.31 현재", "4,000,000,000", "2,000,000,000", "R1");
        job.run();
        assertThat(signalService.findByCompany(companyId))
                .anyMatch(s -> s.signalType() == SignalType.FIN_REVENUE_CHANGE && s.status() == SignalStatus.ACTIVE);

        // 정정: 증가율이 30% 밑으로 (전기 20억 -> 당기 22억, +10%)
        seedQuarter("11013", "2026.01.01 ~ 2026.03.31", "2026.03.31 현재", "2,200,000,000", "2,000,000,000", "R2");
        job.run();

        assertThat(signalService.findByCompany(companyId))
                .filteredOn(s -> s.signalType() == SignalType.FIN_REVENUE_CHANGE)
                .allMatch(s -> s.status() == SignalStatus.WITHDRAWN);
    }

    @Test
    void newerQuarterMovesPreviousActiveSignalToPast() {
        seedQuarter("11013", "2026.01.01 ~ 2026.03.31", "2026.03.31 현재", "4,000,000,000", "2,000,000,000", "R1");
        job.run();
        String q1Key = signalService.findByCompany(companyId).stream()
                .filter(s -> s.signalType() == SignalType.FIN_REVENUE_CHANGE)
                .findFirst().orElseThrow().basisKey();

        // 다음 분기(반기 보고서)가 들어온다. 이번에는 조건을 만들지 않는다.
        seedQuarter("11012", "2026.01.01 ~ 2026.06.30", "2026.06.30 현재", "2,100,000,000", "2,000,000,000", "R2");
        job.run();

        CompanySignal q1Signal = signalService.findByCompany(companyId).stream()
                .filter(s -> s.signalType() == SignalType.FIN_REVENUE_CHANGE && s.basisKey().equals(q1Key))
                .findFirst().orElseThrow();
        assertThat(q1Signal.status()).isEqualTo(SignalStatus.PAST);
    }

    @Test
    void refusesToRunWithoutAnyFinancialData() {
        assertThat(job.run().status()).isEqualTo(RunStatus.FAILED);
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
        // period는 "YYYY.MM.DD ~ YYYY.MM.DD" 형식이라 종료일 연도를 bsns_year로 쓴다.
        String endDate = period.substring(period.length() - 10).replace(".", "-");
        int bsnsYear = LocalDate.parse(endDate).getYear();
        financialService.replace(companyId, bsnsYear, reportCode, rows, null);
    }
}
