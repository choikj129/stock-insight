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
            "fin.revenue.2026-01.Q2", "매출액", "+", "2026-01.Q2");
    private static final FinancialExplainInput.Fact REVENUE_YOY = new FinancialExplainInput.Fact(
            "fin.revenue_yoy.2026-01.Q2", "매출 증가율(전년 같은 분기 대비)", "+", "2026-01.Q2");
    private static final FinancialExplainInput.Fact OPERATING_INCOME = new FinancialExplainInput.Fact(
            "fin.operating_income.2026-01.Q2", "영업이익", "+", "2026-01.Q2");
    private static final FinancialExplainInput.Fact OPERATING_MARGIN = new FinancialExplainInput.Fact(
            "fin.operating_margin.2026-01.Q2", "영업이익률", "+", "2026-01.Q2");
    private static final FinancialExplainInput.Fact OPERATING_LOSS_RUN = new FinancialExplainInput.Fact(
            "fin.operating_loss_run.2026-01.Q2", "연속 영업적자 분기 수", "-", "2026-01.Q2");
    private static final FinancialExplainInput.Fact OPERATING_MARGIN_DIFF = new FinancialExplainInput.Fact(
            "fin.operating_margin_diff.2026-01.Q2", "영업이익률 변화", "-", "2026-01.Q2");

    private static FinancialExplainInput baseInput(List<String> sections) {
        return new FinancialExplainInput(
                new FinancialExplainInput.Company("삼성전자", "GENERAL", "KRW", "CFS", 12),
                new FinancialExplainInput.Latest("2026-01.Q2", "QUARTER"),
                "CHANGED",
                sections,
                List.of(new FinancialExplainInput.PeriodLabel("2026-01.Q2", "QUARTER"),
                        new FinancialExplainInput.PeriodLabel("2026-01.Q1", "QUARTER")),
                List.of(REVENUE, REVENUE_YOY, OPERATING_INCOME, OPERATING_MARGIN),
                List.of(new FinancialExplainInput.SignalRef("S1", "FIN_REVENUE_CHANGE", "ACTIVE", "CHANGE",
                                "POSITIVE", "HIGH", "2026-01.Q2", "sales_profit", null,
                                List.of("fin.revenue.2026-01.Q2", "fin.revenue_yoy.2026-01.Q2")),
                        new FinancialExplainInput.SignalRef("S2", "FIN_REVENUE_CHANGE", "PAST", "CHANGE",
                                "NEGATIVE", "LOW", "2026-01.Q1", "history", null, List.of())),
                List.of(new FinancialExplainInput.Group("sales_profit", "2026-01.Q2", "SALES_PROFIT", List.of("S1"), false),
                        new FinancialExplainInput.Group("history", "2026-01.Q1", "SALES_PROFIT", List.of("S2"), false)),
                // 규칙 11 대상이 아닌 기존 테스트가 흔들리지 않도록 base는 배정 제한을 두지 않는다(sectionFacts null).
                null,
                List.of(new FinancialExplainInput.Unavailable("operating_margin", "ACCOUNT_MISSING")),
                List.of("부채비율"));
    }

    private static FinancialExplainOutput output(String overview, String salesProfit, String structure, String history) {
        return new FinancialExplainOutput(overview, salesProfit, structure, history);
    }

    @Test
    void validOutputPasses() {
        FinancialExplainInput input = baseInput(List.of("overview", "sales_profit", "history"));
        // D-55 이전의 개요 "매출이 {fin.revenue…}으로 늘었어요"는 수준 값 옆에 증감 어휘만 있어(근거 없음) 규칙 13으로 실패한다.
        FinancialExplainOutput out = output(
                "{per.2026-01.Q2} 매출은 {fin.revenue.2026-01.Q2}이었어요.",
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
                null, List.of(), List.of());
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
    void rule6PassesForMixedDirectionSentenceWithBothSigns() {
        // §4.4.3이 시키는 "나란히 놓기" 예시 그대로: 부호가 다른 변화 토큰 둘을 한 문장에 쓴다. 문장 안 마지막
        // 토큰의 부호만으로 전체를 판단하던 버그의 회귀 테스트 — 이런 문장은 옳게 쓴 것이므로 통과해야 한다.
        FinancialExplainInput base = baseInput(List.of("overview"));
        FinancialExplainInput input = new FinancialExplainInput(base.company(), base.latest(), base.changeStatus(),
                base.sections(), base.periods(),
                List.of(REVENUE, REVENUE_YOY, OPERATING_INCOME, OPERATING_MARGIN, OPERATING_MARGIN_DIFF),
                base.signals(), base.groups(), base.sectionFacts(), base.unavailable(), base.doNotMention());
        FinancialExplainOutput out = output(
                "매출이 {fin.revenue_yoy.2026-01.Q2} 늘었지만 영업이익률은 {fin.operating_margin_diff.2026-01.Q2} 낮아졌어요.",
                null, null, null);

        assertThat(validator.validate(out, input).failedRules()).doesNotContain("6");
    }

    // ---- 규칙 6: '지다' 축약 과거형(§7.4.19 G1) ----
    // "높아지다·낮아지다·좋아지다·나빠지다"는 '지+었'이 '졌'으로 줄어("높아졌어요") 어간 부분 문자열이 사라진다.
    // 이 축약형을 사전에 추가하기 전에는 이 문장들에서 규칙 6이 전혀 판정하지 않았다(항상 통과).

    @Test
    void rule6FailsForContractedPositiveFormWhenSignIsNegative() {
        FinancialExplainInput base = baseInput(List.of("overview"));
        FinancialExplainInput input = new FinancialExplainInput(base.company(), base.latest(), base.changeStatus(),
                base.sections(), base.periods(),
                List.of(REVENUE, REVENUE_YOY, OPERATING_INCOME, OPERATING_MARGIN, OPERATING_MARGIN_DIFF),
                base.signals(), base.groups(), base.sectionFacts(), base.unavailable(), base.doNotMention());
        FinancialExplainOutput out = output("영업이익률이 {fin.operating_margin_diff.2026-01.Q2} 높아졌어요.", null, null, null);

        assertThat(validator.validate(out, input).failedRules()).contains("6");
    }

    @Test
    void rule6PassesForContractedPositiveFormWhenSignIsPositive() {
        FinancialExplainInput input = baseInput(List.of("overview"));
        FinancialExplainOutput out = output("매출이 {fin.revenue_yoy.2026-01.Q2} 높아졌어요.", null, null, null);

        assertThat(validator.validate(out, input).failedRules()).doesNotContain("6");
    }

    @Test
    void rule6FailsForContractedNegativeFormWhenSignIsPositive() {
        FinancialExplainInput input = baseInput(List.of("overview"));
        FinancialExplainOutput out = output("매출이 {fin.revenue_yoy.2026-01.Q2} 낮아졌어요.", null, null, null);

        assertThat(validator.validate(out, input).failedRules()).contains("6");
    }

    @Test
    void rule6PassesForContractedNegativeFormWhenSignIsNegative() {
        FinancialExplainInput base = baseInput(List.of("overview"));
        FinancialExplainInput input = new FinancialExplainInput(base.company(), base.latest(), base.changeStatus(),
                base.sections(), base.periods(),
                List.of(REVENUE, REVENUE_YOY, OPERATING_INCOME, OPERATING_MARGIN, OPERATING_MARGIN_DIFF),
                base.signals(), base.groups(), base.sectionFacts(), base.unavailable(), base.doNotMention());
        FinancialExplainOutput out = output("영업이익률이 {fin.operating_margin_diff.2026-01.Q2} 낮아졌어요.", null, null, null);

        assertThat(validator.validate(out, input).failedRules()).doesNotContain("6");
    }

    @Test
    void rule6FailsForJoahjyeossFormWhenSignIsNegative() {
        FinancialExplainInput base = baseInput(List.of("overview"));
        FinancialExplainInput input = new FinancialExplainInput(base.company(), base.latest(), base.changeStatus(),
                base.sections(), base.periods(),
                List.of(REVENUE, REVENUE_YOY, OPERATING_INCOME, OPERATING_MARGIN, OPERATING_MARGIN_DIFF),
                base.signals(), base.groups(), base.sectionFacts(), base.unavailable(), base.doNotMention());
        FinancialExplainOutput out = output("영업이익률이 {fin.operating_margin_diff.2026-01.Q2} 좋아졌어요.", null, null, null);

        assertThat(validator.validate(out, input).failedRules()).contains("6");
    }

    @Test
    void rule6FailsForNappajyeossFormWhenSignIsPositive() {
        FinancialExplainInput input = baseInput(List.of("overview"));
        FinancialExplainOutput out = output("매출이 {fin.revenue_yoy.2026-01.Q2} 나빠졌어요.", null, null, null);

        assertThat(validator.validate(out, input).failedRules()).contains("6");
    }

    @Test
    void rule6DoesNotFlagPlainLevelStatementWithoutJida() {
        // "높아요"·"낮아요"(수준 서술, '지' 없음)는 사전에 없으므로 부호와 무관하게 규칙 6을 걸지 않는다 —
        // 축약형을 추가하면서 "높아"·"낮아"를 통째로 어간으로 넣지 않았음을 확인하는 회귀 테스트.
        FinancialExplainInput base = baseInput(List.of("overview"));
        FinancialExplainInput input = new FinancialExplainInput(base.company(), base.latest(), base.changeStatus(),
                base.sections(), base.periods(),
                List.of(REVENUE, REVENUE_YOY, OPERATING_INCOME, OPERATING_MARGIN, OPERATING_MARGIN_DIFF),
                base.signals(), base.groups(), base.sectionFacts(), base.unavailable(), base.doNotMention());
        // D-54 뒤로 변화량 토큰에는 같은 부호의 증감 어휘가 필요하므로, "낮아졌지만"(부호 −와 일치)을 함께 둬서
        // "높아요"가 양(+)의 증감 어휘로 잘못 세지지 않는지만 본다.
        FinancialExplainOutput out = output("영업이익률이 {fin.operating_margin_diff.2026-01.Q2} 낮아졌지만 여전히 높아요.",
                null, null, null);

        assertThat(validator.validate(out, input).failedRules()).doesNotContain("6");
    }

    @Test
    void turnWordingWrittenAsTextIsRule12NotRule6() {
        // D-55: 흑자·적자는 단어 대조(규칙 6)가 아니라 코드가 표시하는 상태·전환 사실 토큰으로만 쓴다(규칙 12).
        // 글자로 쓴 "적자로 바뀌었어요"는 방향이 맞든 틀리든 규칙 12이고, 전환 신호의 단어 검사(규칙 6)는 없어졌다.
        FinancialExplainInput base = baseInput(List.of("overview"));
        FinancialExplainInput input = new FinancialExplainInput(base.company(), base.latest(), base.changeStatus(),
                base.sections(), base.periods(), base.facts(),
                List.of(new FinancialExplainInput.SignalRef("S1", "FIN_OPERATING_TURN", "ACTIVE", "CHANGE",
                        "POSITIVE", "MEDIUM", "2026-01.Q2", "sales_profit", null, List.of())),
                base.groups(), base.sectionFacts(), base.unavailable(), base.doNotMention());
        FinancialExplainOutput out = output("{sig.S1} 적자로 바뀌었어요.", null, null, null);

        assertThat(validator.validate(out, input).failedRules()).contains("12").doesNotContain("6");
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

    @Test
    void rule7FailsWhenFlowFactInsideHistory() {
        FinancialExplainInput base = baseInput(List.of("overview", "history"));
        FinancialExplainInput input = new FinancialExplainInput(base.company(), base.latest(), base.changeStatus(),
                base.sections(), base.periods(),
                List.of(REVENUE, REVENUE_YOY, OPERATING_INCOME, OPERATING_MARGIN, OPERATING_LOSS_RUN),
                base.signals(), base.groups(), base.sectionFacts(), base.unavailable(), base.doNotMention());
        FinancialExplainOutput out = output("{per.2026-01.Q2} 상태예요.", null, null,
                "{fin.operating_loss_run.2026-01.Q2}째 적자가 이어지고 있어요.");

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

    @Test
    void rule8PassesForRevenueMentionWhenOnlyRevenueYoyIsUnavailable() {
        // revenue_yoy만 unavailable(예: 비원화 NON_KRW)이어도 매출 원값(revenue)은 정상 사실이라 "매출"을
        // 언급할 수 있어야 한다 — revenue_yoy를 "매출"에 묶어 막던 버그의 회귀 테스트.
        FinancialExplainInput base = baseInput(List.of("overview"));
        FinancialExplainInput input = new FinancialExplainInput(base.company(), base.latest(), base.changeStatus(),
                base.sections(), base.periods(), base.facts(), base.signals(), base.groups(), base.sectionFacts(),
                List.of(new FinancialExplainInput.Unavailable("revenue_yoy", "NON_KRW")), base.doNotMention());
        FinancialExplainOutput out = output("매출은 {fin.revenue.2026-01.Q2}이었어요.", null, null, null);

        assertThat(validator.validate(out, input).failedRules()).doesNotContain("8");
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

    // ---- 문장 분리: '다'·'요'로 끝나도 문장 끝이 될 수 없는 말은 경계가 아니다 (D-53) ----

    @Test
    void comparativeParticleDoesNotSplitSentence() {
        FinancialExplainInput input = baseInput(List.of("structure"));
        FinancialExplainOutput out = output(null, null,
                "자본총계는 자본금보다 많아요. 부채총계는 자산총계보다 적어요. 부채비율은 전기말보다 낮아졌어요.", null);

        assertThat(validator.validate(out, input).failedRules()).doesNotContain("10");
    }

    @Test
    void realSentencesWithComparativeParticleStillCountAgainstLimit() {
        FinancialExplainInput input = baseInput(List.of("structure"));
        FinancialExplainOutput out = output(null, null,
                "자본총계는 자본금보다 많아요. 부채총계는 자산총계보다 적어요. 부채비율은 전기말보다 낮아졌어요. "
                        + "자산총계는 부채총계보다 커요.", null);

        assertThat(validator.validate(out, input).failedRules()).contains("10");
    }

    @Test
    void historyWithGeubodaIsCountedByRealSentences() {
        // D-52 경계 예시와 같은 형태: "그보다 앞선 …"이 두 번 들어가도 실제 문장은 3개다.
        FinancialExplainInput input = baseInput(List.of("overview", "history"));
        FinancialExplainOutput out = output("{per.2026-01.Q2} 기준 변화를 정리했어요.", null, null,
                "지난 {per.2026-01.Q1}에는 {sig.S2} 신호가 있었어요. 그보다 앞선 시기에도 신호가 있었어요. "
                        + "그보다 앞선 시기에는 다른 신호도 있었어요.");

        assertThat(validator.validate(out, input).failedRules()).doesNotContain("10");
    }

    @Test
    void rule5PassesWhenSignalAndIntensityWordShareSentenceWithComparative() {
        FinancialExplainInput input = baseInput(List.of("overview"));
        FinancialExplainOutput out = output("{sig.S1} 매출이 전년보다 크게 늘었어요.", null, null, null);

        assertThat(validator.validate(out, input).failedRules()).doesNotContain("5");
    }

    @Test
    void rule6CatchesContradictionAcrossComparativeParticle() {
        // 분리 오류가 있으면 변화 토큰과 증감 어휘가 다른 조각으로 갈라져 방향 불일치를 놓친다.
        FinancialExplainInput input = baseInput(List.of("overview"));
        FinancialExplainOutput out = output("매출은 {fin.revenue_yoy.2026-01.Q2}로 전년보다 줄었어요.", null, null, null);

        assertThat(validator.validate(out, input).failedRules()).contains("6");
    }

    @Test
    void nounsEndingInYoDoNotSplitSentence() {
        FinancialExplainInput input = baseInput(List.of("overview"));
        FinancialExplainOutput out = output(
                "주요 지표인 매출은 {fin.revenue.2026-01.Q2}예요. 중요 항목은 더 설명할 필요 없는 수준이에요.", null, null, null);

        assertThat(validator.validate(out, input).failedRules()).doesNotContain("10");
    }

    @Test
    void sentencesWithoutPeriodAreStillSplitAtYoEnding() {
        FinancialExplainInput input = baseInput(List.of("overview"));
        FinancialExplainOutput out = output("첫 문장이에요 둘째 문장이에요 셋째 문장이에요", null, null, null);

        assertThat(validator.validate(out, input).failedRules()).contains("10");
    }

    // ---- D-54: 섹션 배정 계약 ----

    private static final FinancialExplainInput.Fact DEBT_RATIO = new FinancialExplainInput.Fact(
            "fin.debt_ratio.2026-01.Q2", "부채비율", "+", "2026-01.Q2");
    private static final FinancialExplainInput.Fact DEBT_RATIO_DIFF = new FinancialExplainInput.Fact(
            "fin.debt_ratio_diff.2026-01.Q2", "부채비율 변화(전기말 대비)", "+", "2026-01.Q2");
    private static final FinancialExplainInput.Fact TOTAL_EQUITY = new FinancialExplainInput.Fact(
            "fin.total_equity.2026-01.Q2", "자본총계", "+", "2026-01.Q2");
    private static final FinancialExplainInput.Fact CAPITAL_STOCK = new FinancialExplainInput.Fact(
            "fin.capital_stock.2026-01.Q2", "자본금", "+", "2026-01.Q2");

    /**
     * 716형 입력: 활성 흑자 전환(손익)·활성 자본잠식(재무 구조, 발생 기간은 과거지만 설명 기준 기간은 최신)·이력 적자 지속.
     * 개요는 매출 증가율, structure는 부채비율·변화량·자본총계·자본금(자본잠식이 있을 때만)으로 배정된다.
     */
    private static FinancialExplainInput d54Input() {
        FinancialExplainInput base = baseInput(List.of("overview", "sales_profit", "structure", "history"));
        return new FinancialExplainInput(base.company(), base.latest(), "CHANGED", base.sections(), base.periods(),
                List.of(REVENUE, REVENUE_YOY, OPERATING_INCOME, OPERATING_MARGIN, DEBT_RATIO, DEBT_RATIO_DIFF,
                        TOTAL_EQUITY, CAPITAL_STOCK),
                List.of(new FinancialExplainInput.SignalRef("S1", "FIN_REVENUE_CHANGE", "ACTIVE", "CHANGE",
                                "POSITIVE", "HIGH", "2026-01.Q2", "sales_profit", null,
                                List.of("fin.revenue.2026-01.Q2", "fin.revenue_yoy.2026-01.Q2")),
                        new FinancialExplainInput.SignalRef("S2", "FIN_CAPITAL_IMPAIRMENT", "ACTIVE", "STATE",
                                "NEGATIVE", "MEDIUM", "2026-01.Q2", "structure", null,
                                List.of("fin.total_equity.2026-01.Q2", "fin.capital_stock.2026-01.Q2")),
                        new FinancialExplainInput.SignalRef("S3", "FIN_OPERATING_LOSS_STREAK", "PAST", "STATE",
                                "NEGATIVE", "MEDIUM", "2026-01.Q1", "history", null, List.of())),
                List.of(new FinancialExplainInput.Group("sales_profit", "2026-01.Q2", "SALES_PROFIT", List.of("S1"), false),
                        new FinancialExplainInput.Group("structure", "2026-01.Q2", "STRUCTURE", List.of("S2"), false),
                        new FinancialExplainInput.Group("history", "2026-01.Q1", "SALES_PROFIT", List.of("S3"), false)),
                java.util.Map.of("overview", List.of("fin.revenue_yoy.2026-01.Q2"),
                        "structure", List.of("fin.debt_ratio.2026-01.Q2", "fin.debt_ratio_diff.2026-01.Q2",
                                "fin.total_equity.2026-01.Q2", "fin.capital_stock.2026-01.Q2")),
                List.of(), List.of());
    }

    @Test
    void d54ContractOutputPasses() {
        FinancialExplainOutput out = output(
                "{per.2026-01.Q2} 기준 {sig.S1} 신호가 있어요. 매출이 전년 같은 분기보다 {fin.revenue_yoy.2026-01.Q2} 늘었어요.",
                "매출은 {fin.revenue.2026-01.Q2}, 영업이익은 {fin.operating_income.2026-01.Q2}이었어요.",
                "부채비율은 {fin.debt_ratio.2026-01.Q2}로 전기말보다 {fin.debt_ratio_diff.2026-01.Q2} 높아졌어요. "
                        + "{sig.S2} 자본총계는 {fin.total_equity.2026-01.Q2}로 자본금 {fin.capital_stock.2026-01.Q2}보다 적어요.",
                "지난 {per.2026-01.Q1}에는 {sig.S3} 신호가 있었어요.");

        FinancialExplainValidator.ValidationResult result = validator.validate(out, d54Input());

        assertThat(result.failedRules()).isEmpty();
    }

    @Test
    void rule6FailsWhenChangeFactIsWrittenAsLevel() {
        // D-46 예시 "영업이익률은 {…_diff} 수준이에요"(608·1640 통과본, implementation-plan.md §7.4.21 E): 증감 어휘가
        // 없어 부호와 대조할 말이 없던 문장. D-54 뒤로 변화량 토큰에는 같은 부호의 증감 어휘가 필요하다.
        // (참고: "매출 증가율은 {…}예요"는 사실 이름의 "증가"가 증감 어휘로 세어져 통과한다 — 증가율을 값으로 말하는
        // 것은 사실과 맞는 문장이라 막을 대상이 아니다. 문제는 수준 지표 이름에 변화량을 붙이는 경우다.)
        FinancialExplainInput base = baseInput(List.of("overview"));
        FinancialExplainInput input = new FinancialExplainInput(base.company(), base.latest(), base.changeStatus(),
                base.sections(), base.periods(),
                List.of(REVENUE, REVENUE_YOY, OPERATING_INCOME, OPERATING_MARGIN, OPERATING_MARGIN_DIFF),
                base.signals(), base.groups(), base.sectionFacts(), base.unavailable(), base.doNotMention());
        FinancialExplainOutput out = output(
                "{per.2026-01.Q2} 기준 큰 변화는 없어요. 영업이익률은 {fin.operating_margin_diff.2026-01.Q2} 수준이에요.",
                null, null, null);

        assertThat(validator.validate(out, input).failedRules()).contains("6");
    }

    @Test
    void rule6MixedSignSentenceNeedsBothDirections() {
        FinancialExplainInput base = baseInput(List.of("overview"));
        FinancialExplainInput input = new FinancialExplainInput(base.company(), base.latest(), base.changeStatus(),
                base.sections(), base.periods(),
                List.of(REVENUE, REVENUE_YOY, OPERATING_INCOME, OPERATING_MARGIN, OPERATING_MARGIN_DIFF),
                base.signals(), base.groups(), base.sectionFacts(), base.unavailable(), base.doNotMention());
        FinancialExplainOutput onlyOneDirection = output(
                "매출이 {fin.revenue_yoy.2026-01.Q2} 늘었고 영업이익률은 {fin.operating_margin_diff.2026-01.Q2} 변했어요.",
                null, null, null);

        assertThat(validator.validate(onlyOneDirection, input).failedRules()).contains("6");
    }

    @Test
    void rule7FailsWhenActiveSignalLeavesItsSection() {
        // 활성 자본잠식(S2)의 section은 structure다. 발생 기간이 과거라고 history에 쓰면(716·737·908형) 실패하고,
        // 손익 섹션에 써도 실패한다.
        FinancialExplainInput input = d54Input();

        assertThat(validator.validate(output("{per.2026-01.Q2} 기준이에요.", "매출은 {fin.revenue.2026-01.Q2}이었어요.",
                "부채비율은 {fin.debt_ratio.2026-01.Q2}예요.", "지난 {per.2026-01.Q1}에는 {sig.S3} 신호와 {sig.S2} 신호가 있었어요."),
                input).failedRules()).contains("7");
        assertThat(validator.validate(output("{per.2026-01.Q2} 기준이에요.", "{sig.S2} 매출은 {fin.revenue.2026-01.Q2}이었어요.",
                "부채비율은 {fin.debt_ratio.2026-01.Q2}예요.", "지난 {per.2026-01.Q1}에는 {sig.S3} 신호가 있었어요."),
                input).failedRules()).contains("7");
    }

    @Test
    void rule7AllowsActiveSignalInOverviewButNotPastSignal() {
        FinancialExplainInput input = d54Input();
        FinancialExplainOutput activeInOverview = output("{per.2026-01.Q2} 기준 {sig.S2} 신호가 있어요.",
                "매출은 {fin.revenue.2026-01.Q2}이었어요.", "{sig.S2} 부채비율은 {fin.debt_ratio.2026-01.Q2}예요.",
                "지난 {per.2026-01.Q1}에는 {sig.S3} 신호가 있었어요.");
        FinancialExplainOutput pastInOverview = output("{per.2026-01.Q2} 기준 {sig.S3} 신호가 있어요.",
                "매출은 {fin.revenue.2026-01.Q2}이었어요.", "부채비율은 {fin.debt_ratio.2026-01.Q2}예요.",
                "지난 {per.2026-01.Q1}에는 {sig.S3} 신호가 있었어요.");

        assertThat(validator.validate(activeInOverview, input).failedRules()).doesNotContain("7");
        assertThat(validator.validate(pastInOverview, input).failedRules()).contains("7");
    }

    @Test
    void rule11FailsWhenOverviewUsesUnassignedFact() {
        // 1016형: 개요가 지정되지 않은 원값(net_income 등)을 쓰면 실패한다(D-46의 "겹치지 않는 원값" 경로 삭제).
        FinancialExplainOutput out = output("{per.2026-01.Q2} 기준 매출은 {fin.revenue.2026-01.Q2}이에요.",
                "영업이익은 {fin.operating_income.2026-01.Q2}이었어요.", "부채비율은 {fin.debt_ratio.2026-01.Q2}예요.",
                "지난 {per.2026-01.Q1}에는 {sig.S3} 신호가 있었어요.");

        assertThat(validator.validate(out, d54Input()).failedRules()).contains("11");
    }

    @Test
    void rule11FailsWhenStructureUsesUnassignedFact() {
        FinancialExplainOutput out = output("{per.2026-01.Q2} 기준이에요.", "매출은 {fin.revenue.2026-01.Q2}이었어요.",
                "부채비율은 {fin.debt_ratio.2026-01.Q2}이고 영업이익률은 {fin.operating_margin.2026-01.Q2}예요.",
                "지난 {per.2026-01.Q1}에는 {sig.S3} 신호가 있었어요.");

        assertThat(validator.validate(out, d54Input()).failedRules()).contains("11");
    }

    @Test
    void rule11DoesNotRestrictSectionsWithoutAssignment() {
        // sales_profit·history는 배정 목록이 없어 사실표의 어떤 사실이든 쓸 수 있다(D-54 범위: 개요·structure).
        // 개요는 배정된 사실(revenue_yoy)을 그대로 써서 D-57 완전성은 걸리지 않는다.
        FinancialExplainOutput out = output(
                "{per.2026-01.Q2} 기준 매출이 전년 같은 분기보다 {fin.revenue_yoy.2026-01.Q2} 늘었어요.",
                "매출은 {fin.revenue.2026-01.Q2}, 영업이익률은 {fin.operating_margin.2026-01.Q2}이었어요.",
                "부채비율은 {fin.debt_ratio.2026-01.Q2}예요.", "지난 {per.2026-01.Q1}에는 {sig.S3} 신호가 있었어요.");

        assertThat(validator.validate(out, d54Input()).failedRules()).doesNotContain("11");
    }

    // ---- D-57: 개요 완전성(규칙 11 확장). sales_profit·structure는 예외로 남는다 ----

    /** {@code input}과 같되 {@code sectionFacts.overview}만 바꾼 입력(872형: 첫 묶음에 신호가 둘). */
    private static FinancialExplainInput withOverviewFacts(FinancialExplainInput input, List<String> overviewFacts) {
        java.util.Map<String, List<String>> sectionFacts = new java.util.LinkedHashMap<>(input.sectionFacts());
        sectionFacts.put("overview", overviewFacts);
        return new FinancialExplainInput(input.company(), input.latest(), input.changeStatus(), input.sections(),
                input.periods(), input.facts(), input.signals(), input.groups(), sectionFacts, input.relations(),
                input.unavailable(), input.doNotMention());
    }

    @Test
    void rule11FailsWhenOverviewDropsAnAssignedFact() {
        // 872: 첫 묶음에 매출 증가·영업이익률 변화 신호가 함께 있어 개요에 사실 둘이 배정됐는데, 하나만 쓰고 버렸다.
        FinancialExplainInput input = withOverviewFacts(withPrior("UP"),
                List.of("fin.revenue_yoy." + Q2, "fin.operating_margin_diff." + Q2));
        FinancialExplainOutput onlyOne = output(
                "{per.2026-01.Q2} 기준 {sig.S1} 신호가 있어요. 매출이 전년 같은 분기보다 {fin.revenue_yoy.2026-01.Q2} 늘었어요.",
                "매출은 {fin.revenue.2026-01.Q2}, 영업이익은 {fin.operating_income.2026-01.Q2}이었어요.",
                "부채비율은 {fin.debt_ratio.2026-01.Q2}로 지난 회계연도 말보다 {fin.debt_ratio_diff.2026-01.Q2} 높아졌어요. "
                        + "{sig.S5} 자본총계는 {fin.total_equity.2026-01.Q2}로 자본금 {fin.capital_stock.2026-01.Q2}보다 적어요.",
                null);

        assertThat(validator.validate(onlyOne, input).failedRules()).contains("11");
    }

    @Test
    void rule11PassesWhenOverviewUsesEveryAssignedFact() {
        FinancialExplainInput input = withOverviewFacts(withPrior("UP"),
                List.of("fin.revenue_yoy." + Q2, "fin.operating_margin_diff." + Q2));
        FinancialExplainOutput both = output(
                "{per.2026-01.Q2} 기준 {sig.S1} 신호와 {sig.S4} 신호가 있어요. 매출은 전년 같은 분기보다 {fin.revenue_yoy.2026-01.Q2} "
                        + "늘었지만, 영업이익률은 {fin.operating_margin_diff.2026-01.Q2} 낮아졌어요.",
                "매출은 {fin.revenue.2026-01.Q2}, 영업이익은 {fin.operating_income.2026-01.Q2}이었어요.",
                "부채비율은 {fin.debt_ratio.2026-01.Q2}로 지난 회계연도 말보다 {fin.debt_ratio_diff.2026-01.Q2} 높아졌어요. "
                        + "{sig.S5} 자본총계는 {fin.total_equity.2026-01.Q2}로 자본금 {fin.capital_stock.2026-01.Q2}보다 적어요.",
                null);

        assertThat(validator.validate(both, input).failedRules()).doesNotContain("11");
    }

    @Test
    void rule11DoesNotRequireCompletenessForSalesProfitOrStructure() {
        // D-57은 완전성을 overview에만 적용한다. sales_profit·structure는 배정 안에서 일부만 써도 규칙 11에 걸리지 않는다
        // (D-54 보완이 준 자유 — 우선순위 목록에서 무엇을 어떻게 묶어 쓸지는 AI가 정한다).
        FinancialExplainInput input = withPrior("UP");
        String partialSalesProfit = "매출은 {fin.revenue.2026-01.Q2}이었어요.";
        FinancialExplainOutput out = salesProfit(partialSalesProfit);

        assertThat(validator.validate(out, input).failedRules()).doesNotContain("11");
    }

    @Test
    void legacyInputWithoutSectionFieldsUsesStatusRuleAndSkipsRule11() {
        // fx-input-2 이하로 저장된 입력(section·sectionFacts 없음)은 이전 판정을 그대로 쓴다.
        FinancialExplainInput legacy = new FinancialExplainInput(
                baseInput(List.of("overview")).company(), baseInput(List.of("overview")).latest(), "CHANGED",
                List.of("overview", "history"), baseInput(List.of("overview")).periods(),
                List.of(REVENUE, REVENUE_YOY),
                List.of(new FinancialExplainInput.SignalRef("S1", "FIN_REVENUE_CHANGE", "ACTIVE", "CHANGE",
                        "POSITIVE", "HIGH", "2026-01.Q2", null, null, List.of())),
                List.of(), null, List.of(), List.of());

        assertThat(validator.validate(output("{sig.S1} 매출은 {fin.revenue.2026-01.Q2}이에요.", null, null,
                "지난 {per.2026-01.Q1}에 있었어요."), legacy).failedRules()).doesNotContain("7", "11");
        assertThat(validator.validate(output("{per.2026-01.Q2} 기준이에요.", null, null, "{sig.S1} 있었어요."), legacy)
                .failedRules()).contains("7");
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

    // ---- D-55: 관계 서술 계약 (implementation-plan.md §7.4.26·§7.4.27 점검표 8~13, 23, 24) ----

    private static final String Q2 = "2026-01.Q2";
    private static final FinancialExplainInput.Fact OPERATING_INCOME_PRIOR = new FinancialExplainInput.Fact(
            "fin.operating_income_prior." + Q2, "영업이익(전년 동기)", "+", Q2);
    private static final FinancialExplainInput.Fact NET_INCOME = new FinancialExplainInput.Fact(
            "fin.net_income." + Q2, "당기순이익", "+", Q2);
    private static final FinancialExplainInput.Fact NET_INCOME_TURN = new FinancialExplainInput.Fact(
            "fin.net_income_turn." + Q2, "당기순이익 흑자·적자 전환(전년 같은 기간 대비)", "+", Q2);
    private static final FinancialExplainInput.Fact NET_INCOME_STATUS = new FinancialExplainInput.Fact(
            "fin.net_income_status." + Q2, "당기순이익 흑자·적자", "+", Q2);
    private static final FinancialExplainInput.Fact OPERATING_INCOME_TURN = new FinancialExplainInput.Fact(
            "fin.operating_income_turn." + Q2, "영업이익 흑자·적자 전환(전년 같은 기간 대비)", "+", Q2);
    private static final FinancialExplainInput.Fact REVENUE_RUN_DOWN = new FinancialExplainInput.Fact(
            "fin.revenue_yoy_run." + Q2, "매출 같은 방향 연속 분기 수", "-", Q2);

    /**
     * fx-input-4 모양의 입력: 네 섹션 모두 배정, 관계 항목 포함. S1 매출 증가(활성), S3 부채비율 급등(활성, NEGATIVE),
     * S4 흑자 전환(활성), S5 자본잠식(활성), 이력 S2(2026 Q1)·S6(2025 Q3).
     */
    private static FinancialExplainInput d55Input(List<FinancialExplainInput.Fact> extraFacts, List<String> salesProfitFacts,
            List<FinancialExplainInput.Relation> relations) {
        List<FinancialExplainInput.Fact> facts = new java.util.ArrayList<>(List.of(REVENUE, REVENUE_YOY, OPERATING_INCOME,
                OPERATING_MARGIN, DEBT_RATIO, DEBT_RATIO_DIFF, TOTAL_EQUITY, CAPITAL_STOCK));
        facts.addAll(extraFacts);
        return new FinancialExplainInput(
                new FinancialExplainInput.Company("삼성전자", "GENERAL", "KRW", "CFS", 12),
                new FinancialExplainInput.Latest(Q2, "QUARTER"), "CHANGED",
                List.of("overview", "sales_profit", "structure", "history"),
                List.of(new FinancialExplainInput.PeriodLabel(Q2, "QUARTER"),
                        new FinancialExplainInput.PeriodLabel("2026-01.Q1", "QUARTER"),
                        new FinancialExplainInput.PeriodLabel("2025-01.Q3", "QUARTER")),
                facts,
                List.of(new FinancialExplainInput.SignalRef("S1", "FIN_REVENUE_CHANGE", "ACTIVE", "CHANGE", "POSITIVE",
                                "HIGH", Q2, "sales_profit", null, List.of("fin.revenue." + Q2, "fin.revenue_yoy." + Q2)),
                        new FinancialExplainInput.SignalRef("S2", "FIN_REVENUE_CHANGE", "PAST", "CHANGE", "NEGATIVE",
                                "LOW", "2026-01.Q1", "history", null, List.of()),
                        new FinancialExplainInput.SignalRef("S3", "FIN_DEBT_RATIO_JUMP", "ACTIVE", "CHANGE", "NEGATIVE",
                                "MEDIUM", Q2, "structure", null, List.of("fin.debt_ratio." + Q2, "fin.debt_ratio_diff." + Q2)),
                        new FinancialExplainInput.SignalRef("S4", "FIN_OPERATING_TURN", "ACTIVE", "CHANGE", "POSITIVE",
                                "MEDIUM", Q2, "sales_profit", null, List.of()),
                        new FinancialExplainInput.SignalRef("S5", "FIN_CAPITAL_IMPAIRMENT", "ACTIVE", "STATE", "NEGATIVE",
                                "MEDIUM", Q2, "structure", null, List.of("fin.total_equity." + Q2, "fin.capital_stock." + Q2)),
                        new FinancialExplainInput.SignalRef("S6", "FIN_OPERATING_MARGIN_CHANGE", "PAST", "CHANGE", "NEGATIVE",
                                "HIGH", "2025-01.Q3", "history", null, List.of())),
                List.of(new FinancialExplainInput.Group("sales_profit", Q2, "SALES_PROFIT", List.of("S4", "S1"), false),
                        new FinancialExplainInput.Group("structure", Q2, "STRUCTURE", List.of("S3", "S5"), false),
                        new FinancialExplainInput.Group("history", "2026-01.Q1", "SALES_PROFIT", List.of("S2"), false),
                        new FinancialExplainInput.Group("history", "2025-01.Q3", "SALES_PROFIT", List.of("S6"), false)),
                java.util.Map.of("overview", List.of("fin.revenue_yoy." + Q2),
                        "sales_profit", salesProfitFacts,
                        "structure", List.of("fin.debt_ratio." + Q2, "fin.debt_ratio_diff." + Q2,
                                "fin.total_equity." + Q2, "fin.capital_stock." + Q2),
                        "history", List.of()),
                relations,
                List.of(), List.of());
    }

    private static final List<String> SP_WITH_PRIOR = List.of("fin.revenue." + Q2, "fin.operating_income." + Q2,
            "fin.operating_income_prior." + Q2, "fin.net_income." + Q2, "fin.net_income_turn." + Q2, "fin.operating_margin." + Q2);

    private static FinancialExplainInput.Relation direction(String value) {
        return new FinancialExplainInput.Relation(FinancialExplainInput.Relation.OPERATING_INCOME_DIRECTION,
                List.of("fin.operating_income." + Q2, "fin.operating_income_prior." + Q2), value);
    }

    private static FinancialExplainInput withPrior(String directionValue) {
        return d55Input(List.of(OPERATING_INCOME_PRIOR, NET_INCOME, NET_INCOME_TURN), SP_WITH_PRIOR,
                List.of(direction(directionValue)));
    }

    /** 문장 하나를 sales_profit에 두고 나머지 섹션은 계약에 맞게 채운 출력. */
    private static FinancialExplainOutput salesProfit(String sentence) {
        return output("{per.2026-01.Q2} 기준 {sig.S1} 신호가 있어요. 매출이 전년 같은 분기보다 {fin.revenue_yoy.2026-01.Q2} 늘었어요.",
                sentence,
                "부채비율은 {fin.debt_ratio.2026-01.Q2}로 전기말보다 {fin.debt_ratio_diff.2026-01.Q2} 높아졌어요. "
                        + "{sig.S5} 자본총계는 {fin.total_equity.2026-01.Q2}로 자본금 {fin.capital_stock.2026-01.Q2}보다 적어요.",
                "지난 {per.2026-01.Q1}에는 {sig.S2} 신호가 있었어요. 그보다 앞선 {per.2025-01.Q3}에는 {sig.S6} 신호가 있었어요.");
    }

    @Test
    void d55ContractOutputPasses() {
        FinancialExplainOutput out = salesProfit(
                "매출은 {fin.revenue.2026-01.Q2}, 영업이익률은 {fin.operating_margin.2026-01.Q2}이었어요. "
                        + "영업이익은 {fin.operating_income.2026-01.Q2}로 전년 같은 분기 {fin.operating_income_prior.2026-01.Q2}보다 늘었어요. "
                        + "당기순이익은 {fin.net_income.2026-01.Q2}로 전년 같은 분기와 달리 {fin.net_income_turn.2026-01.Q2} 바뀌었어요.");

        assertThat(validator.validate(out, withPrior("UP")).failedRules()).isEmpty();
    }

    // 규칙 6: 증감 근거 확장 (점검표 8)

    @Test
    void rule6ChecksSignalOnlySentenceByArithmeticTable() {
        // 신호만 있고 변화 토큰이 없는 문장: 매출 증가(POSITIVE) 배지 옆 "줄었어요"는 이전에는 대조되지 않았다(§7.4.19).
        assertThat(validator.validate(salesProfit("매출은 {fin.revenue.2026-01.Q2}, 영업이익은 {fin.operating_income.2026-01.Q2}"
                + "이었어요. {sig.S1} 매출이 줄었어요."), withPrior("UP")).failedRules()).contains("6");
    }

    @Test
    void rule6DebtRatioJumpIsNegativeButMeansIncrease() {
        // 부채비율 급등은 좋고 나쁨으로는 NEGATIVE지만 산술 방향은 높아짐이다(대응표). "높아졌어요"는 맞고 "낮아졌어요"는 틀리다.
        FinancialExplainInput input = withPrior("UP");
        FinancialExplainOutput up = output("{per.2026-01.Q2} 기준 {sig.S3} 부채비율이 높아졌어요.",
                "매출은 {fin.revenue.2026-01.Q2}이었어요.", "부채비율은 {fin.debt_ratio.2026-01.Q2}예요.", "지난 {per.2026-01.Q1}에는 {sig.S2} 신호가 있었어요.");
        FinancialExplainOutput down = output("{per.2026-01.Q2} 기준 {sig.S3} 부채비율이 낮아졌어요.",
                "매출은 {fin.revenue.2026-01.Q2}이었어요.", "부채비율은 {fin.debt_ratio.2026-01.Q2}예요.", "지난 {per.2026-01.Q1}에는 {sig.S2} 신호가 있었어요.");

        assertThat(validator.validate(up, input).failedRules()).doesNotContain("6", "13");
        assertThat(validator.validate(down, input).failedRules()).contains("6");
    }

    @Test
    void rule6ChecksRevenueRunSign() {
        FinancialExplainInput input = d55Input(List.of(REVENUE_RUN_DOWN), List.of("fin.revenue." + Q2,
                "fin.operating_income." + Q2, "fin.revenue_yoy_run." + Q2), List.of());
        String prefix = "매출은 {fin.revenue.2026-01.Q2}, 영업이익은 {fin.operating_income.2026-01.Q2}이었어요. ";

        assertThat(validator.validate(salesProfit(prefix + "매출 감소가 {fin.revenue_yoy_run.2026-01.Q2}째 이어지고 있어요."), input)
                .failedRules()).isEmpty();
        assertThat(validator.validate(salesProfit(prefix + "매출 증가가 {fin.revenue_yoy_run.2026-01.Q2}째 이어지고 있어요."), input)
                .failedRules()).contains("6");
    }

    @Test
    void rule6FollowsRelationValueNotDisplayValues() {
        // 점검표 13: 검증기는 관계 방향을 표시 문자열(5.0억원 vs 3.0억원)에서 다시 계산하지 않는다. 입력의 관계가 DOWN이면
        // "늘었어요"는 실패, "줄었어요"는 통과다.
        String sentence = "매출은 {fin.revenue.2026-01.Q2}이었어요. 영업이익은 {fin.operating_income.2026-01.Q2}로 전년 같은 분기 "
                + "{fin.operating_income_prior.2026-01.Q2}보다 %s. 당기순이익은 {fin.net_income.2026-01.Q2}로 {fin.net_income_turn.2026-01.Q2} 바뀌었어요.";

        assertThat(validator.validate(salesProfit(sentence.formatted("늘었어요")), withPrior("DOWN")).failedRules()).contains("6");
        assertThat(validator.validate(salesProfit(sentence.formatted("줄었어요")), withPrior("DOWN")).failedRules()).isEmpty();
    }

    @Test
    void rule6SameDirectionRelationForbidsDirectionWords() {
        String sentence = "매출은 {fin.revenue.2026-01.Q2}이었어요. 영업이익은 {fin.operating_income.2026-01.Q2}로 전년 같은 분기 "
                + "{fin.operating_income_prior.2026-01.Q2}보다 늘었어요.";

        assertThat(validator.validate(salesProfit(sentence), withPrior("SAME")).failedRules()).contains("6");
    }

    @Test
    void rule6EvaluativeWordsNeedSignalWithSameDirection() {
        FinancialExplainInput input = withPrior("UP");
        String prefix = "매출은 {fin.revenue.2026-01.Q2}, 영업이익은 {fin.operating_income.2026-01.Q2}이었어요. ";

        assertThat(validator.validate(salesProfit(prefix + "{sig.S1} 매출 흐름이 좋아졌어요."), input).failedRules()).doesNotContain("6");
        assertThat(validator.validate(salesProfit(prefix + "영업이익률이 개선됐어요."), input).failedRules()).contains("6");
        // 부채비율 급등(NEGATIVE) 배지 옆 "개선"은 방향이 맞지 않는다(평가어의 뜻은 신호에만 있다).
        FinancialExplainOutput badStructure = output(
                "{per.2026-01.Q2} 기준 {sig.S1} 신호가 있어요. 매출이 전년 같은 분기보다 {fin.revenue_yoy.2026-01.Q2} 늘었어요.",
                prefix.strip(), "{sig.S3} 부채비율 흐름이 개선됐어요.", "지난 {per.2026-01.Q1}에는 {sig.S2} 신호가 있었어요.");
        assertThat(validator.validate(badStructure, input).failedRules()).contains("6");
    }

    @Test
    void turnSignalWithCorrectTransitionSentenceIsNoLongerFalsePositive() {
        // 716 오탐(§7.4.19 5, fx-v6): 흑자 전환 배지와 "적자에서 흑자로" 설명이 한 문장이면 이전 규칙 6이 반대 단어로 읽었다.
        // 이제 전환은 전환 사실 토큰이 보여 주고, 전환 신호의 단어 검사는 없다.
        FinancialExplainInput input = d55Input(List.of(OPERATING_INCOME_TURN), List.of("fin.revenue." + Q2,
                "fin.operating_income." + Q2, "fin.operating_income_turn." + Q2), List.of());

        assertThat(validator.validate(salesProfit("매출은 {fin.revenue.2026-01.Q2}이었어요. {sig.S4} 영업이익은 "
                + "{fin.operating_income.2026-01.Q2}로 {fin.operating_income_turn.2026-01.Q2} 바뀌었어요."), input).failedRules()).isEmpty();
    }

    // 규칙 12: 흑자·적자 (점검표 9, 23, 24)

    @Test
    void rule12ForbidsStateWordsOutsideTokens() {
        FinancialExplainInput input = withPrior("UP");
        String prefix = "매출은 {fin.revenue.2026-01.Q2}, 영업이익은 {fin.operating_income.2026-01.Q2}이었어요. ";

        assertThat(validator.validate(salesProfit(prefix + "당기순이익은 {fin.net_income.2026-01.Q2}로 흑자였어요."), input)
                .failedRules()).contains("12");
        // 최종 점검(§7.4.27): 908의 "…손실을 기록했어요"에서 비교 부분이 빠져도 상태 서술이 빠져나가지 않는다.
        assertThat(validator.validate(salesProfit(prefix + "당기순이익은 {fin.net_income.2026-01.Q2}로 손실을 기록했어요."), input)
                .failedRules()).contains("12");
        assertThat(validator.validate(salesProfit(prefix + "당기순이익은 {fin.net_income.2026-01.Q2}로 손해를 봤어요."), input)
                .failedRules()).contains("12");
    }

    @Test
    void rule12AllowsYeongeopjeokjaOnlyWithLossRunToken() {
        FinancialExplainInput input = d55Input(List.of(OPERATING_LOSS_RUN), List.of("fin.revenue." + Q2,
                "fin.operating_income." + Q2, "fin.operating_loss_run." + Q2), List.of());
        String prefix = "매출은 {fin.revenue.2026-01.Q2}, 영업이익은 {fin.operating_income.2026-01.Q2}이었어요. ";

        assertThat(validator.validate(salesProfit(prefix + "영업적자가 {fin.operating_loss_run.2026-01.Q2}째 이어지고 있어요."), input)
                .failedRules()).isEmpty();
        assertThat(validator.validate(salesProfit(prefix + "영업적자가 이어지고 있어요."), input).failedRules()).contains("12");
    }

    @Test
    void rule12TurnPredicateNeedsTurnTokenAndCannotContinue() {
        FinancialExplainInput input = withPrior("UP");
        String prefix = "매출은 {fin.revenue.2026-01.Q2}, 영업이익은 {fin.operating_income.2026-01.Q2}이었어요. ";

        assertThat(validator.validate(salesProfit(prefix + "당기순이익은 {fin.net_income.2026-01.Q2}로 부호가 바뀌었어요."), input)
                .failedRules()).contains("12");
        assertThat(validator.validate(salesProfit(prefix + "당기순이익은 {fin.net_income.2026-01.Q2}로 "
                + "{fin.net_income_turn.2026-01.Q2} 바뀐 흐름이 이어지고 있어요."), input).failedRules()).contains("12");
    }

    // 규칙 13: 코드가 주지 않은 관계 (점검표 10)

    @Test
    void rule13AlwaysForbidsRelationsCodeNeverGives() {
        FinancialExplainInput input = withPrior("UP");
        String prefix = "매출은 {fin.revenue.2026-01.Q2}, 영업이익은 {fin.operating_income.2026-01.Q2}이었어요. ";

        // 2715 통과본의 두 문장: 근거(두 사실 짝)가 같은 문장에 있어도 늘 실패다.
        assertThat(validator.validate(salesProfit(prefix + "전년 같은 분기 {fin.operating_income_prior.2026-01.Q2}와 비슷한 수준이었어요."),
                input).failedRules()).contains("13");
        assertThat(validator.validate(salesProfit(prefix + "전년 같은 분기 {fin.operating_income_prior.2026-01.Q2}보다 차이 폭이 줄었어요."),
                input).failedRules()).contains("13");
    }

    @Test
    void rule13ComparisonNeedsItsOwnKindOfEvidence() {
        FinancialExplainInput input = withPrior("UP");
        String prefix = "매출은 {fin.revenue.2026-01.Q2}, 영업이익은 {fin.operating_income.2026-01.Q2}이었어요. ";

        // 908형: 서로 다른 지표 사이의 크기 비교
        assertThat(validator.validate(salesProfit(prefix + "당기순이익은 {fin.net_income.2026-01.Q2}로 영업이익보다 작아요."), input)
                .failedRules()).contains("13");
        // 자본 비교는 자본잠식 배지(R6)와 같은 문장이어야 한다(통과본 716·908·1640은 모두 같은 문장이었다).
        FinancialExplainOutput withoutBadge = output(
                "{per.2026-01.Q2} 기준 {sig.S1} 신호가 있어요. 매출이 전년 같은 분기보다 {fin.revenue_yoy.2026-01.Q2} 늘었어요.",
                prefix.strip(), "{sig.S5} 신호가 있어요. 자본총계는 {fin.total_equity.2026-01.Q2}로 자본금 {fin.capital_stock.2026-01.Q2}보다 적어요.",
                "지난 {per.2026-01.Q1}에는 {sig.S2} 신호가 있었어요.");
        assertThat(validator.validate(withoutBadge, input).failedRules()).contains("13");
    }

    @Test
    void rule13ContrastNeedsTurnFactOrStateDifferenceRelation() {
        List<String> sp = List.of("fin.revenue." + Q2, "fin.operating_income." + Q2, "fin.net_income." + Q2,
                "fin.net_income_status." + Q2);
        FinancialExplainInput.Relation stateDifference = new FinancialExplainInput.Relation(
                FinancialExplainInput.Relation.STATE_DIFFERENCE, List.of("fin.net_income_status." + Q2, "fin.operating_income." + Q2),
                "DIFFERENT");
        String sentence = "매출은 {fin.revenue.2026-01.Q2}, 영업이익은 {fin.operating_income.2026-01.Q2}이었어요. "
                + "당기순이익은 {fin.net_income.2026-01.Q2}로 영업이익과 달리 {fin.net_income_status.2026-01.Q2}였어요.";

        assertThat(validator.validate(salesProfit(sentence), d55Input(List.of(NET_INCOME, NET_INCOME_STATUS), sp,
                List.of(stateDifference))).failedRules()).isEmpty();
        assertThat(validator.validate(salesProfit(sentence), d55Input(List.of(NET_INCOME, NET_INCOME_STATUS), sp, List.of()))
                .failedRules()).contains("13");
    }

    @Test
    void rule13DirectionWordNeedsEvidence() {
        // 수준 값만 있는 문장의 증감 어휘(D-55 이전 통과본의 "매출이 {fin.revenue…}으로 늘었어요" 형태)
        assertThat(validator.validate(salesProfit("매출은 {fin.revenue.2026-01.Q2}, 영업이익은 {fin.operating_income.2026-01.Q2}로 늘었어요."),
                withPrior("UP")).failedRules()).contains("13");
    }

    @Test
    void rule13DoesNotTreatTimeGeubodaAsComparison() {
        // history의 "그보다 앞선 {per}"는 시간 관계(R8)이지 크기 비교가 아니다.
        assertThat(validator.validate(salesProfit("매출은 {fin.revenue.2026-01.Q2}, 영업이익은 {fin.operating_income.2026-01.Q2}이었어요."),
                withPrior("UP")).failedRules()).doesNotContain("13");
    }

    // 규칙 7 확장: history 기간 순서와 시간 어휘 (점검표 11)

    @Test
    void rule7HistoryPeriodsMustFollowRecentToPastOrder() {
        FinancialExplainInput input = withPrior("UP");
        FinancialExplainOutput reversed = output(
                "{per.2026-01.Q2} 기준 {sig.S1} 신호가 있어요. 매출이 전년 같은 분기보다 {fin.revenue_yoy.2026-01.Q2} 늘었어요.",
                "매출은 {fin.revenue.2026-01.Q2}이었어요.", "부채비율은 {fin.debt_ratio.2026-01.Q2}예요.",
                "지난 {per.2025-01.Q3}에는 {sig.S6} 신호가 있었어요. 그보다 앞선 {per.2026-01.Q1}에는 {sig.S2} 신호가 있었어요.");

        assertThat(validator.validate(reversed, input).failedRules()).contains("7");
    }

    @Test
    void rule7TimeWordsOnlyInHistory() {
        assertThat(validator.validate(salesProfit("매출은 {fin.revenue.2026-01.Q2}, 영업이익은 {fin.operating_income.2026-01.Q2}으로 "
                + "이전과 같은 흐름이었어요."), withPrior("UP")).failedRules()).contains("7");
    }

    // 규칙 9: 내부 용어 노출 (fx-v7 재검증 §7.4.28, fxv-5)

    @Test
    void rule9ForbidsLeakingSectionAndFieldNames() {
        // fx-v7 통과본 2715의 실제 개요 문장. 입력 계약의 섹션 이름이 사용자 문장에 나왔다.
        FinancialExplainOutput leaked = output("{per.2026-01.Q2} 기준 이 기간 보고서 기준으로 규칙상 큰 변화로 표시된 항목은 없어요. "
                        + "영업이익 관련 사실은 sales_profit 섹션에서 확인할 수 있어요.",
                "매출은 {fin.revenue.2026-01.Q2}, 영업이익은 {fin.operating_income.2026-01.Q2}이었어요.",
                "부채비율은 {fin.debt_ratio.2026-01.Q2}예요.", "지난 {per.2026-01.Q1}에는 {sig.S2} 신호가 있었어요.");

        assertThat(validator.validate(leaked, withPrior("UP")).failedRules()).contains("9");
        // 토큰 키 안의 영문(예: fin.operating_income…)은 대상이 아니다.
        assertThat(validator.validate(salesProfit("매출은 {fin.revenue.2026-01.Q2}, 영업이익은 {fin.operating_income.2026-01.Q2}이었어요."),
                withPrior("UP")).failedRules()).doesNotContain("9");
    }

    // 규칙 11: 네 섹션 배정 (점검표 12)

    @Test
    void rule11AppliesToSalesProfitAndHistory() {
        FinancialExplainInput input = withPrior("UP");
        // sales_profit에 배정되지 않은 사실(개요의 변화량)을 쓰면 실패
        assertThat(validator.validate(salesProfit("매출은 {fin.revenue.2026-01.Q2}이었어요. {fin.revenue_yoy.2026-01.Q2} 늘었어요."),
                input).failedRules()).contains("11");
        // history에는 사실 토큰을 쓰지 않는다(배정이 빈 목록)
        FinancialExplainOutput historyFact = output(
                "{per.2026-01.Q2} 기준 {sig.S1} 신호가 있어요.", "매출은 {fin.revenue.2026-01.Q2}이었어요.",
                "부채비율은 {fin.debt_ratio.2026-01.Q2}예요.", "지난 {per.2026-01.Q1}에는 {sig.S2} 영업이익이 {fin.operating_income.2026-01.Q2}였어요.");
        assertThat(validator.validate(historyFact, input).failedRules()).contains("11");
    }

    // ---- D-56: 배지 위치(규칙 14), 흐름 사실과 기간 토큰(규칙 7) ----

    private static final List<String> SP_WITH_LOSS_RUN = List.of("fin.revenue." + Q2, "fin.operating_income." + Q2,
            "fin.operating_loss_run." + Q2);

    @Test
    void rule14FailsWhenBadgeSitsBetweenValueAndPredicate() {
        // fx-v10 737·908: "매출은 {…}로 [배지] 줄었어요"
        assertThat(validator.validate(salesProfit("매출은 {fin.revenue.2026-01.Q2}로 {sig.S1} 늘었어요."), withPrior("UP"))
                .failedRules()).contains("14");
        assertThat(validator.validate(salesProfit("{sig.S1} 매출은 {fin.revenue.2026-01.Q2}로 전년 같은 분기보다 늘었어요."), withPrior("UP"))
                .failedRules()).isEmpty();
    }

    @Test
    void rule14AllowsClauseStartSignalNounAndLeadingBadges() {
        FinancialExplainInput input = withPrior("UP");
        // D-55 R9: 두 지표를 한 문장에 쓰면 지표마다 자기 배지를 절의 맨 앞에 둔다.
        // D-58 확장: 앞머리 배지 뒤 같은 절에는 그 신호 지표의 사실 토큰이 있어야 한다(872형 "배지만" 방지) — 아래
        // 각 문장은 배지 뒤에 그 신호(S1=매출, S4=영업이익)의 사실을 바로 붙인다.
        assertThat(validator.validate(salesProfit("{sig.S1} 매출은 {fin.revenue.2026-01.Q2}로 늘었고 {sig.S4} 영업이익은 "
                + "{fin.operating_income.2026-01.Q2}였어요."), input).failedRules()).doesNotContain("14");
        assertThat(validator.validate(salesProfit("영업이익은 {fin.operating_income.2026-01.Q2}였어요, {sig.S1} 매출은 "
                + "{fin.revenue.2026-01.Q2}로 늘었어요."), input).failedRules()).doesNotContain("14");
        assertThat(validator.validate(salesProfit("이번에는 {sig.S1} 신호와 {sig.S4} 신호가 있어요."), input)
                .failedRules()).doesNotContain("14");
        // 기간 부사어 뒤는 절의 맨 앞이다.
        assertThat(validator.validate(salesProfit("{per.2026-01.Q2}에는 {sig.S1} 매출은 {fin.revenue.2026-01.Q2}로 늘었어요."), input)
                .failedRules()).doesNotContain("14");
    }

    // ---- D-58: 배지·지표 결합(규칙 14 확장), "로" 잇기 금지(규칙 13 확장), 절 단위 방향(규칙 6), 정도 어휘 ----

    @Test
    void rule14FailsWhenLeadingBadgeHasNoAttachedFamilyFactAndSectionHasThatFamilyAssigned() {
        // 872형: 배지만 쓰고 그 신호 지표의 사실을 다른 문장으로 미루면(§7.4.33), 그 섹션에 그 지표 묶음 사실이
        // 배정돼 있는 한(SP_WITH_PRIOR에 "revenue") 실패다.
        FinancialExplainInput input = withPrior("UP");
        assertThat(validator.validate(salesProfit("{sig.S1} 매출이 늘었어요."), input).failedRules()).contains("14");
    }

    @Test
    void rule14FailsWhenLeadingBadgeAttachesToADifferentMetric() {
        // 716형: 흑자 전환 배지(S4, 지표 묶음 operating_income*) 뒤에 영업이익률 사실이 오면 실패다.
        FinancialExplainInput input = withPrior("UP");
        assertThat(validator.validate(salesProfit("{sig.S4} 영업이익률은 {fin.operating_margin.2026-01.Q2}였어요."), input)
                .failedRules()).contains("14");
    }

    @Test
    void rule14SkipsMetricBindingWhenSectionHasNoFamilyFactAssigned() {
        // history에는 사실을 배정하지 않는다(빈 목록) — 이름 자리가 아닌 배지 뒤에 사실이 없어도 확장 대상이 아니다.
        FinancialExplainInput input = withPrior("UP");
        FinancialExplainOutput out = output(
                "{per.2026-01.Q2} 기준 {sig.S1} 신호가 있어요. 매출이 전년 같은 분기보다 {fin.revenue_yoy.2026-01.Q2} 늘었어요.",
                "영업이익은 {fin.operating_income.2026-01.Q2}로 전년 같은 분기 {fin.operating_income_prior.2026-01.Q2}보다 늘었어요.",
                "부채비율은 {fin.debt_ratio.2026-01.Q2}로 전기말보다 {fin.debt_ratio_diff.2026-01.Q2} 높아졌어요. "
                        + "{sig.S5} 자본총계는 {fin.total_equity.2026-01.Q2}로 자본금 {fin.capital_stock.2026-01.Q2}보다 적어요.",
                "지난 {per.2026-01.Q1}에는 {sig.S2} 매출이 줄었어요.");

        assertThat(validator.validate(out, input).failedRules()).doesNotContain("14");
    }

    @Test
    void rule13FailsWhenDifferentMetricsAreJoinedByRoButNotForSameMetricValueAndComparison() {
        FinancialExplainInput input = withPrior("UP");
        FinancialExplainOutput crossMetric = salesProfit(
                "영업이익은 {fin.operating_income.2026-01.Q2}로 영업이익률은 {fin.operating_margin.2026-01.Q2}였어요.");
        FinancialExplainOutput sameMetric = salesProfit(
                "영업이익은 {fin.operating_income.2026-01.Q2}로 전년 같은 분기 {fin.operating_income_prior.2026-01.Q2}보다 늘었어요.");

        assertThat(validator.validate(crossMetric, input).failedRules()).contains("13");
        assertThat(validator.validate(sameMetric, input).failedRules()).doesNotContain("13");
    }

    @Test
    void rule6PerClauseCatchesSwappedDirectionWordsInMixedSignSentence() {
        // 820형 검증기 틈(§7.4.33): 부호가 다른 변화량 둘이 한 문장에 있고 증감 어휘 둘 다 있으면, 어휘 자리가 서로
        // 바뀌어도 문장 전체 기준으로는 통과해 버렸다. 절 단위로 봐야 잡힌다.
        FinancialExplainInput base = baseInput(List.of("overview"));
        FinancialExplainInput input = new FinancialExplainInput(base.company(), base.latest(), base.changeStatus(),
                base.sections(), base.periods(),
                List.of(REVENUE, REVENUE_YOY, OPERATING_INCOME, OPERATING_MARGIN, OPERATING_MARGIN_DIFF),
                base.signals(), base.groups(), base.sectionFacts(), base.unavailable(), base.doNotMention());
        FinancialExplainOutput swapped = output(
                "매출은 전년 같은 분기보다 {fin.revenue_yoy.2026-01.Q2} 줄었지만, 영업이익률은 {fin.operating_margin_diff.2026-01.Q2} 높아졌어요.",
                null, null, null);
        FinancialExplainOutput correct = output(
                "매출은 전년 같은 분기보다 {fin.revenue_yoy.2026-01.Q2} 늘었지만, 영업이익률은 {fin.operating_margin_diff.2026-01.Q2} 낮아졌어요.",
                null, null, null);

        assertThat(validator.validate(swapped, input).failedRules()).contains("6");
        assertThat(validator.validate(correct, input).failedRules()).doesNotContain("6");
    }

    @Test
    void rule5PassesForDegreeWordWithSignalAndFailsWithout() {
        FinancialExplainInput input = baseInput(List.of("overview"));
        assertThat(validator.validate(output("매출이 많이 늘었어요.", null, null, null), input).failedRules()).contains("5");
        assertThat(validator.validate(output("{sig.S1} 매출이 많이 늘었어요.", null, null, null), input).failedRules())
                .doesNotContain("5");
    }

    @Test
    void rule13ForbidsDegreeReducingWords() {
        FinancialExplainInput input = baseInput(List.of("overview"));
        assertThat(validator.validate(output("매출이 조금 늘었어요.", null, null, null), input).failedRules()).contains("13");
        assertThat(validator.validate(output("매출이 약간 늘었어요.", null, null, null), input).failedRules()).contains("13");
        assertThat(validator.validate(output("매출이 살짝 늘었어요.", null, null, null), input).failedRules()).contains("13");
    }

    @Test
    void rule7ForbidsPeriodTokenInFlowFactSentence() {
        // 사업보고서 기간과 파생 4분기가 같은 기간 키라, 흐름 사실 문장의 기간 토큰은 분기 수에 회계연도 라벨을 붙인다(1640).
        FinancialExplainInput input = d55Input(List.of(OPERATING_LOSS_RUN), SP_WITH_LOSS_RUN, List.of());
        String prefix = "매출은 {fin.revenue.2026-01.Q2}, 영업이익은 {fin.operating_income.2026-01.Q2}이었어요. ";

        assertThat(validator.validate(salesProfit(prefix + "{per.2026-01.Q2} 영업적자가 {fin.operating_loss_run.2026-01.Q2}째 이어지고 있어요."),
                input).failedRules()).contains("7");
        assertThat(validator.validate(salesProfit(prefix + "분기로 세면 영업적자가 {fin.operating_loss_run.2026-01.Q2}째 이어지고 있어요."),
                input).failedRules()).isEmpty();
    }
}
