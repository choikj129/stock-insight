package org.stockinsight.financial;

/**
 * 데이터 한계 사유 코드와 해당 기간(ai-analysis.md §3.8). periodKey가 없으면(null) 기업 전체에 대한 플래그다.
 */
public record SummaryFlag(String code, String periodKey) {

    public static final String NON_KRW = "NON_KRW";
    public static final String BASIS_GAP = "BASIS_GAP";
    public static final String DERIVED_INVALID = "DERIVED_INVALID";
    public static final String SMALL_BASE = "SMALL_BASE";
    public static final String INCONSISTENT_BALANCE = "INCONSISTENT_BALANCE";
    public static final String NOT_APPLICABLE_FORMAT = "NOT_APPLICABLE_FORMAT";
    public static final String ACCOUNT_MISSING = "ACCOUNT_MISSING";
    public static final String BASE_NOT_POSITIVE = "BASE_NOT_POSITIVE";

    public SummaryFlag(String code) {
        this(code, null);
    }
}
