package org.stockinsight.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * 재무 쉬운 설명 검증기의 경계값을 확인한다(ai-analysis.md §4.4.7, §7).
 */
class FinancialExplainValidatorTest {

    private final FinancialExplainValidator validator = new FinancialExplainValidator();

    private static final FinancialExplainInput.Fact REVENUE = new FinancialExplainInput.Fact(
            "fin.revenue.2026-01.Q2", "매출액", "40.0억원", "+", "2026-01.Q2");
    private static final FinancialExplainInput.Fact REVENUE_YOY = new FinancialExplainInput.Fact(
            "fin.revenue_yoy.2026-01.Q2", "매출 증가율(전년 같은 분기 대비)", "+30.0%", "+", "2026-01.Q2");
    private static final FinancialExplainInput.Fact OPERATING_INCOME = new FinancialExplainInput.Fact(
            "fin.operating_income.2026-01.Q2", "영업이익", "5.0억원", "+", "2026-01.Q2");
    private static final FinancialExplainInput.Fact OPERATING_MARGIN = new FinancialExplainInput.Fact(
            "fin.operating_margin.2026-01.Q2", "영업이익률", "+12.5%", "+", "2026-01.Q2");

    private static FinancialExplainInput baseInput(List<String> sections) {
        return new FinancialExplainInput(
                new FinancialExplainInput.Company("삼성전자", "GENERAL", "KRW", "CFS", 12),
                new FinancialExplainInput.Latest("2026-01.Q2", "QUARTER"),
                "CHANGED",
                sections,
                List.of(new FinancialExplainInput.PeriodLabel("2026-01.Q2", "2026년 2분기"),
                        new FinancialExplainInput.PeriodLabel("2026-01.Q1", "2026년 1분기")),
                List.of(REVENUE, REVENUE_YOY, OPERATING_INCOME, OPERATING_MARGIN),
                List.of(new FinancialExplainInput.SignalRef("S1", "FIN_REVENUE_CHANGE", "ACTIVE", "CHANGE",
                                "POSITIVE", "HIGH", "2026-01.Q2", null, List.of("fin.revenue.2026-01.Q2", "fin.revenue_yoy.2026-01.Q2")),
                        new FinancialExplainInput.SignalRef("S2", "FIN_REVENUE_CHANGE", "PAST", "CHANGE",
                                "NEGATIVE", "LOW", "2026-01.Q1", null, List.of())),
                List.of(new FinancialExplainInput.Group("2026-01.Q2", "SALES_PROFIT", List.of("S1"), false)),
                List.of(new FinancialExplainInput.Unavailable("operating_margin", "ACCOUNT_MISSING")),
                List.of("부채비율"));
    }

    private static FinancialExplainOutput output(String overview, String salesProfit, String structure, String history) {
        return new FinancialExplainOutput(overview, salesProfit, structure, history);
    }

    @Test
    void validOutputPasses() {
        FinancialExplainInput input = baseInput(List.of("overview", "sales_profit", "history"));
        FinancialExplainOutput out = output(
                "{per.2026-01.Q2} 매출이 {fin.revenue.2026-01.Q2}으로 늘었어요.",
                "{sig.S1} 매출이 늘면서 {fin.revenue_yoy.2026-01.Q2} 증가했어요.",
                null,
                "지난 {per.2026-01.Q1}에는 {sig.S2} 매출이 줄었어요.");

        FinancialExplainValidator.ValidationResult result = validator.validate(out, input);

        assertThat(result.valid()).isTrue();
        assertThat(result.failedRules()).isEmpty();
    }

    // ---- 규칙 1: sections에 있는 섹션만 채움 ----

    @Test
    void rule1FailsWhenSectionNotInListIsFilled() {
        FinancialExplainInput input = baseInput(List.of("overview"));
        FinancialExplainOutput out = output("{per.2026-01.Q2} 상태예요.", "채우면 안 되는 섹션이에요.", null, null);

        assertThat(validator.validate(out, input).failedRules()).contains("1");
    }

    @Test
    void rule1FailsWhenListedSectionIsMissing() {
        FinancialExplainInput input = baseInput(List.of("overview", "sales_profit"));
        FinancialExplainOutput out = output("{per.2026-01.Q2} 상태예요.", null, null, null);

        assertThat(validator.validate(out, input).failedRules()).contains("1");
    }

    // ---- 규칙 2: 모든 토큰이 입력에 있음 ----

    @Test
    void rule2FailsForUnknownToken() {
        FinancialExplainInput input = baseInput(List.of("overview"));
        FinancialExplainOutput out = output("{fin.revenue_yoy.2025-01.Q3} 값이에요.", null, null, null);

        assertThat(validator.validate(out, input).failedRules()).contains("2");
    }

    @Test
    void rule2PassesForKnownToken() {
        FinancialExplainInput input = baseInput(List.of("overview"));
        FinancialExplainOutput out = output("{fin.revenue.2026-01.Q2} 규모예요.", null, null, null);

        assertThat(validator.validate(out, input).failedRules()).doesNotContain("2");
    }

    // ---- 규칙 3: 토큰 밖 숫자·수량 표현 없음 ----

    @Test
    void rule3FailsForBareNumber() {
        FinancialExplainInput input = baseInput(List.of("overview"));
        FinancialExplainOutput out = output("매출이 30% 늘었어요.", null, null, null);

        assertThat(validator.validate(out, input).failedRules()).contains("3");
    }

    @Test
    void rule3FailsForQuantityWord() {
        FinancialExplainInput input = baseInput(List.of("overview"));
        FinancialExplainOutput out = output("매출이 두 배로 늘었어요.", null, null, null);

        assertThat(validator.validate(out, input).failedRules()).contains("3");
    }

    @Test
    void rule3PassesWhenNumberIsInsideToken() {
        FinancialExplainInput input = baseInput(List.of("overview"));
        FinancialExplainOutput out = output("매출이 {fin.revenue_yoy.2026-01.Q2} 늘었어요.", null, null, null);

        assertThat(validator.validate(out, input).failedRules()).doesNotContain("3");
    }

    // ---- 규칙 4: 같은 사실 토큰 한 번, 같은 신호 참조 두 번까지 ----

    @Test
    void rule4FailsWhenSameFactTokenRepeats() {
        FinancialExplainInput input = baseInput(List.of("overview", "sales_profit"));
        FinancialExplainOutput out = output(
                "매출이 {fin.revenue.2026-01.Q2}이에요.",
                "다시 말하면 {fin.revenue.2026-01.Q2}예요.", null, null);

        assertThat(validator.validate(out, input).failedRules()).contains("4");
    }

    @Test
    void rule4FailsWhenSignalRefUsedThreeTimes() {
        FinancialExplainInput input = baseInput(List.of("overview", "sales_profit", "history"));
        FinancialExplainOutput out = output(
                "{sig.S1} 변화가 있었어요.",
                "{sig.S1} 다시 나와요.",
                null,
                "{sig.S1} 세 번째예요.");

        assertThat(validator.validate(out, input).failedRules()).contains("4");
    }

    @Test
    void rule4PassesWhenSignalRefUsedTwice() {
        FinancialExplainInput input = baseInput(List.of("overview", "sales_profit"));
        FinancialExplainOutput out = output("{sig.S1} 변화가 있었어요.", "{sig.S1} 자세히 보면 늘었어요.", null, null);

        assertThat(validator.validate(out, input).failedRules()).doesNotContain("4");
    }

    // ---- 규칙 5: 강도 표현은 신호가 있는 문장에서만 ----

    @Test
    void rule5FailsForIntensityWordWithoutSignal() {
        FinancialExplainInput input = baseInput(List.of("overview"));
        FinancialExplainOutput out = output("매출이 크게 늘었어요.", null, null, null);

        assertThat(validator.validate(out, input).failedRules()).contains("5");
    }

    @Test
    void rule5PassesForIntensityWordWithSignal() {
        FinancialExplainInput input = baseInput(List.of("overview"));
        FinancialExplainOutput out = output("{sig.S1} 매출이 크게 늘었어요.", null, null, null);

        assertThat(validator.validate(out, input).failedRules()).doesNotContain("5");
    }

    @Test
    void rule5FailsWhenChangeStatusNoneHasIntensityWord() {
        FinancialExplainInput noneInput = new FinancialExplainInput(
                baseInput(List.of("overview")).company(), baseInput(List.of("overview")).latest(), "NONE",
                List.of("overview"), baseInput(List.of("overview")).periods(), List.of(), List.of(), List.of(),
                List.of(), List.of());
        FinancialExplainOutput out = output("{per.2026-01.Q2} 특별히 지속되는 변화는 없어요.", null, null, null);

        assertThat(validator.validate(out, noneInput).failedRules()).contains("5");
    }

    // ---- 규칙 6: 방향 일치 ----

    @Test
    void rule6FailsWhenWordingContradictsSign() {
        FinancialExplainInput input = baseInput(List.of("overview"));
        FinancialExplainOutput out = output("매출이 {fin.revenue_yoy.2026-01.Q2} 줄었어요.", null, null, null);

        assertThat(validator.validate(out, input).failedRules()).contains("6");
    }

    @Test
    void rule6PassesWhenWordingMatchesSign() {
        FinancialExplainInput input = baseInput(List.of("overview"));
        FinancialExplainOutput out = output("매출이 {fin.revenue_yoy.2026-01.Q2} 늘었어요.", null, null, null);

        assertThat(validator.validate(out, input).failedRules()).doesNotContain("6");
    }

    @Test
    void rule6FailsWhenTurnWordingContradictsDirection() {
        FinancialExplainInput base = baseInput(List.of("overview"));
        FinancialExplainInput input = new FinancialExplainInput(base.company(), base.latest(), base.changeStatus(),
                base.sections(), base.periods(), base.facts(),
                List.of(new FinancialExplainInput.SignalRef("S1", "FIN_OPERATING_TURN", "ACTIVE", "CHANGE",
                        "POSITIVE", "MEDIUM", "2026-01.Q2", null, List.of())),
                base.groups(), base.unavailable(), base.doNotMention());
        FinancialExplainOutput out = output("{sig.S1} 적자로 바뀌었어요.", null, null, null);

        assertThat(validator.validate(out, input).failedRules()).contains("6");
    }

    // ---- 규칙 7: 이력은 history에서만, 활성은 history 밖에서만 ----

    @Test
    void rule7FailsWhenPastSignalOutsideHistory() {
        FinancialExplainInput input = baseInput(List.of("overview"));
        FinancialExplainOutput out = output("{sig.S2} 지난 변화예요.", null, null, null);

        assertThat(validator.validate(out, input).failedRules()).contains("7");
    }

    @Test
    void rule7FailsWhenActiveSignalInsideHistory() {
        FinancialExplainInput input = baseInput(List.of("overview", "history"));
        FinancialExplainOutput out = output("{per.2026-01.Q2} 상태예요.", null, null, "{sig.S1} 최근 변화예요.");

        assertThat(validator.validate(out, input).failedRules()).contains("7");
    }

    // ---- 규칙 8: doNotMention·unavailable 지표 이름 없음 ----

    @Test
    void rule8FailsForDoNotMentionTerm() {
        FinancialExplainInput input = baseInput(List.of("overview"));
        FinancialExplainOutput out = output("부채비율은 표시하지 않아요.", null, null, null);

        assertThat(validator.validate(out, input).failedRules()).contains("8");
    }

    @Test
    void rule8FailsForUnavailableMetricName() {
        FinancialExplainInput input = baseInput(List.of("overview"));
        FinancialExplainOutput out = output("영업이익률은 이번에 계산하지 않았어요.", null, null, null);

        assertThat(validator.validate(out, input).failedRules()).contains("8");
    }

    // ---- 규칙 9: 금지 표현 ----

    @Test
    void rule9FailsForInvestmentSolicitation() {
        FinancialExplainInput input = baseInput(List.of("overview"));
        FinancialExplainOutput out = output("지금 매수해도 좋아요.", null, null, null);

        assertThat(validator.validate(out, input).failedRules()).contains("9");
    }

    @Test
    void rule9FailsForCausalConnective() {
        FinancialExplainInput input = baseInput(List.of("overview"));
        FinancialExplainOutput out = output("매출이 늘었기 때문에 좋아요.", null, null, null);

        assertThat(validator.validate(out, input).failedRules()).contains("9");
    }

    @Test
    void rule9FailsForComparisonNotInInput() {
        FinancialExplainInput input = baseInput(List.of("overview"));
        FinancialExplainOutput out = output("전분기보다 좋아졌어요.", null, null, null);

        assertThat(validator.validate(out, input).failedRules()).contains("9");
    }

    // ---- 규칙 10: 섹션 분량 ----

    @Test
    void rule10FailsWhenOverviewHasTooManySentences() {
        FinancialExplainInput input = baseInput(List.of("overview"));
        FinancialExplainOutput out = output("첫 문장이에요. 둘째 문장이에요. 셋째 문장이에요.", null, null, null);

        assertThat(validator.validate(out, input).failedRules()).contains("10");
    }

    @Test
    void rule10PassesAtSentenceLimit() {
        FinancialExplainInput input = baseInput(List.of("overview"));
        FinancialExplainOutput out = output("첫 문장이에요. 둘째 문장이에요.", null, null, null);

        assertThat(validator.validate(out, input).failedRules()).doesNotContain("10");
    }

    @Test
    void rule10FailsWhenOverviewHasTooManyFactTokens() {
        FinancialExplainInput input = baseInput(List.of("overview"));
        FinancialExplainOutput out = output(
                "매출은 {fin.revenue.2026-01.Q2}, 증가율은 {fin.revenue_yoy.2026-01.Q2}, "
                        + "영업이익은 {fin.operating_income.2026-01.Q2}, 영업이익률은 {fin.operating_margin.2026-01.Q2}예요.",
                null, null, null);

        assertThat(validator.validate(out, input).failedRules()).contains("10");
    }
}
