package org.stockinsight.financial;

import java.math.BigDecimal;
import java.util.Objects;

/** 저장된 계정 한 줄. */
public record StoredFinancialLine(
        int ord,
        String statement,
        String accountName,
        BigDecimal currentAmount,
        BigDecimal currentCumulativeAmount,
        BigDecimal priorAmount,
        BigDecimal priorCumulativeAmount,
        BigDecimal prior2Amount) {

    /** 계정명·금액이 같은지 비교한다 (BigDecimal은 scale이 달라도 값이 같으면 같다고 본다). */
    boolean sameContentAs(StoredFinancialLine other) {
        return ord == other.ord
                && statement.equals(other.statement)
                && accountName.equals(other.accountName)
                && amountEquals(currentAmount, other.currentAmount)
                && amountEquals(currentCumulativeAmount, other.currentCumulativeAmount)
                && amountEquals(priorAmount, other.priorAmount)
                && amountEquals(priorCumulativeAmount, other.priorCumulativeAmount)
                && amountEquals(prior2Amount, other.prior2Amount);
    }

    private static boolean amountEquals(BigDecimal a, BigDecimal b) {
        if (a == null || b == null) {
            return Objects.equals(a, b);
        }
        return a.compareTo(b) == 0;
    }
}
