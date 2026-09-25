package org.stockinsight.signal;

import java.time.LocalDate;

/** 저장된 기업 신호(계산값·확인 지표 제외, 목록·상태 확인용). 자연키 = companyId + signalType + basisKey. */
public record CompanySignal(
        long companyId,
        SignalType signalType,
        String basisKey,
        SignalNature nature,
        SignalDirection direction,
        SignalSeverity severity,
        LocalDate occurredOn,
        Integer persistence,
        String sourceReceiptNo,
        SignalStatus status,
        String ruleVersion) {
}
