package org.stockinsight.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * 사용자 표시(D-41·D-56): 렌더링 때 원값으로 서식을 만들고(수준 비율 '+' 없음), 토큰 뒤 조사를 맞추고, 문장 목록을 준다.
 * {@link AnalysisRenderer#render}는 신호·재무 서비스를 쓰지 않으므로 Spring 없이 만든다.
 */
class AnalysisRendererDisplayTest {

    private static final String Q2 = "2026-01.Q2";
    private final AnalysisRenderer renderer = new AnalysisRenderer(null, null);

    private static ValueSnapshot.FactSnapshot fact(String storedDisplay, String raw, String unit, String currency) {
        return new ValueSnapshot.FactSnapshot(storedDisplay, raw == null ? null : new BigDecimal(raw), unit, Q2,
                "2026-06-30", "R1", "CFS", currency);
    }

    private static ValueSnapshot snapshot(Map<String, ValueSnapshot.FactSnapshot> facts) {
        return new ValueSnapshot(facts, Map.of(Q2, "2026년 2분기", "2025-01.Q4", "2025년(연간)"),
                Map.of("S1", new ValueSnapshot.SignalSnapshot("FIN_REVENUE_CHANGE:" + Q2, "FIN_REVENUE_CHANGE", "ACTIVE",
                        "NEGATIVE", "HIGH", "2026-06-30", "R1")),
                List.of(), new ValueSnapshot.Header("CFS", "KRW", "GENERAL", Q2));
    }

    private final ValueSnapshot snap = snapshot(Map.of(
            "fin.operating_income." + Q2, fact("6,544만위안", "65440000", "CNY", "CNY"),
            "fin.revenue." + Q2, fact("52.9억원", "5290000000", "KRW", "KRW"),
            "fin.total_equity." + Q2, fact("3.3억달러", "330000000", "USD", "USD"),
            "fin.operating_margin." + Q2, fact("+31.7%", "31.66", "%", "KRW"),
            "fin.debt_ratio." + Q2, fact("+83.5%", "83.52", "%", "KRW"),
            "fin.operating_margin_diff." + Q2, fact("+28.7%p", "28.71", "%p", "KRW"),
            "fin.revenue_yoy." + Q2, fact("-69.3%", "-69.31", "%", "KRW"),
            "fin.net_income_turn." + Q2, fact("적자에서 흑자로", "300000000", FinancialExplainInputBuilder.STATE_UNIT, "KRW"),
            "fin.operating_loss_run." + Q2, fact("6분기", "6", "분기", "KRW")));

    @Test
    void particlesFollowTheRenderedValue() {
        // fx-v10 사람 검토 사례: "만위안로" → "만위안으로", "억원로"·"억원였어요" → "억원으로"·"억원이었어요".
        assertThat(renderer.render("영업이익은 {fin.operating_income.2026-01.Q2}로 늘었어요.", snap))
                .isEqualTo("영업이익은 6,544만위안으로 늘었어요.");
        assertThat(renderer.render("매출은 {fin.revenue.2026-01.Q2}였어요.", snap)).isEqualTo("매출은 52.9억원이었어요.");
        assertThat(renderer.render("자본총계는 {fin.total_equity.2026-01.Q2}으로 적어요.", snap)).isEqualTo("자본총계는 3.3억달러로 적어요.");
        assertThat(renderer.render("{per.2025-01.Q4}에는 {sig.S1} 신호가 있었어요.", snap)).contains("2025년(연간)에는");
    }

    @Test
    void levelRatiosHaveNoPlusSignButChangesKeepTheirSign() {
        assertThat(renderer.render("영업이익률은 {fin.operating_margin.2026-01.Q2}였어요.", snap)).isEqualTo("영업이익률은 31.7%였어요.");
        ValueSnapshot negative = snapshot(Map.of("fin.operating_margin." + Q2, fact("-120.4%", "-120.43", "%", "KRW")));
        assertThat(renderer.render("{fin.operating_margin.2026-01.Q2}", negative)).isEqualTo("-120.4%");
    }

    @Test
    void inSentenceChangeQuantitiesDropTheirSignEntirely() {
        // D-58: 문장 안의 변화량(%p, `_yoy`의 %)은 부호를 완전히 뺀다 — 방향은 절의 증감 어휘가 말한다(규칙 6이 어휘와
        // 부호의 일치를 보장한다). 부호를 남기면 "-69.3% 줄었어요"처럼 겹말이 된다(§7.4.33).
        assertThat(renderer.render("부채비율은 {fin.debt_ratio.2026-01.Q2}로 지난 회계연도 말보다 {fin.operating_margin_diff.2026-01.Q2} 높아졌어요.", snap))
                .isEqualTo("부채비율은 83.5%로 지난 회계연도 말보다 28.7%p 높아졌어요.");
        assertThat(renderer.render("매출은 {fin.revenue_yoy.2026-01.Q2} 줄었어요.", snap)).isEqualTo("매출은 69.3% 줄었어요.");
    }

    @Test
    void unknownUnitsAndMissingRawValuesUseStoredDisplay() {
        assertThat(renderer.render("당기순이익은 {fin.net_income_turn.2026-01.Q2} 돌아섰어요.", snap))
                .isEqualTo("당기순이익은 적자에서 흑자로 돌아섰어요.");
        ValueSnapshot legacy = snapshot(Map.of("fin.revenue." + Q2, fact("52.9억원", null, "KRW", "KRW")));
        assertThat(renderer.render("{fin.revenue.2026-01.Q2}", legacy)).isEqualTo("52.9억원");
    }

    @Test
    void renderSentencesSplitsOnTheValidatorBoundaryBeforeRendering() {
        List<String> sentences = renderer.renderSentences(
                "영업이익은 {fin.operating_income.2026-01.Q2}로 늘었어요. 영업적자가 {fin.operating_loss_run.2026-01.Q2}째 이어지고 있어요.", snap);

        assertThat(sentences).containsExactly("영업이익은 6,544만위안으로 늘었어요.", "영업적자가 6분기째 이어지고 있어요.");
        assertThat(renderer.renderSentences(null, snap)).isEmpty();
    }

    @Test
    void leadingBadgesAreBracketedButNameBadgesStayInline() {
        // D-58: 배지가 문장·절의 앞머리면 대괄호로 구분해 배지 이름과 주어가 붙어 읽히지 않게 한다("매출 큰 폭 감소
        // 매출은…"이 아니라 "[매출 큰 폭 감소] 매출은…"). "… 신호" 앞의 이름 배지는 그대로 문장 속 말이다.
        assertThat(renderer.render("{sig.S1} 매출은 {fin.revenue.2026-01.Q2}로 전년 같은 분기보다 줄었어요.", snap))
                .isEqualTo("[매출 큰 폭 감소] 매출은 52.9억원으로 전년 같은 분기보다 줄었어요.");
        assertThat(renderer.render("{per.2025-01.Q4}에는 {sig.S1} 신호가 있었어요.", snap))
                .contains("매출 큰 폭 감소 신호가 있었어요.").doesNotContain("[매출 큰 폭 감소]");
    }

    @Test
    void escapingAndUnknownTokensAreUnchanged() {
        assertThat(renderer.render("<b>매출</b>은 {fin.revenue.2026-01.Q2}로", snap)).isEqualTo("&lt;b&gt;매출&lt;/b&gt;은 52.9억원으로");
        assertThatThrownBy(() -> renderer.render("{fin.unknown.2026-01.Q2}", snap)).isInstanceOf(IllegalStateException.class);
    }
}
