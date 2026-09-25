package org.stockinsight.financial;

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
import org.stockinsight.company.CompanyService;
import org.stockinsight.company.CompanyService.ListedCompany;
import org.stockinsight.company.Market;

/** 재무 요약 구성을 검증한다(D-37, ai-analysis.md §3.6). */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class FinancialSummaryServiceTest {

    @Autowired
    FinancialService financialService;

    @Autowired
    FinancialSummaryService summaryService;

    @Autowired
    CompanyService companyService;

    @Autowired
    JdbcClient jdbc;

    long companyId;

    @BeforeEach
    void setUp() {
        jdbc.sql("truncate financial_report, company_alias, security, company restart identity cascade").update();
        companyId = companyService.upsertListed(
                new ListedCompany("00126380", "삼성전자", "삼성전자(주)", "005930", Market.KOSPI, "264", 12)).getId();
    }

    @Test
    void fixesToConsolidatedBasisWhenLatestPeriodHasBoth() {
        replaceBoth(2026, "11012", "2026.01.01 ~ 2026.06.30", "2026.06.30 현재", "R-H1");

        FinancialSummary summary = summaryService.summarize(companyId);

        assertThat(summary.hasAnyReport()).isTrue();
        assertThat(summary.basis()).isEqualTo("CFS");
        assertThat(summary.currency()).isEqualTo("KRW");
        assertThat(summary.latestPeriodEnd()).isEqualTo(LocalDate.of(2026, 6, 30));
    }

    @Test
    void derivesFourthQuarterFromAnnualMinusThirdQuarterCumulative() {
        replaceQuarter("CFS", 2025, "11014", "Q3", "2025.01.01 ~ 2025.09.30", "2025.09.30 현재",
                "3,000,000,000", "9,000,000,000", "R-Q3");
        replaceQuarter("CFS", 2025, "11011", "FY", "2025.01.01 ~ 2025.12.31", "2025.12.31 현재",
                "12,000,000,000", null, "R-FY");

        FinancialSummary summary = summaryService.summarize(companyId);

        QuarterEntry q4 = summary.quarters().stream().filter(q -> q.key().quarterNumber() == 4).findFirst().orElseThrow();
        assertThat(q4.derived()).isTrue();
        assertThat(q4.derivedValid()).isTrue();
        assertThat(q4.revenue().current()).isEqualByComparingTo("3000000000"); // 120억 - 90억
    }

    @Test
    void negativeDerivedRevenueIsInvalid() {
        replaceQuarter("CFS", 2025, "11014", "Q3", "2025.01.01 ~ 2025.09.30", "2025.09.30 현재",
                "3,000,000,000", "10,000,000,000", "R-Q3");
        replaceQuarter("CFS", 2025, "11011", "FY", "2025.01.01 ~ 2025.12.31", "2025.12.31 현재",
                "9,000,000,000", null, "R-FY"); // 90억 - 100억 = 음수

        FinancialSummary summary = summaryService.summarize(companyId);

        QuarterEntry q4 = summary.quarters().stream().filter(q -> q.key().quarterNumber() == 4).findFirst().orElseThrow();
        assertThat(q4.derived()).isTrue();
        assertThat(q4.derivedValid()).isFalse();
        assertThat(q4.revenue().hasCurrent()).isFalse();
        assertThat(summary.flags()).anyMatch(f -> f.code().equals(SummaryFlag.DERIVED_INVALID));
    }

    @Test
    void nonKrwFlagIsSet() {
        replaceQuarter("CFS", 2026, "11013", "Q1", "2026.01.01 ~ 2026.03.31", "2026.03.31 현재",
                "1,000", "900", "R-Q1", "USD");

        FinancialSummary summary = summaryService.summarize(companyId);

        assertThat(summary.currency()).isEqualTo("USD");
        assertThat(summary.flags()).anyMatch(f -> f.code().equals(SummaryFlag.NON_KRW));
    }

    @Test
    void inconsistentBalanceSheetIsFlagged() {
        List<RawAccountLine> rows = List.of(
                line("CFS", "BS", "자산총계", "5", "2026.03.31 현재", "1,000", "900"),
                line("CFS", "BS", "부채총계", "9", "2026.03.31 현재", "2,000", "500"), // 자산 != 부채+자본
                line("CFS", "BS", "자본총계", "13", "2026.03.31 현재", "500", "400"),
                line("CFS", "IS", "매출액", "23", "2026.01.01 ~ 2026.03.31", "1,000", "900"));
        financialService.replace(companyId, 2026, "11013", rows, null);

        FinancialSummary summary = summaryService.summarize(companyId);

        assertThat(summary.quarters().get(0).balanceConsistent()).isFalse();
        assertThat(summary.flags()).anyMatch(f -> f.code().equals(SummaryFlag.INCONSISTENT_BALANCE));
    }

    @Test
    void financialFormatIsDetectedByMissingCurrentAssets() {
        List<RawAccountLine> rows = List.of(
                line("CFS", "BS", "자산총계", "5", "2026.03.31 현재", "1,000", "900"),
                line("CFS", "IS", "매출액", "23", "2026.01.01 ~ 2026.03.31", "1,000", "900"));
        financialService.replace(companyId, 2026, "11013", rows, null);

        FinancialSummary summary = summaryService.summarize(companyId);

        assertThat(summary.quarters().get(0).format()).isEqualTo(FinancialFormat.FINANCIAL);
        assertThat(summary.flags()).anyMatch(f -> f.code().equals(SummaryFlag.NOT_APPLICABLE_FORMAT));
    }

    @Test
    void companyWithNoReportsHasEmptySummary() {
        long emptyCompanyId = companyService.upsertListed(
                new ListedCompany("00999999", "빈회사", "빈회사(주)", "999999", Market.KOSPI, "264", 12)).getId();

        FinancialSummary summary = summaryService.summarize(emptyCompanyId);

        assertThat(summary.hasAnyReport()).isFalse();
        assertThat(summary.quarters()).isEmpty();
    }

    private void replaceBoth(int bsnsYear, String reportCode, String period, String balancePeriod, String receiptNo) {
        List<RawAccountLine> rows = List.of(
                line("CFS", "IS", "매출액", "23", period, "1,000", "900"),
                line("CFS", "BS", "자산총계", "5", balancePeriod, "5,000", "4,500"),
                line("OFS", "IS", "매출액", "23", period, "800", "700"),
                line("OFS", "BS", "자산총계", "5", balancePeriod, "4,000", "3,600"));
        financialService.replace(companyId, bsnsYear, reportCode, rows.stream()
                .map(r -> new RawAccountLine(r.fsDiv(), r.statement(), r.accountName(), r.ord(), r.currentPeriod(),
                        r.currentAmount(), r.currentCumulativeAmount(), r.priorAmount(), r.priorCumulativeAmount(),
                        r.prior2Amount(), r.currency(), receiptNo))
                .toList(), null);
    }

    private void replaceQuarter(String fsDiv, int bsnsYear, String reportCode, String reportTypeLabel, String period,
            String balancePeriod, String currentAmount, String cumulativeAmount, String receiptNo) {
        replaceQuarter(fsDiv, bsnsYear, reportCode, reportTypeLabel, period, balancePeriod, currentAmount, cumulativeAmount,
                receiptNo, "KRW");
    }

    private void replaceQuarter(String fsDiv, int bsnsYear, String reportCode, String reportTypeLabel, String period,
            String balancePeriod, String currentAmount, String cumulativeAmount, String receiptNo, String currency) {
        List<RawAccountLine> rows = List.of(
                new RawAccountLine(fsDiv, "IS", "매출액", "23", period, currentAmount, cumulativeAmount, "1", cumulativeAmount == null ? null : "1",
                        null, currency, receiptNo),
                new RawAccountLine(fsDiv, "BS", "자산총계", "5", balancePeriod, "9,000,000,000", null, "8,000,000,000", null,
                        null, currency, receiptNo),
                new RawAccountLine(fsDiv, "BS", "부채총계", "9", balancePeriod, "4,000,000,000", null, "3,500,000,000", null,
                        null, currency, receiptNo),
                new RawAccountLine(fsDiv, "BS", "자본총계", "13", balancePeriod, "5,000,000,000", null, "4,500,000,000", null,
                        null, currency, receiptNo));
        financialService.replace(companyId, bsnsYear, reportCode, rows, null);
    }

    private static RawAccountLine line(String fsDiv, String statement, String accountName, String ord, String period,
            String currentAmount, String priorAmount) {
        return new RawAccountLine(fsDiv, statement, accountName, ord, period, currentAmount, null, priorAmount, null,
                null, "KRW", "R1");
    }
}
