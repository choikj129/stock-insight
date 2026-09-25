package org.stockinsight.analysis;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

/**
 * 게시용 조립(화면 전 단계, ai-analysis.md §4.4.5). 토큰을 값 스냅샷으로 바꾸고 이스케이프하며, 게시본이 참조한
 * 신호·보고서가 최신 데이터와 달라졌는지(무효화, D-40) 판단한다. 화면(Thymeleaf)은 아직 없다.
 */
@Service
public class AnalysisRenderer {

    private static final Pattern TOKEN = Pattern.compile("\\{((?:fin|per|sig)\\.[^{}]+)\\}");

    private final FinancialExplainInputBuilder inputBuilder;

    AnalysisRenderer(FinancialExplainInputBuilder inputBuilder) {
        this.inputBuilder = inputBuilder;
    }

    /** 토큰을 값 스냅샷의 표시 값으로 치환한다. HTML 이스케이프를 거친다. 모르는 토큰은 예외를 던진다. */
    public String render(String textWithTokens, ValueSnapshot snapshot) {
        if (textWithTokens == null) {
            return null;
        }
        Matcher m = TOKEN.matcher(textWithTokens);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String inner = m.group(1);
            int dot = inner.indexOf('.');
            String kind = inner.substring(0, dot);
            String replacement = switch (kind) {
                case "fin" -> Optional.ofNullable(snapshot.facts().get(inner))
                        .map(ValueSnapshot.FactSnapshot::display)
                        .orElseThrow(() -> new IllegalStateException("값 스냅샷에 없는 사실 토큰: " + inner));
                case "per" -> Optional.ofNullable(snapshot.periodLabels().get(inner.substring(dot + 1)))
                        .orElseThrow(() -> new IllegalStateException("값 스냅샷에 없는 기간 토큰: " + inner));
                case "sig" -> {
                    ValueSnapshot.SignalSnapshot signal = snapshot.signals().get(inner.substring(dot + 1));
                    if (signal == null) {
                        throw new IllegalStateException("값 스냅샷에 없는 신호 토큰: " + inner);
                    }
                    yield StaticContent.badgeName(signal.type(), signal.direction());
                }
                default -> throw new IllegalStateException("알 수 없는 토큰: " + inner);
            };
            m.appendReplacement(out, Matcher.quoteReplacement(escapeHtml(replacement)));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** 섹션 옆에 보여줄 데이터 한계 문구. */
    public java.util.List<String> limitMessages(ValueSnapshot snapshot) {
        return snapshot.limitCodes().stream().map(StaticContent::limitCodeMessage).toList();
    }

    /**
     * 게시본이 참조한 신호가 철회되었거나 참조한 보고서의 공시번호가 바뀌었으면 무효화한다(D-40). 지금 다시 입력을 만들어
     * 지문이 달라졌는지로 판단한다(새 보고서가 나온 것만으로는 지문이 바뀌지 않을 수 있다 — 계기가 없으면 재무는 그대로다).
     */
    public boolean isInvalidated(Analysis current, long companyId) {
        Optional<FinancialExplainInputBuilder.BuildResult> fresh = inputBuilder.build(companyId);
        return fresh.isEmpty() || !fresh.get().fingerprint().equals(current.fingerprint());
    }

    private static String escapeHtml(String value) {
        Map<Character, String> escapes = ESCAPES;
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            sb.append(escapes.getOrDefault(c, String.valueOf(c)));
        }
        return sb.toString();
    }

    private static final Map<Character, String> ESCAPES = new LinkedHashMap<>();

    static {
        ESCAPES.put('&', "&amp;");
        ESCAPES.put('<', "&lt;");
        ESCAPES.put('>', "&gt;");
        ESCAPES.put('"', "&quot;");
        ESCAPES.put('\'', "&#39;");
    }
}
