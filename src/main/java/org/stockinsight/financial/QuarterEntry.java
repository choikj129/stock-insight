package org.stockinsight.financial;

import java.time.LocalDate;

/**
 * 분기 시계열의 한 기간(ai-analysis.md §3.6). 1·2·3분기는 실제 보고서(1분기·반기·3분기)의 당기 값을 그대로 쓰고,
 * 4분기 손익은 파생값(연간 − 3분기 누적)이라 전년 대비 비교를 하지 않는다({@code derived}). 4분기의 재무상태표 값은
 * 파생이 아니라 사업보고서 자체의 기간 말 값이다({@code reportType}은 항상 그 값의 출처 보고서 종류를 가리킨다).
 */
public record QuarterEntry(
        PeriodKey key,
        PeriodType reportType,
        LocalDate periodEnd,
        String receiptNo,
        boolean derived,
        boolean derivedValid,
        FinancialFormat format,
        boolean balanceConsistent,
        MetricValue revenue,
        MetricValue operatingIncome,
        MetricValue netIncome,
        MetricValue totalAssets,
        MetricValue totalLiabilities,
        MetricValue totalEquity,
        MetricValue capitalStock) {

    /** 흐름 지표(매출·영업이익) 변화 신호 판정 대상인가: 4분기(파생)는 근거 보고서가 없어 제외한다. */
    public boolean flowSignalEligible() {
        return !derived;
    }

    /** 지속(연속) 계산에 쓸 손익 부호가 있는가. */
    public boolean hasOperatingIncome() {
        return (!derived || derivedValid) && operatingIncome.hasCurrent();
    }
}
