package org.stockinsight.financial;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
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
import org.stockinsight.financial.FinancialService.ReplaceResult;

/**
 * 재무 저장 규칙을 검증한다 (docs/implementation-plan.md §4.2, §4.4, D-32, D-33).
 * 계정 값은 실제 응답의 형태(쉼표, 음수, "-", 기간 문자열)를 따른다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class FinancialServiceTest {

    @Autowired
    FinancialService financialService;

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
    void storesConsolidatedAndSeparateFromOneResponse() {
        List<RawAccountLine> rows = List.of(
                line("CFS", "BS", "자산총계", "5", "2025.06.30 현재", "212,160,671,000,000", null,
                        "227,062,266,000,000", null, null, "20250814003156"),
                line("CFS", "IS", "매출액", "23", "2025.01.01 ~ 2025.06.30", "74,566,317,000,000", "153,706,820,000,000",
                        "74,068,302,000,000", "145,983,903,000,000", null, "20250814003156"),
                line("OFS", "IS", "매출액", "23", "2025.01.01 ~ 2025.06.30", "70,000,000,000", "140,000,000,000",
                        "69,000,000,000", "139,000,000,000", null, "20250814003156"));

        ReplaceResult result = financialService.replace(companyId, 2025, "11012", rows, null);

        assertThat(result).isEqualTo(new ReplaceResult(2, 0, 0, 0));
        StoredFinancialReport cfs = financialService.find(companyId, 2025, "11012", "CFS").orElseThrow();
        assertThat(cfs.periodType()).isEqualTo(PeriodType.H1);
        assertThat(cfs.fiscalYearStart()).isEqualTo(LocalDate.of(2025, 1, 1));
        assertThat(cfs.periodEnd()).isEqualTo(LocalDate.of(2025, 6, 30));
        assertThat(cfs.currency()).isEqualTo("KRW");
        assertThat(cfs.receiptNo()).isEqualTo("20250814003156");
        assertThat(cfs.lines()).hasSize(2);

        StoredFinancialLine bsLine = cfs.lines().stream().filter(l -> l.ord() == 5).findFirst().orElseThrow();
        assertThat(bsLine.currentAmount()).isEqualByComparingTo("212160671000000");
        assertThat(bsLine.priorAmount()).isEqualByComparingTo("227062266000000");
        // 재무상태표는 당기누적이 없다.
        assertThat(bsLine.currentCumulativeAmount()).isNull();

        StoredFinancialLine isLine = cfs.lines().stream().filter(l -> l.ord() == 23).findFirst().orElseThrow();
        assertThat(isLine.currentCumulativeAmount()).isEqualByComparingTo("153706820000000");

        assertThat(financialService.find(companyId, 2025, "11012", "OFS")).isPresent();
    }

    @Test
    void annualReportKeepsThirdPriorPeriodAndDashBecomesNull() {
        List<RawAccountLine> rows = List.of(
                line("CFS", "IS", "매출액", "23", "2025.01.01 ~ 2025.12.31", "333,605,938,000,000", null,
                        "300,870,903,000,000", null, "258,935,494,000,000", "20260310002820"),
                line("CFS", "BS", "기타포괄손익-공정가치측정금융자산", "38", "2025.12.31 현재", "999,996,492", null, "-", null, null,
                        "20260310002820"));

        financialService.replace(companyId, 2025, "11011", rows, null);

        StoredFinancialReport report = financialService.find(companyId, 2025, "11011", "CFS").orElseThrow();
        assertThat(report.periodType()).isEqualTo(PeriodType.FY);
        StoredFinancialLine revenue = report.lines().stream().filter(l -> l.ord() == 23).findFirst().orElseThrow();
        assertThat(revenue.prior2Amount()).isEqualByComparingTo("258935494000000");
        StoredFinancialLine dash = report.lines().stream().filter(l -> l.ord() == 38).findFirst().orElseThrow();
        assertThat(dash.priorAmount()).isNull();
    }

    @Test
    void rereadWithSameValuesIsUnchanged() {
        List<RawAccountLine> rows = List.of(
                line("CFS", "IS", "매출액", "23", "2025.01.01 ~ 2025.06.30", "1,000", "2,000", "900", "1,900", null, "R1"));
        financialService.replace(companyId, 2025, "11012", rows, null);

        ReplaceResult second = financialService.replace(companyId, 2025, "11012", rows, null);

        assertThat(second).isEqualTo(new ReplaceResult(0, 0, 1, 0));
    }

    @Test
    void amendmentWithNewValuesIsUpdated() {
        List<RawAccountLine> original = List.of(
                line("CFS", "IS", "매출액", "23", "2025.01.01 ~ 2025.06.30", "1,000", "2,000", "900", "1,900", null, "R1"));
        financialService.replace(companyId, 2025, "11012", original, null);

        List<RawAccountLine> amended = List.of(
                line("CFS", "IS", "매출액", "23", "2025.01.01 ~ 2025.06.30", "1,500", "2,500", "900", "1,900", null, "R2"));
        ReplaceResult result = financialService.replace(companyId, 2025, "11012", amended, null);

        assertThat(result).isEqualTo(new ReplaceResult(0, 1, 0, 0));
        StoredFinancialReport report = financialService.find(companyId, 2025, "11012", "CFS").orElseThrow();
        assertThat(report.receiptNo()).isEqualTo("R2");
        assertThat(report.lines()).first().satisfies(l -> assertThat(l.currentAmount()).isEqualByComparingTo("1500"));
    }

    @Test
    void fsDivMissingFromNewResponseIsRemoved() {
        List<RawAccountLine> both = List.of(
                line("CFS", "IS", "매출액", "23", "2025.01.01 ~ 2025.06.30", "1,000", "2,000", "900", "1,900", null, "R1"),
                line("OFS", "IS", "매출액", "23", "2025.01.01 ~ 2025.06.30", "500", "900", "400", "800", null, "R1"));
        financialService.replace(companyId, 2025, "11012", both, null);

        List<RawAccountLine> onlyCfs = List.of(
                line("CFS", "IS", "매출액", "23", "2025.01.01 ~ 2025.06.30", "1,000", "2,000", "900", "1,900", null, "R1"));
        ReplaceResult result = financialService.replace(companyId, 2025, "11012", onlyCfs, null);

        assertThat(result.removed()).isEqualTo(1);
        assertThat(financialService.find(companyId, 2025, "11012", "OFS")).isEmpty();
        assertThat(financialService.find(companyId, 2025, "11012", "CFS")).isPresent();
    }

    @Test
    void missingIncomeStatementRowFailsPeriodIdentification() {
        List<RawAccountLine> onlyBalanceSheet = List.of(
                line("CFS", "BS", "자산총계", "5", "2025.06.30 현재", "1,000", null, "900", null, null, "R1"));

        assertThatThrownBy(() -> financialService.replace(companyId, 2025, "11012", onlyBalanceSheet, null))
                .isInstanceOf(FinancialPeriodException.class);
    }

    @Test
    void periodEndNotMatchingTriggerMonthFailsWithoutSaving() {
        List<RawAccountLine> rows = List.of(
                line("CFS", "IS", "매출액", "23", "2025.01.01 ~ 2025.06.30", "1,000", "2,000", "900", "1,900", null, "R1"));

        assertThatThrownBy(() -> financialService.replace(companyId, 2025, "11012", rows, YearMonth.of(2025, 9)))
                .isInstanceOf(FinancialPeriodException.class);
        assertThat(financialService.find(companyId, 2025, "11012", "CFS")).isEmpty();
    }

    @Test
    void periodEndMatchingTriggerMonthSucceeds() {
        List<RawAccountLine> rows = List.of(
                line("CFS", "IS", "매출액", "23", "2025.01.01 ~ 2025.06.30", "1,000", "2,000", "900", "1,900", null, "R1"));

        financialService.replace(companyId, 2025, "11012", rows, YearMonth.of(2025, 6));

        assertThat(financialService.find(companyId, 2025, "11012", "CFS")).isPresent();
    }

    @Test
    void negativeAmountsAreParsed() {
        List<RawAccountLine> rows = List.of(
                line("CFS", "IS", "영업손실", "25", "2025.01.01 ~ 2025.06.30", "-1,234,000", null, "-999", null, null, "R1"));

        financialService.replace(companyId, 2025, "11012", rows, null);

        BigDecimal amount = financialService.find(companyId, 2025, "11012", "CFS").orElseThrow()
                .lines().get(0).currentAmount();
        assertThat(amount).isEqualByComparingTo("-1234000");
    }

    private static RawAccountLine line(String fsDiv, String statement, String accountName, String ord,
            String currentPeriod, String currentAmount, String currentCumulativeAmount, String priorAmount,
            String priorCumulativeAmount, String prior2Amount, String receiptNo) {
        return new RawAccountLine(fsDiv, statement, accountName, ord, currentPeriod, currentAmount,
                currentCumulativeAmount, priorAmount, priorCumulativeAmount, prior2Amount, "KRW", receiptNo);
    }
}
