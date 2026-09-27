package org.stockinsight.analysis;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

import org.stockinsight.financial.FinancialService;
import org.stockinsight.signal.CompanySignal;
import org.stockinsight.signal.CompanySignalService;
import org.stockinsight.signal.SignalStatus;

/**
 * 게시용 조립(화면 전 단계, ai-analysis.md §4.4.5). 토큰을 값 스냅샷으로 바꾸고 이스케이프하며, 게시본이 참조한
 * 신호·보고서가 최신 데이터와 달라졌는지(무효화, D-42) 판단한다. 화면(Thymeleaf)은 아직 없다.
 */
@Service
public class AnalysisRenderer {

    private static final Pattern TOKEN = Pattern.compile("\\{((?:fin|per|sig)\\.[^{}]+)\\}");

    private final CompanySignalService signalService;
    private final FinancialService financialService;

    AnalysisRenderer(CompanySignalService signalService, FinancialService financialService) {
        this.signalService = signalService;
        this.financialService = financialService;
    }

    /**
     * 토큰을 값 스냅샷의 값으로 치환한다. 결과 전체가 HTML 이스케이프를 거친다 — 토큰 값뿐 아니라 토큰 사이의 AI 문장도
     * 이스케이프한다(AI 출력은 외부 입력으로 다룬다, 2026-09-27 D-55 구현 중 보안 점검). 모르는 토큰은 예외를 던진다.
     * 사실 값은 스냅샷 원값으로 서식을 만들고(D-41), 토큰 바로 뒤 조사는 그 값의 끝소리에 맞춘다(D-56). 조사 보정은
     * 이스케이프 전에 정해진 조사 문자열만 바꾼다.
     */
    public String render(String textWithTokens, ValueSnapshot snapshot) {
        if (textWithTokens == null) {
            return null;
        }
        Matcher m = TOKEN.matcher(textWithTokens);
        StringBuilder out = new StringBuilder();
        int last = 0;
        String previousValue = null;
        while (m.find()) {
            out.append(escapeHtml(afterToken(previousValue, textWithTokens.substring(last, m.start()))));
            last = m.end();
            String inner = m.group(1);
            int dot = inner.indexOf('.');
            String kind = inner.substring(0, dot);
            String replacement = switch (kind) {
                case "fin" -> Optional.ofNullable(snapshot.facts().get(inner))
                        .map(f -> readerDisplay(inner, f))
                        .orElseThrow(() -> new IllegalStateException("값 스냅샷에 없는 사실 토큰: " + inner));
                case "per" -> Optional.ofNullable(snapshot.periodLabels().get(inner.substring(dot + 1)))
                        .orElseThrow(() -> new IllegalStateException("값 스냅샷에 없는 기간 토큰: " + inner));
                case "sig" -> {
                    ValueSnapshot.SignalSnapshot signal = snapshot.signals().get(inner.substring(dot + 1));
                    if (signal == null) {
                        throw new IllegalStateException("값 스냅샷에 없는 신호 토큰: " + inner);
                    }
                    String label = StaticContent.badgeName(signal.type(), signal.direction());
                    // D-58: 배지가 문장·절의 앞머리(칩 후보)면 대괄호로 구분한다("[매출 큰 폭 감소] 매출은 …") — 화면의
                    // 칩(UI 미구현) 대신, 문자열만 있는 자리(감사 로그·검토 자료)에서도 "매출 큰 폭 감소 매출은…"처럼
                    // 배지 이름과 주어가 붙어 읽히지 않게 한다. "… 신호" 앞 이름 배지는 문장 속 말로 그대로 둔다.
                    boolean leadingBadge = !KoreanText.isNameBadge(textWithTokens, m.end())
                            && KoreanText.isLeadingBadge(textWithTokens, m.start());
                    yield leadingBadge ? "[" + label + "]" : label;
                }
                default -> throw new IllegalStateException("알 수 없는 토큰: " + inner);
            };
            out.append(escapeHtml(replacement));
            previousValue = replacement;
        }
        out.append(escapeHtml(afterToken(previousValue, textWithTokens.substring(last))));
        return out.toString();
    }

    /**
     * 섹션 글을 문장 목록으로 렌더링한다(화면은 문장마다 줄을 바꾼다, D-56). 검증기와 같은 문장 경계로 원문(토큰 상태)에서
     * 나눈 뒤 문장마다 {@link #render}한다 — 표시 값 안의 '.'(예: 13.7억)이 경계가 되지 않는다.
     */
    public List<String> renderSentences(String textWithTokens, ValueSnapshot snapshot) {
        if (textWithTokens == null) {
            return List.of();
        }
        return KoreanText.splitSentences(textWithTokens).stream().map(s -> render(s, snapshot)).toList();
    }

    private static String afterToken(String previousValue, String literal) {
        return previousValue == null ? literal : KoreanText.adjustParticle(previousValue, literal);
    }

    /**
     * 사실의 사용자 표시 값. 원값·단위로 렌더링 때 서식을 만든다(D-41). 원값이 없거나 단위를 모르면(상태·전환 사실의 닫힌
     * 문구 등) 저장된 표시 문자열을 쓴다. AI 문장 안에 박히는 변화량(%p·`_yoy`의 %)은 부호를 뺀다(D-58) — 방향은
     * 같은 절의 증감 어휘가 말하고 검증기 규칙 6이 어휘와 부호의 일치를 보장하므로, 부호는 겹말이다.
     */
    static String readerDisplay(String factKey, ValueSnapshot.FactSnapshot fact) {
        String unit = fact.unit();
        if (fact.rawValue() == null || unit == null) {
            return fact.display();
        }
        boolean known = "%".equals(unit) || "%p".equals(unit) || "분기".equals(unit) || unit.equals(fact.currency());
        if (!known) {
            return fact.display();
        }
        return PeriodLabels.formatForSentence(metricOf(factKey), fact.rawValue(), unit);
    }

    /** "fin.<지표>.<기간 키>"의 지표 이름. 기간 키에도 점이 있어 두 번째 점까지만 자른다. */
    private static String metricOf(String factKey) {
        int first = factKey.indexOf('.');
        int second = factKey.indexOf('.', first + 1);
        return second < 0 ? factKey.substring(first + 1) : factKey.substring(first + 1, second);
    }

    /** 섹션 옆에 보여줄 데이터 한계 문구. 렌더링 시점의 현재 활성 데이터 한계 신호로 만든다(D-42, 스냅샷을 쓰지 않는다). */
    public List<String> limitMessages(long companyId) {
        return signalService.findByCompany(companyId).stream()
                .filter(s -> s.status() == SignalStatus.ACTIVE && s.signalType().name().startsWith("FIN_DATA_"))
                .map(s -> s.signalType().name())
                .distinct()
                .sorted()
                .map(StaticContent::limitCodeMessage)
                .toList();
    }

    /**
     * 게시본의 값 스냅샷이 참조한 것만 확인해 무효화를 판단한다(D-42). 입력 전체를 다시 만들지 않는다.
     * 참조한 신호가 철회되었거나 방향이 바뀌었으면, 또는 참조한 사실의 기간·기준 보고서의 현재 공시번호가 스냅샷과
     * 다르거나 보고서가 없어졌으면 무효화한다. 새 보고서 도착, 활성→이력 전환, 심각도·규칙 버전 변경, 시계열 기준 전환,
     * 데이터 한계 변화만으로는 무효화하지 않는다.
     */
    public boolean isInvalidated(Analysis current, long companyId) {
        ValueSnapshot snapshot = current.valueSnapshot();

        Map<String, CompanySignal> currentByNaturalKey = new LinkedHashMap<>();
        for (CompanySignal signal : signalService.findByCompany(companyId)) {
            currentByNaturalKey.put(signal.signalType().name() + ":" + signal.basisKey(), signal);
        }
        for (ValueSnapshot.SignalSnapshot referenced : snapshot.signals().values()) {
            CompanySignal now = currentByNaturalKey.get(referenced.naturalKey());
            if (now == null || now.status() == SignalStatus.WITHDRAWN
                    || !now.direction().name().equals(referenced.direction())) {
                return true;
            }
        }

        Set<String> checked = new HashSet<>();
        for (ValueSnapshot.FactSnapshot fact : snapshot.facts().values()) {
            if (fact.receiptNo() == null || fact.periodEnd() == null || fact.basis() == null) {
                continue;
            }
            String key = fact.periodEnd() + ":" + fact.basis();
            if (!checked.add(key)) {
                continue;
            }
            Optional<String> currentReceiptNo = financialService.currentReceiptNo(companyId,
                    LocalDate.parse(fact.periodEnd()), fact.basis());
            if (currentReceiptNo.isEmpty() || !currentReceiptNo.get().equals(fact.receiptNo())) {
                return true;
            }
        }
        return false;
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
