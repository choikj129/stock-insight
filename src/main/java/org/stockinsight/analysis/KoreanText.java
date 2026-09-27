package org.stockinsight.analysis;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 재무 쉬운 설명 글의 한국어 처리: 문장 경계(검증기·렌더러 공용)와 토큰 뒤 조사 맞춤(D-56).
 * DB·외부 호출 없는 순수 계산이다.
 */
final class KoreanText {

    // 비교 조사 '보다'와 명사 '주요·필요·중요'는 '다·요'로 끝나도 문장 끝이 아니다(D-53).
    private static final Pattern SENTENCE_SPLIT = Pattern.compile("(?<=[요다.])(?<!보다|주요|필요|중요)(?=\\s|$)");

    // 배지 위치 판정(D-56 규칙 14, D-58 확장). 검증기와 렌더러가 같은 판정을 쓴다 — 검증기는 통과·거절을, 렌더러는
    // 칩(앞머리)과 문장 속 말(이름)을 가른다.
    private static final Pattern ONLY_BADGES = Pattern.compile("^(?:\\s*\\{sig\\.[^{}]+\\}\\s*(?:[·,]|와|과|및)?)*\\s*$");
    private static final Pattern BADGES_THEN_SIGNAL_WORD = Pattern.compile("^(?:\\s*(?:[·,]|와|과|및)?\\s*\\{sig\\.[^{}]+\\})*\\s*신호");
    private static final List<String> CLAUSE_ENDINGS = List.of(",", "고", "며", "면서", "지만", "는데");
    private static final Pattern PERIOD_ADVERBIAL_END = Pattern.compile("\\{per\\.[^{}]+\\}\\s*(?:에는|에|기준)$");

    /**
     * 토큰 바로 뒤에 오는 조사 쌍(받침 있을 때, 받침 없을 때). 긴 것부터 본다 — "이었"·"이에요"를 주격 "이"보다 먼저.
     * {@code needsBoundary}면 조사 뒤가 한글이 아닐 때만 조사로 본다("이고", "는데"처럼 다른 말의 시작일 수 있는 것).
     */
    private record Particle(String afterConsonant, String afterVowel, boolean needsBoundary) {
    }

    private static final List<Particle> PARTICLES = List.of(
            new Particle("으로", "로", false),
            new Particle("이었", "였", false),
            new Particle("이에요", "예요", false),
            new Particle("을", "를", true),
            new Particle("은", "는", true),
            new Particle("과", "와", true),
            new Particle("이", "가", true));

    /** 받침 번호(한글 음절의 종성 인덱스). */
    private static final int NO_FINAL = 0;
    private static final int FINAL_RIEUL = 8;
    private static final int UNKNOWN = -1;

    private KoreanText() {
    }

    /** 글을 문장으로 나눈다. 검증기의 문장 단위 규칙과 분량 규칙, 렌더러의 문장 목록이 같은 경계를 쓴다(D-53·D-56). */
    static List<String> splitSentences(String text) {
        List<String> result = new ArrayList<>();
        for (String s : SENTENCE_SPLIT.split(text.strip())) {
            if (!s.isBlank()) {
                result.add(s.strip());
            }
        }
        return result;
    }

    /**
     * 렌더링한 값 바로 뒤의 조사를 그 값의 끝소리에 맞춘다(예: "2.1억위안" + "로" → "으로", "+15.0%" + "이었어요" → "였어요").
     * 끝소리를 정할 수 없으면(외국 통화 코드, 0으로 끝나는 숫자 등) 바꾸지 않는다. 정해진 조사 문자열만 바꾼다.
     */
    static String adjustParticle(String renderedValue, String following) {
        if (following.isEmpty()) {
            return following;
        }
        int finalConsonant = finalConsonantOf(renderedValue);
        if (finalConsonant == UNKNOWN) {
            return following;
        }
        for (Particle p : PARTICLES) {
            String current = following.startsWith(p.afterConsonant()) ? p.afterConsonant()
                    : following.startsWith(p.afterVowel()) ? p.afterVowel() : null;
            if (current == null) {
                continue;
            }
            if (p.needsBoundary() && following.length() > current.length() && isHangulSyllable(following.charAt(current.length()))) {
                continue;
            }
            // "로"는 받침이 없거나 'ㄹ' 받침 뒤에 온다.
            boolean useConsonantForm = "으로".equals(p.afterConsonant())
                    ? finalConsonant != NO_FINAL && finalConsonant != FINAL_RIEUL
                    : finalConsonant != NO_FINAL;
            String wanted = useConsonantForm ? p.afterConsonant() : p.afterVowel();
            return wanted + following.substring(current.length());
        }
        return following;
    }

    /**
     * 배지가 "… 신호" 바로 앞의 이름 자리인가(D-56·D-58). 여러 배지가 나란히 와도("{sig.S1}·{sig.S2} 신호가…") 된다.
     * 이름 배지는 문장 속 말로 그대로 둔다(칩으로 바꾸지 않는다).
     */
    static boolean isNameBadge(String sentence, int badgeEnd) {
        return BADGES_THEN_SIGNAL_WORD.matcher(sentence.substring(badgeEnd)).lookingAt();
    }

    /**
     * 배지가 문장·절의 맨 앞(앞머리)인가: 앞이 비어 있거나 다른 배지뿐이거나(문장 맨 앞), 절 경계(쉼표·연결 어미) 또는
     * 기간 부사어("{per}에는" 등) 바로 뒤다(D-56). 이름 배지 판정({@link #isNameBadge})과 겹칠 수 있어 호출하는 쪽이
     * 이름 배지를 먼저 본다(검증기는 둘 중 하나만 맞아도 통과, 렌더러는 이름을 우선한다).
     */
    static boolean isLeadingBadge(String sentence, int badgeStart) {
        String before = sentence.substring(0, badgeStart);
        if (ONLY_BADGES.matcher(before).matches()) {
            return true;
        }
        String clause = before.replaceAll("(?:\\s*\\{sig\\.[^{}]+\\})+\\s*$", "").stripTrailing();
        return CLAUSE_ENDINGS.stream().anyMatch(clause::endsWith) || PERIOD_ADVERBIAL_END.matcher(clause).find();
    }

    /** 값을 소리 내어 읽을 때 마지막 음절의 받침 번호. 끝의 괄호·따옴표·공백은 건너뛴다. 정할 수 없으면 {@link #UNKNOWN}. */
    static int finalConsonantOf(String value) {
        for (int i = value.length() - 1; i >= 0; i--) {
            char c = value.charAt(i);
            if (" )]}'\"’”".indexOf(c) >= 0) {
                continue;
            }
            if (isHangulSyllable(c)) {
                return (c - 0xAC00) % 28;
            }
            if (c == '%') {
                return NO_FINAL; // 퍼센트
            }
            if (c == 'p' && i > 0 && value.charAt(i - 1) == '%') {
                return NO_FINAL; // 퍼센트포인트
            }
            return switch (c) {
                case '1', '7', '8' -> FINAL_RIEUL; // 일·칠·팔
                case '3' -> 16; // 삼(ㅁ)
                case '6' -> 1; // 육(ㄱ)
                case '2', '4', '5', '9' -> NO_FINAL; // 이·사·오·구
                default -> UNKNOWN; // 0(십·백·천으로 읽힘), 영문 등
            };
        }
        return UNKNOWN;
    }

    private static boolean isHangulSyllable(char c) {
        return c >= 0xAC00 && c <= 0xD7A3;
    }
}
