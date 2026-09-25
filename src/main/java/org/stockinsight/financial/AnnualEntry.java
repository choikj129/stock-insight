package org.stockinsight.financial;

import java.time.LocalDate;

/** 연간 시계열의 한 사업연도. 사업보고서 한 벌에서 온다(ai-analysis.md §3.6). */
public record AnnualEntry(
        LocalDate fiscalYearStart,
        LocalDate periodEnd,
        String receiptNo,
        boolean irregular,
        boolean balanceConsistent,
        FinancialFormat format,
        MetricValue revenue,
        MetricValue operatingIncome,
        MetricValue netIncome,
        MetricValue totalAssets,
        MetricValue totalLiabilities,
        MetricValue totalEquity,
        MetricValue capitalStock) {

    public String basisKey() {
        return fiscalYearStart + ":FY";
    }
}
