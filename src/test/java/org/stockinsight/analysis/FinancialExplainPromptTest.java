package org.stockinsight.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

/** 재시도 안내가 검증기 번호 대신 프롬프트 표현으로 위반 내용을 알려 주는지 확인한다(D-53). */
class FinancialExplainPromptTest {

    private static final List<String> ALL_FAILURE_CODES = List.of(
            "1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12", "13", "14", "LLM_OUTPUT_REJECTED", "INVALID_JSON");

    @Test
    void firstAttemptHasNoRetrySection() {
        String message = FinancialExplainPrompt.userMessage("{\"company\":{}}", null);

        assertThat(message).contains("<data>\n{\"company\":{}}\n</data>").doesNotContain("이전 시도");
    }

    @Test
    void retryDescribesViolatedRulesInPromptTerms() {
        String message = FinancialExplainPrompt.userMessage("{}", List.of("3", "10"));

        assertThat(message)
                .contains("이전 시도가 아래 규칙을 지키지 않았다")
                .contains("- " + FinancialExplainPrompt.retryGuidance("3"))
                .contains("- " + FinancialExplainPrompt.retryGuidance("10"))
                .doesNotContain("규칙 번호를 위반");
    }

    @Test
    void numberRuleGuidancePointsToPromptItemSevenNotThree() {
        // 검증기 규칙 3(토큰 밖 숫자)은 프롬프트 "절대 금지" 7번에 해당한다. 번호만 주면 "절대 금지" 3번(새 판정)을 가리켰다.
        assertThat(FinancialExplainPrompt.retryGuidance("3")).contains("절대 금지 7").contains("토큰");
        assertThat(FinancialExplainPrompt.retryGuidance("8")).contains("절대 금지 9");
    }

    @Test
    void everyFailureCodeHasDistinctGuidance() {
        List<String> guidance = ALL_FAILURE_CODES.stream().map(FinancialExplainPrompt::retryGuidance).toList();

        assertThat(guidance).allSatisfy(g -> assertThat(g).isNotBlank());
        assertThat(guidance.stream().collect(Collectors.toSet())).hasSize(ALL_FAILURE_CODES.size());
    }

    @Test
    void unknownFailureCodeIsRejected() {
        assertThatThrownBy(() -> FinancialExplainPrompt.retryGuidance("15")).isInstanceOf(IllegalArgumentException.class);
    }

    // ---- D-55: 관계 서술 계약의 프롬프트·재시도 안내 (점검표 14~16) ----

    @Test
    void relationRuleGuidanceNamesTheSpecificForm() {
        assertThat(FinancialExplainPrompt.retryGuidance("6")).contains("근거").contains("평가어");
        assertThat(FinancialExplainPrompt.retryGuidance("7")).contains("최근 → 과거").contains("시간 어휘");
        assertThat(FinancialExplainPrompt.retryGuidance("11")).contains("history");
        assertThat(FinancialExplainPrompt.retryGuidance("12")).contains("흑자").contains("손실").contains("_turn");
        assertThat(FinancialExplainPrompt.retryGuidance("13")).contains("비슷").contains("달리").contains("차례로");
        assertThat(FinancialExplainPrompt.retryGuidance("9")).contains("내부 용어");
        assertThat(FinancialExplainPrompt.retryGuidance("12")).contains("배지만");
        assertThat(FinancialExplainPrompt.retryGuidance("13")).contains("토큰 둘 다");
    }

    @Test
    void d56GuidanceNamesBadgePositionAndFlowFactPeriodToken() {
        assertThat(FinancialExplainPrompt.retryGuidance("14")).contains("배지").contains("값과 서술어 사이");
        assertThat(FinancialExplainPrompt.retryGuidance("7")).contains("흐름 사실이 있는 문장에는 `{per.…}`");
        String prompt = FinancialExplainPrompt.SYSTEM_PROMPT;
        assertThat(prompt).contains("지난 회계연도 말").contains("분기로 세면");
        List<String> exampleLines = prompt.lines().filter(l -> l.startsWith("> ")).toList();
        // 예시에는 "전기말"과 겹친 전환 문장("달리 … 바뀌었어요")이 없다.
        assertThat(exampleLines).noneMatch(l -> l.contains("전기말"));
        assertThat(exampleLines).noneMatch(l -> l.contains("달리 {fin.") && l.contains("_turn"));
    }

    @Test
    void promptExamplesWereReplacedNotIncreased() {
        // fx-v6의 예시는 11개였다. D-55는 예시를 늘리지 않고 새 형태로 교체한다(지침 누적의 부작용 완화, §7.4.17).
        String prompt = FinancialExplainPrompt.SYSTEM_PROMPT;
        long examples = prompt.lines().filter(l -> l.startsWith("> ")).count();
        assertThat(examples).isLessThanOrEqualTo(11);
        // 예시 출력 문장에는 흑자·적자를 글자로 쓰지 않는다(상태·전환 사실 토큰으로), 배지 하나로 두 지표를 말하지 않는다.
        List<String> exampleLines = prompt.lines().filter(l -> l.startsWith("> ")).toList();
        assertThat(exampleLines).noneMatch(l -> l.contains("흑자") || l.contains("적자에서"));
        assertThat(exampleLines).noneMatch(l -> l.contains("{sig.S1} 매출이 늘면서 영업이익률도"));
        assertThat(prompt).contains("net_income_turn").contains("OPERATING_INCOME_DIRECTION").contains("STATE_DIFFERENCE");
    }

    // ---- D-57: 개요 완전성(규칙 11 확장), 표현 정리 ----

    @Test
    void d57GuidanceRequiresOverviewToUseEveryAssignedFact() {
        assertThat(FinancialExplainPrompt.retryGuidance("11")).contains("하나도 빠짐없이").contains("둘이면 둘 다");
    }

    @Test
    void promptNoLongerTeachesPickingOneOverviewFact() {
        String prompt = FinancialExplainPrompt.SYSTEM_PROMPT;
        assertThat(prompt).doesNotContain("핵심 한 가지").doesNotContain("이 기간 보고서 기준으로");
        assertThat(prompt).contains("하나도 빠짐없이");
        // 개요 2사실 예시가 추가됐다(지표마다 자기 배지).
        List<String> exampleLines = prompt.lines().filter(l -> l.startsWith("> ")).toList();
        assertThat(exampleLines).anyMatch(l -> l.contains("overview") && l.contains("{sig.S1} 신호와 {sig.S2} 신호"));
    }

    @Test
    void promptNoLongerTeachesWritingProfitStateAsText() {
        // D-51의 금지어 우회 설명 가운데 영업이익·순이익의 흑자·적자를 글자로 쓰게 하던 부분은 상태 토큰으로 바뀌었다.
        assertThat(FinancialExplainPrompt.SYSTEM_PROMPT)
                .doesNotContain("흑자·적자 어휘는 전환 신호의 방향과 같아야 한다")
                .contains("글자로 \"흑자\"·\"적자\"·\"손실\"·\"손해\"를 쓰지 않는다");
    }

    // ---- D-58·D-59: 배지·지표 결합, "로" 잇기 금지, 절 단위 방향, 표시 값 없는 입력 (docs/work/3-4-verification-2.md §7.4.33) ----

    @Test
    void versionIsFxV13() {
        assertThat(FinancialExplainPrompt.PROMPT_VERSION).isEqualTo("fx-v13");
    }

    @Test
    void d58GuidanceNamesBadgeMetricBindingRoJoinAndClauseLevelDirection() {
        assertThat(FinancialExplainPrompt.retryGuidance("14")).contains("자기 지표의 사실 토큰이 있는 절의 맨 앞");
        assertThat(FinancialExplainPrompt.retryGuidance("13")).contains("(으)로").contains("쉼표로 차례로").contains("조금");
        assertThat(FinancialExplainPrompt.retryGuidance("6")).contains("절").contains("방향 없는 말로 바꾸지 않는다");
    }

    @Test
    void rule3GuidanceExplainsItUnlocksOtherRules() {
        assertThat(FinancialExplainPrompt.retryGuidance("3")).contains("근거가 생겨").contains("같이 풀리는");
    }

    @Test
    void rule3GuidanceComesFirstOnRetry() {
        String message = FinancialExplainPrompt.userMessage("{}", List.of("13", "3", "6"));
        int idx3 = message.indexOf(FinancialExplainPrompt.retryGuidance("3"));
        int idx13 = message.indexOf(FinancialExplainPrompt.retryGuidance("13"));
        assertThat(idx3).isPositive().isLessThan(idx13);
    }

    @Test
    void promptDescribesInputWithoutDisplayValuesOrLabels() {
        // D-59: AI 입력에는 표시 값·라벨이 없다. periods는 kind만 준다.
        assertThat(FinancialExplainPrompt.SYSTEM_PROMPT)
                .contains("기간과 종류(kind: QUARTER·ANNUAL)")
                .doesNotContain("기간과 라벨");
    }

    @Test
    void promptExamplesAttachFactsToTheirOwnBadgeInR9Example() {
        // 배지 뒤에 사실 토큰 없이 문장만 잇는(872형) 예시를 없앴다.
        assertThat(FinancialExplainPrompt.SYSTEM_PROMPT).doesNotContain("{sig.S1} 매출이 늘었지만 {sig.S2} 영업이익률은");
        List<String> exampleLines = FinancialExplainPrompt.SYSTEM_PROMPT.lines().filter(l -> l.startsWith("> ")).toList();
        assertThat(exampleLines).anyMatch(l -> l.contains("{sig.S1} 매출은") && l.contains("{fin.revenue_yoy")
                && l.contains("{sig.S2} 영업이익률은") && l.contains("{fin.operating_margin_diff"));
    }
}
