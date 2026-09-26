package org.stockinsight.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;

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

/** 재무 쉬운 설명 입력 구성을 검증한다(D-38, ai-analysis.md §4.4.2). */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class FinancialExplainInputBuilderTest {

    @Autowired
    FinancialExplainInputBuilder builder;

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
        jdbc.sql("truncate company_signal, financial_report, company_alias, security, company, ingest_checkpoint, pipeline_run restart identity cascade")
                .update();
        companyId = companyService.upsertListed(
                new ListedCompany("00126380", "삼성전자", "삼성전자(주)", "005930", Market.KOSPI, "264", 12)).getId();
    }

    @Test
    void companyWithoutReportsHasNoInput() {
        assertThat(builder.build(companyId)).isEmpty();
    }

    @Test
    void buildsFactsAndSectionsForGrowingCompany() {
        seedGeneralQuarter("11013", "2026.01.01 ~ 2026.03.31", "2026.03.31 현재", "2,000,000,000", "1,500,000,000", "R1");
        seedGeneralQuarter("11012", "2026.01.01 ~ 2026.06.30", "2026.06.30 현재", "4,000,000,000", "2,000,000,000", "R2");
        signalJob.run();

        FinancialExplainInputBuilder.BuildResult result = builder.build(companyId).orElseThrow();
        FinancialExplainInput input = result.input();

        assertThat(input.company().name()).isEqualTo("삼성전자");
        assertThat(input.company().format()).isEqualTo("GENERAL");
        assertThat(input.company().basis()).isEqualTo("CFS");
        assertThat(input.latest().kind()).isEqualTo("QUARTER");
        assertThat(input.sections()).contains("overview", "sales_profit", "structure");
        assertThat(input.changeStatus()).isEqualTo("CHANGED");

        assertThat(input.facts()).anySatisfy(f -> {
            assertThat(f.key()).isEqualTo("fin.revenue_yoy." + input.latest().period());
            assertThat(f.sign()).isEqualTo("+");
        });
        assertThat(input.signals()).anySatisfy(s -> assertThat(s.type()).isEqualTo("FIN_REVENUE_CHANGE"));

        // 원천 데이터를 노출하지 않는다(D-38): 공시번호가 AI 입력 어디에도 없어야 한다.
        assertThat(input.toString()).doesNotContain("R1").doesNotContain("R2");
        // 값 스냅샷(별도 저장물, AI에 보내지 않음)에는 출처 공시번호가 있어야 한다(렌더링·무효화 판단용).
        assertThat(result.snapshot().facts().toString()).contains("R2");
    }

    @Test
    void financialFormatExcludesRevenueRelatedTerms() {
        List<RawAccountLine> rows = List.of(
                line("BS", "자산총계", "1", "2026.03.31 현재", "9,000,000,000", "8,000,000,000"),
                line("BS", "부채총계", "9", "2026.03.31 현재", "4,000,000,000", "3,500,000,000"),
                line("BS", "자본총계", "13", "2026.03.31 현재", "5,000,000,000", "4,500,000,000"),
                line("IS", "영업이익", "27", "2026.01.01 ~ 2026.03.31", "500,000,000", "400,000,000"));
        financialService.replace(companyId, 2026, "11013", rows, null);
        signalJob.run();

        FinancialExplainInput input = builder.build(companyId).orElseThrow().input();

        assertThat(input.company().format()).isEqualTo("FINANCIAL");
        assertThat(input.doNotMention()).contains("부채비율", "영업이익률", "매출");
        assertThat(input.facts()).noneMatch(f -> f.key().startsWith("fin.debt_ratio"));
        assertThat(input.unavailable()).anySatisfy(u -> {
            assertThat(u.metric()).isEqualTo("debt_ratio");
            assertThat(u.reason()).isEqualTo("NOT_APPLICABLE_FORMAT");
        });
    }

    @Test
    void financialFormatStillIncludesRevenueUnderOperatingRevenueName() {
        // 금융형은 매출 증가율만 계산하지 않는다(§4.4.2). 원값은 "영업수익" 이름으로 사실표에 남아야 한다.
        // 참고: 실제 금융형 공시의 원천 계정명은 보통 "영업수익"이지만, AccountMapper는 현재 "매출액"만 인식한다
        // (financial 패키지의 기존 한계, 이번 작업 범위 밖). 여기서는 형식 판정(유동자산 유무)과 계정 인식이 서로
        // 독립적이라는 점을 이용해 AccountMapper가 인식하는 이름으로 seed하고, 이 클래스의 이름·가용성 로직만 검증한다.
        List<RawAccountLine> rows = List.of(
                line("BS", "부채총계", "9", "2026.03.31 현재", "4,000,000,000", "3,500,000,000"),
                line("BS", "자본총계", "13", "2026.03.31 현재", "5,000,000,000", "4,500,000,000"),
                line("IS", "매출액", "20", "2026.01.01 ~ 2026.03.31", "2,000,000,000", "1,800,000,000"),
                line("IS", "영업이익", "27", "2026.01.01 ~ 2026.03.31", "500,000,000", "400,000,000"));
        financialService.replace(companyId, 2026, "11013", rows, null);
        signalJob.run();

        FinancialExplainInput input = builder.build(companyId).orElseThrow().input();

        assertThat(input.company().format()).isEqualTo("FINANCIAL");
        assertThat(input.facts()).anySatisfy(f -> {
            assertThat(f.key()).startsWith("fin.revenue.");
            assertThat(f.name()).isEqualTo("영업수익");
        });
        assertThat(input.facts()).noneMatch(f -> f.key().startsWith("fin.revenue_yoy"));
        assertThat(input.unavailable()).anySatisfy(u -> {
            assertThat(u.metric()).isEqualTo("revenue_yoy");
            assertThat(u.reason()).isEqualTo("NOT_APPLICABLE_FORMAT");
        });
    }

    @Test
    void correctionDisclosureChangesFingerprint() {
        seedGeneralQuarter("11013", "2026.01.01 ~ 2026.03.31", "2026.03.31 현재", "2,000,000,000", "1,500,000,000", "R1");
        signalJob.run();
        String fingerprintBefore = builder.build(companyId).orElseThrow().fingerprint();

        // 정정공시: 같은 기간이 새 공시번호·값으로 갱신된다.
        seedGeneralQuarter("11013", "2026.01.01 ~ 2026.03.31", "2026.03.31 현재", "2,200,000,000", "1,500,000,000", "R1-CORRECTED");
        signalJob.run();
        String fingerprintAfter = builder.build(companyId).orElseThrow().fingerprint();

        assertThat(fingerprintAfter).isNotEqualTo(fingerprintBefore);
    }

    @Test
    void inconsistentBalanceSheetExcludesAllStructureFacts() {
        // 자산총계(90억) ≠ 부채총계(40억) + 자본총계(45억) → FIN_DATA_INCONSISTENT.
        List<RawAccountLine> rows = List.of(
                line("BS", "유동자산", "1", "2026.03.31 현재", "3,000,000,000", "2,500,000,000"),
                line("BS", "자산총계", "5", "2026.03.31 현재", "9,000,000,000", "8,000,000,000"),
                line("BS", "부채총계", "9", "2026.03.31 현재", "4,000,000,000", "3,500,000,000"),
                line("BS", "자본총계", "13", "2026.03.31 현재", "4,500,000,000", "4,500,000,000"),
                line("IS", "매출액", "23", "2026.01.01 ~ 2026.03.31", "2,000,000,000", "1,500,000,000"),
                line("IS", "영업이익", "27", "2026.01.01 ~ 2026.03.31", "500,000,000", "400,000,000"));
        financialService.replace(companyId, 2026, "11013", rows, null);
        signalJob.run();

        FinancialExplainInput input = builder.build(companyId).orElseThrow().input();

        assertThat(input.sections()).doesNotContain("structure");
        assertThat(input.facts()).noneMatch(f -> f.key().startsWith("fin.total_") || f.key().startsWith("fin.capital_stock")
                || f.key().startsWith("fin.debt_ratio"));
        assertThat(input.unavailable()).extracting(FinancialExplainInput.Unavailable::metric)
                .contains("total_assets", "total_liabilities", "total_equity", "capital_stock", "debt_ratio");
        assertThat(input.unavailable()).allSatisfy(u -> {
            if (u.metric().startsWith("total_") || u.metric().equals("capital_stock") || u.metric().equals("debt_ratio")) {
                assertThat(u.reason()).isEqualTo("INCONSISTENT_BALANCE");
            }
        });
    }

    @Test
    void nonKrwCompanyOmitsRevenueGrowthButKeepsBaseFreeRatios() {
        // 비원화는 매출 증가율(원화 기준값 필요)을 주지 않지만, 기준값이 필요 없는 영업이익률·부채비율은 그대로 준다(D-41).
        List<RawAccountLine> rows = List.of(
                line("BS", "유동자산", "1", "2026.03.31 현재", "3,000,000,000", "2,500,000,000"),
                line("BS", "자산총계", "5", "2026.03.31 현재", "9,000,000,000", "8,000,000,000"),
                line("BS", "부채총계", "9", "2026.03.31 현재", "4,000,000,000", "3,500,000,000"),
                line("BS", "자본총계", "13", "2026.03.31 현재", "5,000,000,000", "4,500,000,000"),
                line("IS", "매출액", "23", "2026.01.01 ~ 2026.03.31", "2,000,000,000", "1,500,000,000"),
                line("IS", "영업이익", "27", "2026.01.01 ~ 2026.03.31", "500,000,000", "400,000,000"));
        List<RawAccountLine> nonKrwRows = rows.stream()
                .map(r -> new RawAccountLine(r.fsDiv(), r.statement(), r.accountName(), r.ord(), r.currentPeriod(),
                        r.currentAmount(), r.currentCumulativeAmount(), r.priorAmount(), r.priorCumulativeAmount(),
                        r.prior2Amount(), "CNY", r.receiptNo()))
                .toList();
        financialService.replace(companyId, 2026, "11013", nonKrwRows, null);
        signalJob.run();

        FinancialExplainInput input = builder.build(companyId).orElseThrow().input();

        assertThat(input.company().currency()).isEqualTo("CNY");
        assertThat(input.facts()).anySatisfy(f -> {
            assertThat(f.key()).isEqualTo("fin.revenue." + input.latest().period());
            assertThat(f.display()).endsWith("위안");
        });
        assertThat(input.facts()).noneMatch(f -> f.key().startsWith("fin.revenue_yoy"));
        assertThat(input.unavailable()).anySatisfy(u -> {
            assertThat(u.metric()).isEqualTo("revenue_yoy");
            assertThat(u.reason()).isEqualTo("NON_KRW");
        });
        // 영업이익률은 매출 기준값이 필요 없는 비율이라 비원화도 그대로 준다.
        assertThat(input.facts()).anyMatch(f -> f.key().startsWith("fin.operating_margin."));
    }

    @Test
    void revenueYoyRunOmittedWhenLatestQuarterRevenueYoyUnavailable() {
        // Q1: 정상 증가(+9%), Q2(최신): 전년 동기가 기준값(10억) 미만이라 revenue_yoy 자체가 없다.
        // 부호만 보면 2분기 연속 증가라 흐름 조건(2개 이상)을 만족하지만, 최신 기간에 revenue_yoy를 줄 수 없으므로
        // revenue_yoy_run도 주지 않아야 한다(D-41).
        seedGeneralQuarter("11013", "2026.01.01 ~ 2026.03.31", "2026.03.31 현재", "1,200,000,000", "1,100,000,000", "R1");
        seedGeneralQuarter("11012", "2026.01.01 ~ 2026.06.30", "2026.06.30 현재", "600,000,000", "500,000,000", "R2");
        signalJob.run();

        FinancialExplainInput input = builder.build(companyId).orElseThrow().input();

        assertThat(input.facts()).noneMatch(f -> f.key().startsWith("fin.revenue_yoy_run"));
        assertThat(input.unavailable()).anySatisfy(u -> {
            assertThat(u.metric()).isEqualTo("revenue_yoy");
            assertThat(u.reason()).isEqualTo("SMALL_BASE");
        });
    }

    @Test
    void buildIsDeterministicForSameData() {
        seedGeneralQuarter("11013", "2026.01.01 ~ 2026.03.31", "2026.03.31 현재", "2,000,000,000", "1,500,000,000", "R1");
        signalJob.run();

        FinancialExplainInputBuilder.BuildResult first = builder.build(companyId).orElseThrow();
        FinancialExplainInputBuilder.BuildResult second = builder.build(companyId).orElseThrow();

        assertThat(first.input()).isEqualTo(second.input());
        assertThat(first.fingerprint()).isEqualTo(second.fingerprint());
    }

    @Test
    void nonDecemberFiscalYearUsesRangeLabel() {
        companyService.upsertListed(new ListedCompany("00241209", "모아텍", "모아텍(주)", "033200", Market.KOSDAQ, "264", 3));
        long moatechId = companyService.findByDartCorpCode("00241209").orElseThrow().getId();
        List<RawAccountLine> rows = List.of(
                line("BS", "유동자산", "1", "2026.03.31 현재", "1,000,000,000", "900,000,000"),
                line("IS", "매출액", "23", "2025.04.01 ~ 2026.03.31", "9,000,000,000", "8,000,000,000"));
        financialService.replace(moatechId, 2026, "11011", rows, null);

        FinancialExplainInput input = builder.build(moatechId).orElseThrow().input();

        assertThat(input.periods()).anySatisfy(p -> assertThat(p.label()).contains("회계연도"));
    }

    private void seedGeneralQuarter(String reportCode, String period, String balancePeriod, String current, String prior,
            String receiptNo) {
        List<RawAccountLine> rows = List.of(
                line("BS", "유동자산", "1", balancePeriod, "3,000,000,000", "2,500,000,000", receiptNo),
                line("BS", "자산총계", "5", balancePeriod, "9,000,000,000", "8,000,000,000", receiptNo),
                line("BS", "부채총계", "9", balancePeriod, "4,000,000,000", "3,500,000,000", receiptNo),
                line("BS", "자본총계", "13", balancePeriod, "5,000,000,000", "4,500,000,000", receiptNo),
                line("IS", "매출액", "23", period, current, prior, receiptNo),
                line("IS", "영업이익", "27", period, "500,000,000", "400,000,000", receiptNo));
        int bsnsYear = Integer.parseInt(period.substring(period.length() - 10, period.length() - 6));
        financialService.replace(companyId, bsnsYear, reportCode, rows, null);
    }

    private static RawAccountLine line(String statement, String accountName, String ord, String period,
            String current, String prior, String receiptNo) {
        return new RawAccountLine("CFS", statement, accountName, ord, period, current, null, prior, null, null,
                "KRW", receiptNo);
    }

    private static RawAccountLine line(String statement, String accountName, String ord, String period,
            String current, String prior) {
        return line(statement, accountName, ord, period, current, prior, "R1");
    }
}
