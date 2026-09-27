package org.stockinsight.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 토큰 뒤 조사 맞춤과 문장 경계(D-56). fx-v10 사람 검토에서 나온 형태("만위안로", "위안였어요")를 그대로 쓴다. */
class KoreanTextTest {

    @Test
    void consonantEndingCurrencyTakesConsonantParticles() {
        assertThat(KoreanText.adjustParticle("6,544만위안", "로 영업이익률은")).isEqualTo("으로 영업이익률은");
        assertThat(KoreanText.adjustParticle("13.7억위안", "였어요.")).isEqualTo("이었어요.");
        assertThat(KoreanText.adjustParticle("52.9억원", "로 줄었어요.")).isEqualTo("으로 줄었어요.");
        assertThat(KoreanText.adjustParticle("14.0조원", "예요.")).isEqualTo("이에요.");
        assertThat(KoreanText.adjustParticle("794.1억원", "을 보면")).isEqualTo("을 보면");
    }

    @Test
    void vowelEndingValuesTakeVowelParticles() {
        assertThat(KoreanText.adjustParticle("3.3억달러", "으로 자본금")).isEqualTo("로 자본금");
        assertThat(KoreanText.adjustParticle("31.7%", "이었어요.")).isEqualTo("였어요.");
        assertThat(KoreanText.adjustParticle("+5.6%p", "으로")).isEqualTo("로");
        assertThat(KoreanText.adjustParticle("6분기", "이에요.")).isEqualTo("예요.");
        assertThat(KoreanText.adjustParticle("적자", "이었어요.")).isEqualTo("였어요.");
    }

    @Test
    void rieulEndingTakesRoAndDigitsAreReadAloud() {
        assertThat(KoreanText.adjustParticle("7", "으로")).isEqualTo("로"); // 칠(ㄹ)
        assertThat(KoreanText.adjustParticle("3", "로")).isEqualTo("으로"); // 삼(ㅁ)
        assertThat(KoreanText.adjustParticle("2", "은")).isEqualTo("는"); // 이
    }

    @Test
    void unknownEndingsAndNonParticlesAreLeftAlone() {
        // 0으로 끝나는 숫자는 십·백·천으로 읽혀 끝소리를 정할 수 없고, 외국 통화 코드도 그렇다.
        assertThat(KoreanText.adjustParticle("10", "로")).isEqualTo("로");
        assertThat(KoreanText.adjustParticle("12.3억 CHF", "로")).isEqualTo("로");
        // 조사가 아닌 말의 시작("이고", "는데")과 띄어 쓴 말은 바꾸지 않는다.
        assertThat(KoreanText.adjustParticle("31.7%", "이고,")).isEqualTo("이고,");
        assertThat(KoreanText.adjustParticle("52.9억원", "는데")).isEqualTo("는데");
        assertThat(KoreanText.adjustParticle("52.9억원", " 신호가")).isEqualTo(" 신호가");
        assertThat(KoreanText.adjustParticle("52.9억원", "")).isEmpty();
    }

    @Test
    void finalConsonantSkipsClosingBracketsOfPeriodLabels() {
        assertThat(KoreanText.finalConsonantOf("2025년(연간)")).isEqualTo(4); // 간(ㄴ)
        assertThat(KoreanText.finalConsonantOf("2026.04~06(1분기)")).isZero(); // 기
        assertThat(KoreanText.adjustParticle("2025년(연간)", "가 있었어요")).isEqualTo("이 있었어요");
    }

    @Test
    void sentenceBoundaryIsTheValidatorsBoundary() {
        // D-53: "보다"는 문장 끝이 아니다. 표시 값의 '.'(13.7억)은 공백이 뒤따르지 않아 경계가 아니다.
        assertThat(KoreanText.splitSentences("자본총계는 {fin.a}로 자본금 {fin.b}보다 적어요. 매출은 13.7억위안이었어요."))
                .containsExactly("자본총계는 {fin.a}로 자본금 {fin.b}보다 적어요.", "매출은 13.7억위안이었어요.");
    }

    // ---- D-58: 배지 위치 판정(규칙 14·렌더러 공용) ----

    @Test
    void nameBadgeIsRecognizedRightBeforeSignalWord() {
        String s = "{per.2026-01.Q2} 기준 {sig.S1} 신호가 있어요.";
        int badgeEnd = s.indexOf("}", s.indexOf("{sig.S1}")) + 1;
        assertThat(KoreanText.isNameBadge(s, badgeEnd)).isTrue();
    }

    @Test
    void nameBadgeAllowsSeveralBadgesBeforeSignalWord() {
        String s = "{sig.S1} {sig.S2} 신호가 있어요.";
        int firstEnd = s.indexOf("}") + 1;
        assertThat(KoreanText.isNameBadge(s, firstEnd)).isTrue();
    }

    @Test
    void leadingBadgeIsRecognizedAtSentenceStartAndAfterClauseBoundaries() {
        String startOfSentence = "{sig.S1} 매출이 늘었어요.";
        assertThat(KoreanText.isLeadingBadge(startOfSentence, 0)).isTrue();

        String afterComma = "매출은 {fin.a}였어요, {sig.S1} 영업이익도 늘었어요.";
        int badgeStart = afterComma.indexOf("{sig.S1}");
        assertThat(KoreanText.isLeadingBadge(afterComma, badgeStart)).isTrue();

        String afterPeriodAdverbial = "지난 {per.2026-01.Q1}에는 {sig.S1} 매출이 줄었어요.";
        int badgeStart2 = afterPeriodAdverbial.indexOf("{sig.S1}");
        assertThat(KoreanText.isLeadingBadge(afterPeriodAdverbial, badgeStart2)).isTrue();
    }

    @Test
    void badgeBetweenValueAndPredicateIsNeitherLeadingNorName() {
        String s = "매출은 {fin.a}로 {sig.S1} 줄었어요.";
        int badgeStart = s.indexOf("{sig.S1}");
        int badgeEnd = badgeStart + "{sig.S1}".length();
        assertThat(KoreanText.isLeadingBadge(s, badgeStart)).isFalse();
        assertThat(KoreanText.isNameBadge(s, badgeEnd)).isFalse();
    }
}
