package org.stockinsight.financial;

/**
 * 다중회사 주요계정 응답의 계정 한 줄 (수집기가 {@code DartKeyAccount}에서 옮겨 담는다). 값은 원천 문자열 그대로다.
 */
public record RawAccountLine(
        String fsDiv,
        String statement,
        String accountName,
        String ord,
        String currentPeriod,
        String currentAmount,
        String currentCumulativeAmount,
        String priorAmount,
        String priorCumulativeAmount,
        String prior2Amount,
        String currency,
        String receiptNo) {
}
