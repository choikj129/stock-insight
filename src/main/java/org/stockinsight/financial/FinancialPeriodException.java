package org.stockinsight.financial;

/** 응답에서 회계기간을 식별할 수 없거나, 계기 공시가 가리키는 기간과 응답 기간이 다르다 (docs/implementation-plan.md §4.3, §4.4). */
public class FinancialPeriodException extends RuntimeException {

    public FinancialPeriodException(String message) {
        super(message);
    }
}
