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
