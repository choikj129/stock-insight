package org.stockinsight.financial;

import java.time.LocalDate;
import java.util.List;

/** 저장된 재무 보고서(기업·기간·연결/별도 한 벌)와 그 계정 행. */
public record StoredFinancialReport(
        long companyId,
        int bsnsYear,
        String reportCode,
        String fsDiv,
        PeriodType periodType,
        LocalDate fiscalYearStart,
        LocalDate periodEnd,
        String currency,
        String receiptNo,
        List<StoredFinancialLine> lines) {
}
