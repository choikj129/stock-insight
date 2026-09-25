package org.stockinsight.analysis;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 렌더링용 값 스냅샷(ai-analysis.md §4.4.5). AI 입력({@link FinancialExplainInput})보다 자세하다(원값·기간 종료일·
 * 출처 공시번호 포함). 생성 당시 값을 고정해 두므로, 나중에 데이터가 바뀌어도 게시된 문장의 숫자는 바뀌지 않는다.
 */
public record ValueSnapshot(
        Map<String, FactSnapshot> facts,
        Map<String, String> periodLabels,
        Map<String, SignalSnapshot> signals,
        java.util.List<String> limitCodes,
        Header header) {

    public record FactSnapshot(
            String display,
            BigDecimal rawValue,
            String unit,
            String periodKey,
            String periodEnd,
            String receiptNo,
            String basis,
            String currency) {
    }

    public record SignalSnapshot(
            String naturalKey,
            String type,
            String status,
            String direction,
            String severity,
            String occurredOn,
            String receiptNo) {
    }

    public record Header(String basis, String currency, String format, String latestPeriod) {
    }
}
