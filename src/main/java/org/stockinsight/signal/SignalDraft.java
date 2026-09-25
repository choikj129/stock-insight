package org.stockinsight.signal;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 계산기가 만든 신호 초안. 저장 전 단계이며 대리키가 없다.
 *
 * @param basisKey        자연키의 일부(기업 + 유형 + 이 값). ai-analysis.md §3.7
 * @param persistence     같은 방향의 변화가 이어진 기간 수. 상태 신호가 아니면 null일 수 있다
 * @param calcValues      판정에 쓴 계산값(자리표시자 키·값)
 * @param watchMetrics    이 신호가 있을 때 지켜볼 지표 키
 * @param sourceReceiptNo 근거 보고서의 공시번호(최신 정정본). 데이터 한계 신호는 null일 수 있다
 * @param active          최신 재무 기간에서 나온 것인가(활성 여부는 이 값과 규칙 버전으로 CompanySignalService가 정한다)
 */
public record SignalDraft(
        SignalType type,
        String basisKey,
        SignalNature nature,
        SignalDirection direction,
        SignalSeverity severity,
        LocalDate occurredOn,
        Integer persistence,
        Map<String, Object> calcValues,
        List<String> watchMetrics,
        String sourceReceiptNo,
        boolean active) {
}
