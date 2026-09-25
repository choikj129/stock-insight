package org.stockinsight.financial;

/** 재무제표 형식. 유동자산 유무로 코드가 판별한다(산업코드를 쓰지 않는다, architecture.md §4.3). */
public enum FinancialFormat {
    GENERAL, FINANCIAL
}
