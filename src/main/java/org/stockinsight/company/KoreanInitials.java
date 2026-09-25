package org.stockinsight.company;

/** 초성 검색용 문자열을 만든다. 예: "삼성전자" → "ㅅㅅㅈㅈ". 한글이 아닌 문자는 그대로 두고 공백은 뺀다. */
public final class KoreanInitials {

    private static final char[] CHOSEONG = {
            'ㄱ', 'ㄲ', 'ㄴ', 'ㄷ', 'ㄸ', 'ㄹ', 'ㅁ', 'ㅂ', 'ㅃ', 'ㅅ',
            'ㅆ', 'ㅇ', 'ㅈ', 'ㅉ', 'ㅊ', 'ㅋ', 'ㅌ', 'ㅍ', 'ㅎ'
    };
    private static final char SYLLABLE_START = 0xAC00;
    private static final char SYLLABLE_END = 0xD7A3;
    private static final int SYLLABLES_PER_INITIAL = 21 * 28;

    private KoreanInitials() {
    }

    public static String of(String text) {
        if (text == null) {
            return null;
        }
        StringBuilder result = new StringBuilder(text.length());
        for (char c : text.toCharArray()) {
            if (c >= SYLLABLE_START && c <= SYLLABLE_END) {
                result.append(CHOSEONG[(c - SYLLABLE_START) / SYLLABLES_PER_INITIAL]);
            } else if (!Character.isWhitespace(c)) {
                result.append(c);
            }
        }
        return result.toString();
    }
}
