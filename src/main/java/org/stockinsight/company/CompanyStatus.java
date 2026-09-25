package org.stockinsight.company;

public enum CompanyStatus {
    /** 대상 종목 범위 안의 상장사. 이후 수집·분석 대상이다. */
    ACTIVE,
    /** 상장사지만 대상 종목 범위 밖이다 (KONEX, 스팩, 리츠 등). */
    EXCLUDED,
    /** 상장폐지. 페이지는 유지하고 수집·분석을 멈춘다. */
    DELISTED
}
