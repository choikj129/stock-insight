package org.stockinsight.analysis;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Map;

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

    private static final BigDecimal TRILLION = BigDecimal.valueOf(1_000_000_000_000L);
    private static final BigDecimal HUNDRED_MILLION = BigDecimal.valueOf(100_000_000L);
    /** 만 단위 문턱값이자, 위 단위로 반올림 되어 올라가는 경계(10000)이기도 하다. */
    private static final BigDecimal TEN_THOUSAND = BigDecimal.valueOf(10_000L);

    /** 통화명(이름이 없는 통화는 코드를 그대로 쓴다, D-41). */
    private static final Map<String, String> CURRENCY_NAMES = Map.of(
            "KRW", "원", "CNY", "위안", "USD", "달러", "JPY", "엔", "GBP", "파운드");

    /**
     * 통화와 관계없이 한국어 수 단위(조·억·만)로 줄이고 통화명을 붙인다. 환산하지 않는다(D-41).
     * 반올림으로 한 단위의 끝(10000)에 닿으면 그 위 단위로 올린다(예: 9,999.95억 → 1.0조).
     */
    private static String formatAmount(BigDecimal value, String currency) {
        BigDecimal abs = value.abs();
        String sign = value.signum() < 0 ? "-" : "";
        String suffix = CURRENCY_NAMES.containsKey(currency) ? CURRENCY_NAMES.get(currency) : " " + currency;

        if (abs.compareTo(TRILLION) >= 0) {
            return sign + grouped(abs.divide(TRILLION, 1, RoundingMode.HALF_UP), 1) + "조" + suffix;
        }
        if (abs.compareTo(HUNDRED_MILLION) >= 0) {
            BigDecimal eok = abs.divide(HUNDRED_MILLION, 1, RoundingMode.HALF_UP);
            if (eok.compareTo(TEN_THOUSAND) >= 0) {
                return sign + grouped(abs.divide(TRILLION, 1, RoundingMode.HALF_UP), 1) + "조" + suffix;
            }
            return sign + grouped(eok, 1) + "억" + suffix;
        }
        if (abs.compareTo(TEN_THOUSAND) >= 0) {
            BigDecimal man = abs.divide(TEN_THOUSAND, 0, RoundingMode.HALF_UP);
            if (man.compareTo(TEN_THOUSAND) >= 0) {
                return sign + grouped(abs.divide(HUNDRED_MILLION, 1, RoundingMode.HALF_UP), 1) + "억" + suffix;
            }
            return sign + grouped(man, 0) + "만" + suffix;
        }
        return sign + grouped(abs.setScale(0, RoundingMode.HALF_UP), 0) + suffix;
    }

    private static String grouped(BigDecimal value, int decimals) {
        return String.format(Locale.US, "%,." + decimals + "f", value);
    }
}
