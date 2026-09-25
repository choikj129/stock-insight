package org.stockinsight.analysis;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

import org.stockinsight.financial.PeriodKey;

/**
 * 기간 라벨과 값 표시 형식(코드가 만든다, ai-analysis.md §4.4.2). AI는 이 라벨을 {@code {per.*}} 토큰으로만 쓴다.
 */
final class PeriodLabels {

    private PeriodLabels() {
    }

    /** 12월 결산은 "2026년 2분기", 그 밖은 "2026.04~06(1분기)". */
    static String ofQuarter(PeriodKey key, Integer fiscalMonth) {
        if (fiscalMonth != null && fiscalMonth == 12) {
            return key.fiscalYearStart().getYear() + "년 " + key.quarterNumber() + "분기";
        }
        LocalDate start = key.fiscalYearStart().plusMonths(3L * (key.quarterNumber() - 1));
        LocalDate end = start.plusMonths(3).minusDays(1);
        String range = start.getYear() == end.getYear()
                ? "%d.%02d~%02d".formatted(start.getYear(), start.getMonthValue(), end.getMonthValue())
                : "%d.%02d~%d.%02d".formatted(start.getYear(), start.getMonthValue(), end.getYear(), end.getMonthValue());
        return range + "(" + key.quarterNumber() + "분기)";
    }

    /** 12월 결산은 "2025년(연간)", 그 밖은 "2025.04~2026.03 회계연도". */
    static String ofAnnual(LocalDate fiscalYearStart, Integer fiscalMonth) {
        if (fiscalMonth != null && fiscalMonth == 12) {
            return fiscalYearStart.getYear() + "년(연간)";
        }
        LocalDate end = fiscalYearStart.plusYears(1).minusDays(1);
        return "%d.%02d~%d.%02d 회계연도".formatted(fiscalYearStart.getYear(), fiscalYearStart.getMonthValue(),
                end.getYear(), end.getMonthValue());
    }

    /** 표시 값: 비율은 부호 있는 %(소수 1자리), 개수는 정수, 금액은 억·조 단위. */
    static String formatValue(BigDecimal value, String unit) {
        return switch (unit) {
            case "%", "%p" -> signed(value.setScale(1, RoundingMode.HALF_UP)) + unit;
            case "분기" -> value.abs().stripTrailingZeros().toPlainString() + "분기";
            default -> formatAmount(value, unit);
        };
    }

    private static String signed(BigDecimal value) {
        return (value.signum() >= 0 ? "+" : "") + value.toPlainString();
    }

    private static String formatAmount(BigDecimal value, String currency) {
        if (!"KRW".equals(currency)) {
            return value.toPlainString() + currency;
        }
        BigDecimal abs = value.abs();
        BigDecimal trillion = BigDecimal.valueOf(1_000_000_000_000L);
        BigDecimal hundredMillion = BigDecimal.valueOf(100_000_000L);
        String sign = value.signum() < 0 ? "-" : "";
        if (abs.compareTo(trillion) >= 0) {
            return sign + abs.divide(trillion, 1, RoundingMode.HALF_UP).toPlainString() + "조원";
        }
        if (abs.compareTo(hundredMillion) >= 0) {
            return sign + abs.divide(hundredMillion, 1, RoundingMode.HALF_UP).toPlainString() + "억원";
        }
        return sign + abs.toPlainString() + "원";
    }
}
