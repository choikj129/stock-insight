package org.stockinsight.disclosure;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 공시 보고서명. OpenDART의 보고서명은 "공시구분 + 보고서명 + 기타정보"이고, 원본에서 기타정보는 공백 여러 칸 뒤에 온다
 * (예: "주주총회소집결의              (임시주주총회)"). 정정 제출이면 앞에 [기재정정]·[첨부정정]·[첨부추가] 같은 표시가 붙는다.
 * OpenDART 정의상 이 표시는 "본 보고서명으로 이미 제출된 보고서"에 대한 후속 제출이라는 뜻이므로,
 * 표시와 기타정보를 뗀 이름({@link #baseName()})이 같은 기업의 원 공시를 찾는 기준이 된다.
 * 정정 제출은 기타정보를 빼거나 바꿔 내는 경우가 있다 (2026-09-25 실제 응답 확인).
 *
 * @param name           공백을 정리한 보고서명 (표시·기타정보 포함). 화면에 보이는 이름이다
 * @param baseName       정정 표시와 기타정보를 뗀 보고서명
 * @param amendmentLabel 정정 표시 (예: 기재정정). 최초 제출이면 null
 */
public record ReportName(String name, String baseName, String amendmentLabel) {

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern EXTRA_INFO_GAP = Pattern.compile("\\s{2,}");
    private static final Pattern LEADING_LABEL = Pattern.compile("^\\[([^\\]]+)]\\s*");

    public static ReportName parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("보고서명이 비어 있습니다");
        }
        String stripped = raw.strip();
        String name = normalize(stripped);
        String reportPart = normalize(EXTRA_INFO_GAP.split(stripped, 2)[0]);

        String rest = reportPart;
        String label = null;
        Matcher matcher = LEADING_LABEL.matcher(rest);
        while (matcher.find()) {
            if (label == null) {
                label = matcher.group(1).strip();
            }
            rest = rest.substring(matcher.end());
            matcher = LEADING_LABEL.matcher(rest);
        }
        if (rest.isEmpty()) {
            return new ReportName(name, name, null);
        }
        return new ReportName(name, rest, label);
    }

    private static String normalize(String value) {
        return WHITESPACE.matcher(value.strip()).replaceAll(" ");
    }

    public boolean isAmendment() {
        return amendmentLabel != null;
    }
}
