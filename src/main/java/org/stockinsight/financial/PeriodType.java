package org.stockinsight.financial;

import java.util.Arrays;

/** 보고서 기간 구분. OpenDART 보고서 코드(report_code)에서 정한다. */
public enum PeriodType {
    Q1("11013"),
    H1("11012"),
    Q3("11014"),
    FY("11011");

    private final String reportCode;

    PeriodType(String reportCode) {
        this.reportCode = reportCode;
    }

    public String reportCode() {
        return reportCode;
    }

    public static PeriodType fromReportCode(String reportCode) {
        return Arrays.stream(values())
                .filter(type -> type.reportCode.equals(reportCode))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("알 수 없는 보고서 코드: " + reportCode));
    }
}
