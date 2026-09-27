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
import org.stockinsight.signal.FinancialRuleCatalog;
import org.stockinsight.signal.FinancialSignalJob;

/** 재무 쉬운 설명 입력 구성을 검증한다(D-38, docs/spec/financial-explain.md §4.4.2). */
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
        // 변화 신호의 지속(D-61)은 AI 입력에 넣지 않는다 — 이어진 기간 수는 흐름 사실 토큰으로만 준다(fx-input-6 유지).
        assertThat(input.signals()).filteredOn(s -> s.type().equals("FIN_REVENUE_CHANGE"))
                .allSatisfy(s -> assertThat(s.persistence()).isNull());

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
    void generalFormatWithMissingRevenueAccountAddsRevenueToDoNotMention() {
        // 2715(셀레스트라) 실제 사례 재현: GENERAL 포맷인데 매출 계정 자체가 없다. doNotMention은 이전에
        // FINANCIAL 포맷일 때만 "매출"을 자동으로 넣어, 같은 "매출 언급 금지" 상황인데 GENERAL 포맷은
        // unavailable에만 들어가고 doNotMention에는 빠지는 비대칭이 있었다(docs/work/3-4-verification-1.md §7.4.9, D-51).
        List<RawAccountLine> rows = List.of(
                line("BS", "유동자산", "1", "2026.03.31 현재", "3,000,000,000", "2,500,000,000"),
                line("BS", "자산총계", "5", "2026.03.31 현재", "9,000,000,000", "8,000,000,000"),
                line("BS", "부채총계", "9", "2026.03.31 현재", "4,000,000,000", "3,500,000,000"),
                line("BS", "자본총계", "13", "2026.03.31 현재", "5,000,000,000", "4,500,000,000"),
                line("IS", "영업이익", "27", "2026.01.01 ~ 2026.03.31", "500,000,000", "400,000,000"));
        financialService.replace(companyId, 2026, "11013", rows, null);
        signalJob.run();

        FinancialExplainInput input = builder.build(companyId).orElseThrow().input();

        assertThat(input.company().format()).isEqualTo("GENERAL");
        assertThat(input.unavailable()).anySatisfy(u -> {
            assertThat(u.metric()).isEqualTo("revenue");
            assertThat(u.reason()).isEqualTo("ACCOUNT_MISSING");
        });
        assertThat(input.doNotMention()).contains("매출");
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

        FinancialExplainInputBuilder.BuildResult result = builder.build(companyId).orElseThrow();
        FinancialExplainInput input = result.input();

        assertThat(input.company().currency()).isEqualTo("CNY");
        String revenueKey = "fin.revenue." + input.latest().period();
        assertThat(input.facts()).anySatisfy(f -> assertThat(f.key()).isEqualTo(revenueKey));
        // 표시 값은 AI 입력에 없다(D-59) — 값 스냅샷(렌더링용)에서 통화명 표시를 확인한다.
        assertThat(result.snapshot().facts().get(revenueKey).display()).endsWith("위안");
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
    void pastSignalPeriodIsLabeledEvenWithoutOwnFacts() {
        // 2906(KT나스미디어) 실제 사례 재현: 최신 기간도 사업연도 대표 기간도 아닌 과거 분기의 이력 신호는 그
        // 기간에 등록된 사실이 없다. 신호를 만들 때 그 기간에 라벨을 붙이지 않으면 프롬프트가 시키는 "지난
        // {per.기간}에는 {sig.S}…" 패턴을 쓸 토큰 자체가 없어져 규칙 2 실패로 이어진다(docs/work/3-4-verification-1.md §7.4.7).
        // 실제 계산기 문턱값에 기대지 않고 company_signal에 직접 넣어 "사실 없는 과거 분기 신호" 상황만 재현한다.
        seedGeneralQuarter("11013", "2026.01.01 ~ 2026.03.31", "2026.03.31 현재", "1,000,000,000", "1,000,000,000", "R1");
        signalJob.run();

        jdbc.sql("""
                insert into company_signal (company_id, signal_type, basis_key, nature, direction, severity,
                    occurred_on, calc_values, watch_metrics, status, rule_version, first_detected_at,
                    last_evaluated_at, status_changed_at)
                values (:companyId, 'FIN_OPERATING_MARGIN_CHANGE', '2025-01-01:PAST_TEST', 'CHANGE', 'NEGATIVE', 'LOW',
                    '2025-06-30', '{}', '[]', 'PAST', :ruleVersion, now(), now(), now())
                """)
                .param("companyId", companyId)
                .param("ruleVersion", FinancialRuleCatalog.RULE_VERSION)
                .update();

        FinancialExplainInput input = builder.build(companyId).orElseThrow().input();

        assertThat(input.sections()).contains("history");
        assertThat(input.signals()).anySatisfy(s -> {
            assertThat(s.status()).isEqualTo("PAST");
            assertThat(s.period()).isEqualTo("2025-01.Q2");
            assertThat(s.factKeys()).isEmpty();
        });
        assertThat(input.periods()).anySatisfy(p -> assertThat(p.key()).isEqualTo("2025-01.Q2"));
    }

    // ---- D-54: 신호·사실의 섹션 배정 ----

    @Test
    void structureFactsAreDebtRatioOnlyWithoutImpairment() {
        // 일반형·자본잠식 없음: structure는 부채비율 + 전기말 대비 변화량뿐이다. 전기말 부채비율·자산·부채 총계·
        // 자본총계·자본금·잠식률은 AI 입력에도 값 스냅샷에도 없다.
        seedGeneralQuarter("11013", "2026.01.01 ~ 2026.03.31", "2026.03.31 현재", "1,000,000,000", "1,000,000,000", "R1");
        signalJob.run();

        FinancialExplainInputBuilder.BuildResult result = builder.build(companyId).orElseThrow();
        FinancialExplainInput input = result.input();
        String latest = input.latest().period();

        assertThat(input.sectionFacts().get("structure")).isSubsetOf("fin.debt_ratio." + latest, "fin.debt_ratio_diff." + latest);
        assertThat(input.sectionFacts().get("structure")).contains("fin.debt_ratio." + latest);
        assertThat(input.facts()).noneMatch(f -> f.key().startsWith("fin.debt_ratio_prior_end")
                || f.key().startsWith("fin.total_assets") || f.key().startsWith("fin.total_liabilities")
                || f.key().startsWith("fin.total_equity") || f.key().startsWith("fin.capital_stock")
                || f.key().startsWith("fin.impairment_ratio"));
        assertThat(result.snapshot().facts().keySet()).noneMatch(k -> k.startsWith("fin.total_") || k.startsWith("fin.capital_stock"));
        assertThat(input.sections()).contains("structure");
    }

    @Test
    void activeStateSignalFromPastPeriodIsExplainedAtLatestPeriodInItsTopicSection() {
        // 716·737·908형: 자본잠식이 과거 분기(2025 Q2)에 시작돼 지금(2026 Q1)도 이어진다. 같은 과거 분기에 이력 신호도 있다.
        seedGeneralQuarter("11013", "2026.01.01 ~ 2026.03.31", "2026.03.31 현재", "1,000,000,000", "1,000,000,000", "R1");
        signalJob.run();
        insertSignal("FIN_CAPITAL_IMPAIRMENT", "2025-01-01:Q2", "STATE", "NEGATIVE", "MEDIUM", "2025-06-30", "ACTIVE");
        insertSignal("FIN_REVENUE_CHANGE", "2025-01-01:Q2", "CHANGE", "NEGATIVE", "HIGH", "2025-06-30", "PAST");

        FinancialExplainInput input = builder.build(companyId).orElseThrow().input();
        String latest = input.latest().period();

        FinancialExplainInput.SignalRef impairment = input.signals().stream()
                .filter(s -> s.type().equals("FIN_CAPITAL_IMPAIRMENT")).findFirst().orElseThrow();
        assertThat(impairment.period()).isEqualTo(latest);
        assertThat(impairment.section()).isEqualTo("structure");
        // 근거 사실은 최신 기간의 자본총계·자본금이다(발생 기간 기준이던 이전에는 비어 있었다).
        assertThat(impairment.factKeys()).containsExactly("fin.total_equity." + latest, "fin.capital_stock." + latest);
        // 자본잠식이 활성이면 structure에 자본총계·자본금이 배정되고, 상한(4)을 넘지 않는다.
        assertThat(input.sectionFacts().get("structure"))
                .contains("fin.total_equity." + latest, "fin.capital_stock." + latest).hasSizeLessThanOrEqualTo(4);

        FinancialExplainInput.SignalRef past = input.signals().stream()
                .filter(s -> s.status().equals("PAST")).findFirst().orElseThrow();
        assertThat(past.period()).isEqualTo("2025-01.Q2");
        assertThat(past.section()).isEqualTo("history");

        // 같은 과거 분기라도 활성 신호와 이력 신호는 한 묶음이 되지 않는다. history 묶음에는 이력 신호만 있다.
        assertThat(input.groups()).allSatisfy(g -> assertThat(g.refs().contains(impairment.ref()) && g.refs().contains(past.ref())).isFalse());
        assertThat(input.groups()).filteredOn(g -> g.section().equals("history"))
                .allSatisfy(g -> assertThat(g.refs()).containsOnly(past.ref()));
        // 활성 묶음이 이력 묶음보다 앞선다.
        assertThat(input.groups().get(input.groups().size() - 1).section()).isEqualTo("history");
    }

    @Test
    void noneOverviewGetsOneChangeFactByPriorityAndItIsNotReassigned() {
        // 매출 변화가 신호 문턱값(±30%/−20%) 아래라 변화 신호가 없다(NONE). 매출 증가율이 개요에 지정되고,
        // 변화량 우선순위에서 뒤인 부채비율 변화는 structure에 남는다.
        seedGeneralQuarter("11013", "2026.01.01 ~ 2026.03.31", "2026.03.31 현재", "2,100,000,000", "2,000,000,000", "R1");
        signalJob.run();

        FinancialExplainInput input = builder.build(companyId).orElseThrow().input();
        String latest = input.latest().period();

        assertThat(input.changeStatus()).isEqualTo("NONE");
        assertThat(input.sectionFacts().get("overview")).containsExactly("fin.revenue_yoy." + latest);
        assertThat(input.sectionFacts().get("structure")).doesNotContain("fin.revenue_yoy." + latest)
                .contains("fin.debt_ratio_diff." + latest);
    }

    @Test
    void changedOverviewGetsFirstGroupChangeFactsOnly() {
        seedGeneralQuarter("11013", "2026.01.01 ~ 2026.03.31", "2026.03.31 현재", "2,000,000,000", "1,500,000,000", "R1");
        signalJob.run();

        FinancialExplainInput input = builder.build(companyId).orElseThrow().input();

        assertThat(input.changeStatus()).isEqualTo("CHANGED");
        assertThat(input.sectionFacts().get("overview")).isNotEmpty()
                .allSatisfy(k -> assertThat(FinancialExplainInputBuilder.isChangeFact(k)).isTrue())
                .hasSizeLessThanOrEqualTo(3);
        assertThat(input.sectionFacts().get("structure")).doesNotContainAnyElementsOf(input.sectionFacts().get("overview"));
    }

    @Test
    void financialFormatStructureIsEquityOnlyWithoutImpairment() {
        List<RawAccountLine> rows = List.of(
                line("BS", "자산총계", "1", "2026.03.31 현재", "9,000,000,000", "8,000,000,000"),
                line("BS", "부채총계", "9", "2026.03.31 현재", "4,000,000,000", "3,500,000,000"),
                line("BS", "자본금", "11", "2026.03.31 현재", "1,000,000,000", "1,000,000,000"),
                line("BS", "자본총계", "13", "2026.03.31 현재", "5,000,000,000", "4,500,000,000"),
                line("IS", "영업이익", "27", "2026.01.01 ~ 2026.03.31", "500,000,000", "400,000,000"));
        financialService.replace(companyId, 2026, "11013", rows, null);
        signalJob.run();

        FinancialExplainInput input = builder.build(companyId).orElseThrow().input();

        assertThat(input.sectionFacts().get("structure")).containsExactly("fin.total_equity." + input.latest().period());
    }

    @Test
    void inconsistentBalanceWithActiveImpairmentOpensStructureForBadgeOnly() {
        // 재무상태표 불일치로 재무상태 사실이 없어도 활성 재무 구조 신호가 있으면 structure를 연다(배지만, D-52).
        List<RawAccountLine> rows = List.of(
                line("BS", "유동자산", "1", "2026.03.31 현재", "3,000,000,000", "2,500,000,000"),
                line("BS", "자산총계", "5", "2026.03.31 현재", "9,000,000,000", "8,000,000,000"),
                line("BS", "부채총계", "9", "2026.03.31 현재", "4,000,000,000", "3,500,000,000"),
                line("BS", "자본총계", "13", "2026.03.31 현재", "4,500,000,000", "4,500,000,000"),
                line("IS", "매출액", "23", "2026.01.01 ~ 2026.03.31", "2,000,000,000", "1,500,000,000"),
                line("IS", "영업이익", "27", "2026.01.01 ~ 2026.03.31", "500,000,000", "400,000,000"));
        financialService.replace(companyId, 2026, "11013", rows, null);
        signalJob.run();
        insertSignal("FIN_CAPITAL_IMPAIRMENT", "2025-01-01:Q4", "STATE", "NEGATIVE", "HIGH", "2025-12-31", "ACTIVE");

        FinancialExplainInput input = builder.build(companyId).orElseThrow().input();

        assertThat(input.sections()).contains("structure");
        assertThat(input.sectionFacts().get("structure")).isEmpty();
        assertThat(input.signals()).anySatisfy(s -> {
            assertThat(s.type()).isEqualTo("FIN_CAPITAL_IMPAIRMENT");
            assertThat(s.section()).isEqualTo("structure");
            assertThat(s.factKeys()).isEmpty();
        });
    }

    private void insertSignal(String type, String basisKey, String nature, String direction, String severity,
            String occurredOn, String status) {
        jdbc.sql("""
                insert into company_signal (company_id, signal_type, basis_key, nature, direction, severity,
                    occurred_on, calc_values, watch_metrics, status, rule_version, first_detected_at,
                    last_evaluated_at, status_changed_at)
                values (:companyId, :type, :basisKey, :nature, :direction, :severity,
                    cast(:occurredOn as date), '{}', '[]', :status, :ruleVersion, now(), now(), now())
                """)
                .param("companyId", companyId)
                .param("type", type)
                .param("basisKey", basisKey)
                .param("nature", nature)
                .param("direction", direction)
                .param("severity", severity)
                .param("occurredOn", occurredOn)
                .param("status", status)
                .param("ruleVersion", FinancialRuleCatalog.RULE_VERSION)
                .update();
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
    void nonDecemberFiscalYearUsesRangeLabelInSnapshotAndAnnualKindInInput() {
        // D-59: AI 입력에는 라벨 글자 없이 kind만 있다. 라벨(렌더링용)은 값 스냅샷에서 확인한다.
        companyService.upsertListed(new ListedCompany("00241209", "모아텍", "모아텍(주)", "033200", Market.KOSDAQ, "264", 3));
        long moatechId = companyService.findByDartCorpCode("00241209").orElseThrow().getId();
        List<RawAccountLine> rows = List.of(
                line("BS", "유동자산", "1", "2026.03.31 현재", "1,000,000,000", "900,000,000"),
                line("IS", "매출액", "23", "2025.04.01 ~ 2026.03.31", "9,000,000,000", "8,000,000,000"));
        financialService.replace(moatechId, 2026, "11011", rows, null);

        FinancialExplainInputBuilder.BuildResult result = builder.build(moatechId).orElseThrow();
        FinancialExplainInput input = result.input();

        assertThat(input.periods()).anySatisfy(p -> assertThat(p.kind()).isEqualTo("ANNUAL"));
        assertThat(result.snapshot().periodLabels().values()).anySatisfy(label -> assertThat(label).contains("회계연도"));
    }

    // ---- D-54 보완·D-55: 모든 섹션 배정, 상태·전환 사실, 관계 항목 (docs/work/3-4-verification-2.md §7.4.26·§7.4.27 점검표) ----

    private static final String H1 = "2026.01.01 ~ 2026.06.30";
    private static final String H1_END = "2026.06.30 현재";

    @Test
    void factsAreUnionOfSectionAssignmentsAndSnapshotMatches() {
        // 점검표 1·18·19: 사실표 = 배정의 합집합, 한 사실은 한 섹션에만, history 배정은 빈 목록, 상한 이하.
        // 값 스냅샷은 입력 사실과 같은 키만 담고 관계 항목은 담지 않는다.
        seedIncomeQuarter("11012", H1, H1_END, "4,000,000,000", "2,000,000,000", "900,000,000", "400,000,000",
                "300,000,000", "-100,000,000", "R2");
        signalJob.run();

        FinancialExplainInputBuilder.BuildResult result = builder.build(companyId).orElseThrow();
        FinancialExplainInput input = result.input();

        assertThat(input.sectionFacts()).containsOnlyKeys("overview", "sales_profit", "structure", "history");
        assertThat(input.sectionFacts().get("history")).isEmpty();
        List<String> all = input.sectionFacts().values().stream().flatMap(List::stream).toList();
        assertThat(all).doesNotHaveDuplicates();
        assertThat(input.facts()).extracting(FinancialExplainInput.Fact::key).containsExactlyInAnyOrderElementsOf(all);
        assertThat(input.sectionFacts().get("overview")).hasSizeLessThanOrEqualTo(3);
        assertThat(input.sectionFacts().get("sales_profit")).hasSizeLessThanOrEqualTo(6);
        assertThat(input.sectionFacts().get("structure")).hasSizeLessThanOrEqualTo(4);
        assertThat(result.snapshot().facts().keySet()).containsExactlyInAnyOrderElementsOf(all);
        // 신호의 근거 사실은 사실표 안에만 있다. 매출·영업이익률·순이익의 전년 원값은 배정되지 않는다.
        assertThat(input.signals()).allSatisfy(s -> assertThat(all).containsAll(s.factKeys()));
        assertThat(all).noneMatch(k -> k.startsWith("fin.revenue_prior") || k.startsWith("fin.operating_margin_prior")
                || k.startsWith("fin.net_income_prior"));
    }

    @Test
    void netIncomeTurnIsOneFactWithClosedDisplayAndSnapshotBasis() {
        // 점검표 2·18·21: 순이익 전환은 사실 하나(표시 "적자에서 흑자로"), 순이익과 함께 배정(관계 단위). 영업이익 상태 사실은 없다.
        seedIncomeQuarter("11012", H1, H1_END, "4,000,000,000", "3,800,000,000", "500,000,000", "400,000,000",
                "300,000,000", "-100,000,000", "R2");
        signalJob.run();

        FinancialExplainInputBuilder.BuildResult result = builder.build(companyId).orElseThrow();
        FinancialExplainInput input = result.input();
        String latest = input.latest().period();
        String turnKey = "fin.net_income_turn." + latest;

        // D-59: AI 입력의 사실에는 표시 값이 없다(이름·부호·기간만).
        assertThat(input.facts()).anySatisfy(f -> {
            assertThat(f.key()).isEqualTo(turnKey);
            assertThat(f.sign()).isEqualTo("+");
        });
        assertThat(input.sectionFacts().get("sales_profit")).contains("fin.net_income." + latest, turnKey);
        assertThat(input.facts()).noneMatch(f -> f.key().startsWith("fin.operating_income_status"));
        assertThat(input.facts()).noneMatch(f -> f.key().startsWith("fin.net_income_status")); // 전환이 상태를 대신한다
        ValueSnapshot.FactSnapshot snap = result.snapshot().facts().get(turnKey);
        assertThat(snap.display()).isEqualTo("적자에서 흑자로"); // 닫힌 문구는 값 스냅샷(렌더링용)에 있다
        assertThat(snap.rawValue()).isEqualByComparingTo("300000000");
        assertThat(snap.receiptNo()).isEqualTo("R2");
        assertThat(snap.basis()).isEqualTo("CFS");
        assertThat(snap.periodEnd()).isEqualTo("2026-06-30");
    }

    @Test
    void zeroIncomeCreatesNoStateOrTurnFact() {
        seedIncomeQuarter("11012", H1, H1_END, "4,000,000,000", "3,800,000,000", "500,000,000", "400,000,000",
                "0", "-100,000,000", "R2");
        signalJob.run();

        FinancialExplainInput input = builder.build(companyId).orElseThrow().input();

        assertThat(input.facts()).noneMatch(f -> f.key().startsWith("fin.net_income_turn") || f.key().startsWith("fin.net_income_status"));
    }

    @Test
    void bothProfitableOperatingIncomeGetsDirectionRelationWithPrior() {
        // 점검표 3·4: 최신·전년 영업이익이 모두 흑자일 때만 방향 관계(R2). 두 사실이 모두 배정된 경우에만 넣는다.
        seedIncomeQuarter("11012", H1, H1_END, "4,000,000,000", "3,800,000,000", "500,000,000", "400,000,000",
                "300,000,000", "200,000,000", "R2");
        signalJob.run();

        FinancialExplainInput input = builder.build(companyId).orElseThrow().input();
        String latest = input.latest().period();

        assertThat(input.sectionFacts().get("sales_profit")).contains("fin.operating_income_prior." + latest);
        assertThat(input.relations()).containsExactly(new FinancialExplainInput.Relation(
                FinancialExplainInput.Relation.OPERATING_INCOME_DIRECTION,
                List.of("fin.operating_income." + latest, "fin.operating_income_prior." + latest), "UP"));
        // 순이익은 영업이익과 상태가 같고 전환도 없어 배정하지 않는다(§4.4.3 조건을 코드가 판정).
        assertThat(input.facts()).noneMatch(f -> f.key().startsWith("fin.net_income"));
    }

    @Test
    void bothLossOperatingIncomeHasNoDirectionButStateDifferenceRelation() {
        // 점검표 4·22: 모두 적자면 방향 관계 없이 전년 값만 나란히. 순이익 상태가 영업이익과 다르면 순이익 + 상태 사실과 R5.
        seedIncomeQuarter("11012", H1, H1_END, "4,000,000,000", "3,800,000,000", "-500,000,000", "-400,000,000",
                "200,000,000", "100,000,000", "R2");
        signalJob.run();

        FinancialExplainInputBuilder.BuildResult result = builder.build(companyId).orElseThrow();
        FinancialExplainInput input = result.input();
        String latest = input.latest().period();
        String statusKey = "fin.net_income_status." + latest;

        assertThat(input.sectionFacts().get("sales_profit"))
                .contains("fin.operating_income_prior." + latest, "fin.net_income." + latest, statusKey);
        assertThat(input.facts()).anySatisfy(f -> assertThat(f.key()).isEqualTo(statusKey));
        assertThat(result.snapshot().facts().get(statusKey).display()).isEqualTo("흑자");
        assertThat(input.relations()).containsExactly(new FinancialExplainInput.Relation(
                FinancialExplainInput.Relation.STATE_DIFFERENCE,
                List.of(statusKey, "fin.operating_income." + latest), "DIFFERENT"));
    }

    @Test
    void operatingTurnWithoutSignalIsAssignedAsFactButNotInOverview() {
        // 점검표 20·24(최종 점검 §7.4.27): 전년 영업이익률이 2% 미만이라 전환 신호는 없지만 부호는 바뀌었다. 전환 사실은
        // 신호와 무관한 부호 사실이라 sales_profit 우선순위 6에 배정한다. 신호가 없으니 개요·배지는 없다.
        seedIncomeQuarter("11012", H1, H1_END, "2,000,000,000", "1,950,000,000", "500,000,000", "-10,000,000",
                "300,000,000", "200,000,000", "R2");
        signalJob.run();

        FinancialExplainInput input = builder.build(companyId).orElseThrow().input();
        String latest = input.latest().period();

        assertThat(input.signals()).noneMatch(s -> s.type().equals("FIN_OPERATING_TURN"));
        assertThat(input.sectionFacts().get("sales_profit")).contains("fin.operating_income_turn." + latest)
                .doesNotContain("fin.operating_income_prior." + latest);
        assertThat(input.sectionFacts().get("overview")).doesNotContain("fin.operating_income_turn." + latest);
        assertThat(input.relations()).noneMatch(r -> r.type().equals(FinancialExplainInput.Relation.OPERATING_INCOME_DIRECTION));
    }

    @Test
    void financialFormatQuarterlyOperatingTurnWithoutSignalIsAssigned() {
        // 금융형은 흑자·적자 전환 신호를 연간으로만 판정한다(D-35). 분기의 부호 뒤바뀜도 전환 사실로 설명할 수 있다.
        List<RawAccountLine> rows = List.of(
                line("BS", "자산총계", "1", H1_END, "9,000,000,000", "8,000,000,000", "R2"),
                line("BS", "부채총계", "9", H1_END, "4,000,000,000", "3,500,000,000", "R2"),
                line("BS", "자본총계", "13", H1_END, "5,000,000,000", "4,500,000,000", "R2"),
                line("IS", "영업이익", "27", H1, "500,000,000", "-300,000,000", "R2"));
        financialService.replace(companyId, 2026, "11012", rows, null);
        signalJob.run();

        FinancialExplainInput input = builder.build(companyId).orElseThrow().input();

        assertThat(input.company().format()).isEqualTo("FINANCIAL");
        assertThat(input.signals()).noneMatch(s -> s.type().equals("FIN_OPERATING_TURN"));
        assertThat(input.sectionFacts().get("sales_profit")).contains("fin.operating_income_turn." + input.latest().period());
    }

    @Test
    void operatingTurnSignalFirstGroupPutsTurnFactInOverview() {
        // 점검표 20: 전환 신호가 있으면 전환 사실이 신호의 근거다. 전환만 있는 첫 묶음이면 그 근거는 개요에 배정된다(fx-input-5,
        // §7.4.28 — D-55 초안의 "개요는 배지만"을 바꿨다. 한 사실은 한 섹션에만 있으므로 sales_profit에는 없다).
        seedIncomeQuarter("11012", H1, H1_END, "2,000,000,000", "1,950,000,000", "500,000,000", "-400,000,000",
                "300,000,000", "200,000,000", "R2");
        signalJob.run();

        FinancialExplainInput input = builder.build(companyId).orElseThrow().input();
        String turnKey = "fin.operating_income_turn." + input.latest().period();

        FinancialExplainInput.SignalRef turn = input.signals().stream()
                .filter(s -> s.type().equals("FIN_OPERATING_TURN")).findFirst().orElseThrow();
        assertThat(turn.factKeys()).contains(turnKey);
        assertThat(input.sectionFacts().get("overview")).containsExactly(turnKey);
        assertThat(input.sectionFacts().get("sales_profit")).doesNotContain(turnKey)
                .doesNotContain("fin.operating_income_prior." + input.latest().period());
    }

    @Test
    void nearZeroNetIncomeFlipStillBecomesTurnFact() {
        // 점검표 24: 0 근처의 뒤바뀜도 부호 사실이다(크기는 값을 나란히 놓아 보인다). 과한 강조로 확인되면 D-54 보완 재검토 조건.
        seedIncomeQuarter("11012", H1, H1_END, "4,000,000,000", "3,800,000,000", "500,000,000", "400,000,000",
                "1,000,000", "-1,000,000", "R2");
        signalJob.run();

        FinancialExplainInputBuilder.BuildResult result = builder.build(companyId).orElseThrow();
        FinancialExplainInput input = result.input();

        String turnKey = input.facts().stream().map(FinancialExplainInput.Fact::key)
                .filter(k -> k.startsWith("fin.net_income_turn.")).findFirst().orElseThrow();
        assertThat(result.snapshot().facts().get(turnKey).display()).isEqualTo("적자에서 흑자로");
    }

    @Test
    void historyGroupsAreOrderedRecentToPastAndCarryNoFacts() {
        // 점검표 5(908형): 고르는 순서는 심각도지만 history의 서술 순서는 기간 종료일 내림차순이다. 이력 신호의 근거 사실은 비운다.
        seedGeneralQuarter("11013", "2026.01.01 ~ 2026.03.31", "2026.03.31 현재", "1,000,000,000", "1,000,000,000", "R1");
        signalJob.run();
        insertSignal("FIN_REVENUE_CHANGE", "2025-01-01:Q1", "CHANGE", "NEGATIVE", "HIGH", "2025-03-31", "PAST");
        insertSignal("FIN_OPERATING_MARGIN_CHANGE", "2025-01-01:H1", "CHANGE", "NEGATIVE", "MEDIUM", "2025-06-30", "PAST");

        FinancialExplainInput input = builder.build(companyId).orElseThrow().input();

        List<String> historyPeriods = input.groups().stream().filter(g -> g.section().equals("history"))
                .map(FinancialExplainInput.Group::period).toList();
        assertThat(historyPeriods).containsExactly("2025-01.Q2", "2025-01.Q1");
        assertThat(input.signals()).filteredOn(s -> s.status().equals("PAST")).allSatisfy(s -> assertThat(s.factKeys()).isEmpty());
    }

    @Test
    void factsWhoseNameContradictsDoNotMentionAreNotAssigned() {
        // 점검표 6(2715형): 최신 분기에 매출 계정이 없어 "매출"이 금지 용어인데, 사업보고서에는 매출이 있다. 이름에 "매출"이
        // 든 사실(연간 매출·연간 매출 증가율)은 어느 섹션에도 배정하지 않아 입력에 남지 않는다.
        List<RawAccountLine> annual = List.of(
                line("BS", "유동자산", "1", "2025.12.31 현재", "3,000,000,000", "2,500,000,000", "RA"),
                line("BS", "자산총계", "5", "2025.12.31 현재", "9,000,000,000", "8,000,000,000", "RA"),
                line("BS", "부채총계", "9", "2025.12.31 현재", "4,000,000,000", "3,500,000,000", "RA"),
                line("BS", "자본총계", "13", "2025.12.31 현재", "5,000,000,000", "4,500,000,000", "RA"),
                line("IS", "매출액", "23", "2025.01.01 ~ 2025.12.31", "9,000,000,000", "8,000,000,000", "RA"),
                line("IS", "영업이익", "27", "2025.01.01 ~ 2025.12.31", "-500,000,000", "-400,000,000", "RA"));
        financialService.replace(companyId, 2025, "11011", annual, null);
        List<RawAccountLine> quarter = List.of(
                line("BS", "유동자산", "1", "2026.03.31 현재", "3,000,000,000", "2,500,000,000", "RQ"),
                line("BS", "자산총계", "5", "2026.03.31 현재", "9,000,000,000", "8,000,000,000", "RQ"),
                line("BS", "부채총계", "9", "2026.03.31 현재", "4,000,000,000", "3,500,000,000", "RQ"),
                line("BS", "자본총계", "13", "2026.03.31 현재", "5,000,000,000", "4,500,000,000", "RQ"),
                line("IS", "영업이익", "27", "2026.01.01 ~ 2026.03.31", "-200,000,000", "-100,000,000", "RQ"));
        financialService.replace(companyId, 2026, "11013", quarter, null);
        signalJob.run();

        FinancialExplainInput input = builder.build(companyId).orElseThrow().input();

        assertThat(input.doNotMention()).contains("매출");
        assertThat(input.facts()).noneMatch(f -> f.name().contains("매출"));
        assertThat(input.facts()).anyMatch(f -> f.key().equals("fin.operating_income.2025-01.Q4")); // 연간 흐름은 영업이익으로
    }

    // ---- D-59: 표시 값·라벨 제거, 사실 이름의 방향, 12개월이 아닌 회계연도 제외 (docs/work/3-4-verification-2.md §7.4.33) ----

    @Test
    void revenueYoyNameRevealsDecreaseNotAlwaysIncrease() {
        // 이전에는 부호와 무관하게 항상 "매출 증가율"이라고 썼다 — 실제 감소일 때도(§7.4.33). 신호 문턱값 아래인 작은
        // 하락(-5%)으로 changeStatus를 NONE에 두어, NONE 우선순위가 revenue_yoy를 개요에 배정하게 한다(D-54).
        seedGeneralQuarter("11013", "2026.01.01 ~ 2026.03.31", "2026.03.31 현재", "1,900,000,000", "2,000,000,000", "R1");
        signalJob.run();

        FinancialExplainInput input = builder.build(companyId).orElseThrow().input();
        String latest = input.latest().period();

        assertThat(input.changeStatus()).isEqualTo("NONE");
        assertThat(input.sectionFacts().get("overview")).containsExactly("fin.revenue_yoy." + latest);
        assertThat(input.facts()).anySatisfy(f -> {
            assertThat(f.key()).isEqualTo("fin.revenue_yoy." + latest);
            assertThat(f.sign()).isEqualTo("-");
            assertThat(f.name()).contains("매출 감소율").doesNotContain("증가율");
        });
    }

    @Test
    void operatingMarginDiffNameRevealsDecreaseAndDebtRatioDiffUsesFiscalYearEndWording() {
        // 매출 기준값 미만(SMALL_BASE)이라 revenue_yoy 자체가 없어, NONE 우선순위가 operating_margin_diff로 넘어간다.
        // 부채비율 이름도 "(전기말 대비)" 대신 "(지난 회계연도 말 대비)"로 쓴다(D-59, 프롬프트 표현과 맞춘다).
        List<RawAccountLine> rows = List.of(
                line("BS", "유동자산", "1", "2026.03.31 현재", "3,000,000,000", "2,500,000,000", "R1"),
                line("BS", "자산총계", "5", "2026.03.31 현재", "9,000,000,000", "8,000,000,000", "R1"),
                line("BS", "부채총계", "9", "2026.03.31 현재", "3,000,000,000", "4,000,000,000", "R1"), // 부채비율 50% < 전기 80%
                line("BS", "자본총계", "13", "2026.03.31 현재", "6,000,000,000", "5,000,000,000", "R1"),
                line("IS", "매출액", "23", "2026.01.01 ~ 2026.03.31", "550,000,000", "500,000,000", "R1"), // 전기 < 10억(기준값)
                line("IS", "영업이익", "27", "2026.01.01 ~ 2026.03.31", "50,000,000", "125,000,000", "R1")); // 마진 9.1%<25%
        financialService.replace(companyId, 2026, "11013", rows, null);
        signalJob.run();

        FinancialExplainInput input = builder.build(companyId).orElseThrow().input();
        String latest = input.latest().period();

        assertThat(input.facts()).noneMatch(f -> f.key().startsWith("fin.revenue_yoy"));
        assertThat(input.facts()).anySatisfy(f -> {
            assertThat(f.key()).isEqualTo("fin.operating_margin_diff." + latest);
            assertThat(f.sign()).isEqualTo("-");
            assertThat(f.name()).contains("영업이익률 하락폭").doesNotContain("상승폭");
        });
        assertThat(input.facts()).anySatisfy(f -> {
            assertThat(f.key()).isEqualTo("fin.debt_ratio_diff." + latest);
            assertThat(f.sign()).isEqualTo("-");
            assertThat(f.name()).isEqualTo("부채비율 하락폭(지난 회계연도 말 대비)");
        });
    }

    @Test
    void irregularAnnualEntryIsExcludedFromLatestAnnualFact() {
        // 2386형: 분할 신설 등으로 12개월이 아닌 회계연도(결산기 변경)는 "최근 사업연도 사실"(우선순위 7)로 배정하지
        // 않는다(D-59) — 2개월짜리 회계연도가 "2025년(연간)"처럼 1년 치로 읽히는 것을 막는다(§7.4.33).
        List<RawAccountLine> shortAnnual = List.of(
                line("BS", "유동자산", "1", "2025.12.31 현재", "3,000,000,000", "2,500,000,000", "RA"),
                line("BS", "자산총계", "5", "2025.12.31 현재", "9,000,000,000", "8,000,000,000", "RA"),
                line("BS", "부채총계", "9", "2025.12.31 현재", "4,000,000,000", "3,500,000,000", "RA"),
                line("BS", "자본총계", "13", "2025.12.31 현재", "5,000,000,000", "4,500,000,000", "RA"),
                line("IS", "매출액", "23", "2025.11.01 ~ 2025.12.31", "1,000,000,000", "900,000,000", "RA"),
                line("IS", "영업이익", "27", "2025.11.01 ~ 2025.12.31", "-200,000,000", "-100,000,000", "RA"));
        financialService.replace(companyId, 2025, "11011", shortAnnual, null);
        seedGeneralQuarter("11013", "2026.01.01 ~ 2026.03.31", "2026.03.31 현재", "1,000,000,000", "1,000,000,000", "R1");
        signalJob.run();

        FinancialExplainInput input = builder.build(companyId).orElseThrow().input();

        assertThat(input.facts()).noneMatch(f -> f.key().equals("fin.operating_income.2025-11.Q4")
                || f.key().equals("fin.revenue_yoy.2025-11.Q4"));
    }

    private void seedIncomeQuarter(String reportCode, String period, String balancePeriod, String revenue, String revenuePrior,
            String operating, String operatingPrior, String net, String netPrior, String receiptNo) {
        List<RawAccountLine> rows = List.of(
                line("BS", "유동자산", "1", balancePeriod, "3,000,000,000", "2,500,000,000", receiptNo),
                line("BS", "자산총계", "5", balancePeriod, "9,000,000,000", "8,000,000,000", receiptNo),
                line("BS", "부채총계", "9", balancePeriod, "4,000,000,000", "3,500,000,000", receiptNo),
                line("BS", "자본금", "11", balancePeriod, "1,000,000,000", "1,000,000,000", receiptNo),
                line("BS", "자본총계", "13", balancePeriod, "5,000,000,000", "4,500,000,000", receiptNo),
                line("IS", "매출액", "23", period, revenue, revenuePrior, receiptNo),
                line("IS", "영업이익", "27", period, operating, operatingPrior, receiptNo),
                line("IS", "당기순이익(손실)", "31", period, net, netPrior, receiptNo));
        int bsnsYear = Integer.parseInt(period.substring(period.length() - 10, period.length() - 6));
        financialService.replace(companyId, bsnsYear, reportCode, rows, null);
    }

    private void seedGeneralQuarter(String reportCode, String period, String balancePeriod, String current, String prior,
            String receiptNo) {
        List<RawAccountLine> rows = List.of(
                line("BS", "유동자산", "1", balancePeriod, "3,000,000,000", "2,500,000,000", receiptNo),
                line("BS", "자산총계", "5", balancePeriod, "9,000,000,000", "8,000,000,000", receiptNo),
                line("BS", "부채총계", "9", balancePeriod, "4,000,000,000", "3,500,000,000", receiptNo),
                line("BS", "자본금", "11", balancePeriod, "1,000,000,000", "1,000,000,000", receiptNo),
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
