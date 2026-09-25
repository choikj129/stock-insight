package org.stockinsight.financial;

import java.math.BigDecimal;

/** 주요계정 응답의 금액 문자열 해석. 쉼표가 든 정수 문자열, 음수는 {@code -} 접두, 값 없음은 {@code "-"}이거나 빈 문자열이다. */
final class FinancialAmounts {

    private FinancialAmounts() {
    }

    static BigDecimal parse(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.strip();
        if (value.isEmpty() || value.equals("-")) {
            return null;
        }
        return new BigDecimal(value.replace(",", ""));
    }
}
