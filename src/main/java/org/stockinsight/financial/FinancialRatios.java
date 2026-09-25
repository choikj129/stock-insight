package org.stockinsight.financial;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * 재무 비율 계산과 그 기준값. 신호 계산기(`signal`)와 AI 입력 사실표(`analysis`)가 같은 코드를 써서
 * 두 곳의 숫자가 어긋나지 않게 한다(D-38, implementation-plan.md §7.2).
 */
public final class FinancialRatios {

    /** 매출 비교 기준값: 분기 10억, 연간 40억 (미만이면 증가율을 계산하지 않는다). ai-analysis.md §3.6, §3.7. */
    public static final BigDecimal REVENUE_BASE_QUARTER = new BigDecimal("1000000000");
    public static final BigDecimal REVENUE_BASE_ANNUAL = new BigDecimal("4000000000");

    /** 재무상태표 항등식(자산 = 부채 + 자본) 허용 오차(비율). ai-analysis.md §3.6. */
    public static final BigDecimal BALANCE_TOLERANCE = new BigDecimal("0.005");

    private FinancialRatios() {
    }

    /** 전기(또는 전년 동기) 대비 증가율(%). prior가 없거나 0이면 호출하지 않는다(사유 판정은 별도). */
    public static BigDecimal percentChange(BigDecimal current, BigDecimal prior) {
        return current.subtract(prior).divide(prior, 6, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100));
    }

    /** numerator / denominator * 100. */
    public static BigDecimal percentOf(BigDecimal numerator, BigDecimal denominator) {
        return numerator.divide(denominator, 6, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100));
    }

    /** 영업이익률(%) = 영업이익 ÷ 매출액. 매출액이 없거나 0 이하면 계산하지 않는다(null). */
    public static BigDecimal margin(BigDecimal revenue, BigDecimal operatingIncome) {
        if (revenue == null || operatingIncome == null || revenue.signum() <= 0) {
            return null;
        }
        return percentOf(operatingIncome, revenue);
    }

    /** 부채비율(%) = 부채총계 ÷ 자본총계. 자본총계가 없거나 0 이하면 계산하지 않는다(자본잠식으로 판정, null). */
    public static BigDecimal debtRatio(BigDecimal totalLiabilities, BigDecimal totalEquity) {
        if (totalLiabilities == null || totalEquity == null || totalEquity.signum() <= 0) {
            return null;
        }
        return percentOf(totalLiabilities, totalEquity);
    }

    /**
     * 잠식률(%) = (자본금 − 자본총계) ÷ 자본금. 자본금이 없거나 0 이하거나 잠식이 아니면(자본총계 ≥ 자본금) null.
     * 완전잠식(자본총계 ≤ 0)도 null이다 — 그 경우는 비율이 아니라 "완전자본잠식" 상태로 다룬다(신호 심각도가 이미 구분한다).
     */
    public static BigDecimal impairmentRatio(BigDecimal capitalStock, BigDecimal totalEquity) {
        if (capitalStock == null || totalEquity == null || capitalStock.signum() <= 0
                || totalEquity.signum() <= 0 || totalEquity.compareTo(capitalStock) >= 0) {
            return null;
        }
        return percentOf(capitalStock.subtract(totalEquity), capitalStock);
    }

    /** 분기 사이 간격 판단 허용 범위(일). 이 범위를 벗어나면 빈 기간(연속 끊김)으로 본다. */
    private static final long MIN_QUARTER_GAP_DAYS = 45;
    private static final long MAX_QUARTER_GAP_DAYS = 135;

    /** 두 분기가 바로 이어지는가(약 1분기 간격). 신호의 지속 계산과 사실표의 흐름 사실이 같은 기준을 쓴다. */
    public static boolean isConsecutiveQuarter(LocalDate earlierPeriodEnd, LocalDate laterPeriodEnd) {
        long days = ChronoUnit.DAYS.between(earlierPeriodEnd, laterPeriodEnd);
        return days >= MIN_QUARTER_GAP_DAYS && days <= MAX_QUARTER_GAP_DAYS;
    }

    /** 자산총계 = 부채총계 + 자본총계 (오차 {@link #BALANCE_TOLERANCE} 이내)인가. 값이 없으면 점검하지 않는다(일치로 본다). */
    public static boolean isBalanceConsistent(BigDecimal totalAssets, BigDecimal totalLiabilities, BigDecimal totalEquity) {
        if (totalAssets == null || totalLiabilities == null || totalEquity == null) {
            return true;
        }
        BigDecimal expected = totalLiabilities.add(totalEquity);
        BigDecimal diff = totalAssets.subtract(expected).abs();
        BigDecimal tolerance = totalAssets.abs().max(BigDecimal.ONE).multiply(BALANCE_TOLERANCE);
        return diff.compareTo(tolerance) <= 0;
    }
}
