package org.stockinsight.analysis;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Map;

import org.stockinsight.financial.PeriodKey;

/**
 * 기간 라벨과 값 표시 형식(코드가 만든다, docs/spec/financial-explain.md §4.4.2). AI는 이 라벨을 {@code {per.*}} 토큰으로만 쓴다.
 */
final class PeriodLabels {

    private PeriodLabels() {
    }

    /**
     * 12월 결산은 "2026년 2분기", 그 밖은 "2026년 4~6월"(D-59: 회계연도 분기 번호 "(1분기)"는 흔히 쓰는 달력 분기와
     * 부딪혀 빼고 달만 쓴다). 연도를 넘는 분기는 "2025년 11월~2026년 1월"처럼 달마다 연도를 붙인다.
     */
    static String ofQuarter(PeriodKey key, Integer fiscalMonth) {
        if (fiscalMonth != null && fiscalMonth == 12) {
            return key.fiscalYearStart().getYear() + "년 " + key.quarterNumber() + "분기";
        }
        LocalDate start = key.fiscalYearStart().plusMonths(3L * (key.quarterNumber() - 1));
        LocalDate end = start.plusMonths(3).minusDays(1);
        return start.getYear() == end.getYear()
                ? "%d년 %d~%d월".formatted(start.getYear(), start.getMonthValue(), end.getMonthValue())
                : "%d년 %d월~%d년 %d월".formatted(start.getYear(), start.getMonthValue(), end.getYear(), end.getMonthValue());
    }

    /**
     * 12월 결산이고 12개월짜리 회계연도면 "2025년(연간)", 그 밖의 12개월짜리는 "2025.04~2026.03 회계연도".
     * 12개월이 아닌 회계연도(결산기 변경 등, D-59)는 실제 시작~끝 달로 "2025.11~12 회계연도"처럼 쓴다 — "(연간)"이라고
     * 하면 12개월 치인 것처럼 읽힌다(2386 사례, docs/work/3-4-verification-2.md §7.4.33).
     */
    static String ofAnnual(LocalDate fiscalYearStart, LocalDate periodEnd, Integer fiscalMonth) {
        boolean irregular = periodEnd != null
                && Math.abs(java.time.Period.between(fiscalYearStart, periodEnd.plusDays(1)).toTotalMonths() - 12) >= 1;
        if (!irregular) {
            if (fiscalMonth != null && fiscalMonth == 12) {
                return fiscalYearStart.getYear() + "년(연간)";
            }
            LocalDate end = fiscalYearStart.plusYears(1).minusDays(1);
            return "%d.%02d~%d.%02d 회계연도".formatted(fiscalYearStart.getYear(), fiscalYearStart.getMonthValue(),
                    end.getYear(), end.getMonthValue());
        }
        return (fiscalYearStart.getYear() == periodEnd.getYear()
                ? "%d.%02d~%02d".formatted(fiscalYearStart.getYear(), fiscalYearStart.getMonthValue(), periodEnd.getMonthValue())
                : "%d.%02d~%d.%02d".formatted(fiscalYearStart.getYear(), fiscalYearStart.getMonthValue(),
                        periodEnd.getYear(), periodEnd.getMonthValue()))
                + " 회계연도";
    }

    /** 표시 값: 비율은 부호 있는 %(소수 1자리), 개수는 정수, 금액은 억·조 단위. */
    static String formatValue(BigDecimal value, String unit) {
        return switch (unit) {
            case "%", "%p" -> signed(value.setScale(1, RoundingMode.HALF_UP)) + unit;
            case "분기" -> value.abs().stripTrailingZeros().toPlainString() + "분기";
            default -> formatAmount(value, unit);
        };
    }

    /**
     * 사용자에게 보이는 표시 값(렌더링 때, D-41·D-56). {@link #formatValue}와 같되, 수준 비율(영업이익률·부채비율 등
     * {@code _yoy}가 아닌 %)에는 '+'를 붙이지 않는다 — 부호가 붙은 비율은 변화량으로 읽힌다. 음수의 '-'는 값이라 남긴다.
     * AI 입력의 표시 값은 아직 {@link #formatValue}를 쓴다(다음 입력 구성 변경 때 맞춘다, D-56).
     */
    static String formatForReader(String metric, BigDecimal value, String unit) {
        if ("%".equals(unit) && !metric.endsWith("_yoy")) {
            return value.setScale(1, RoundingMode.HALF_UP).toPlainString() + unit;
        }
        return formatValue(value, unit);
    }

    /**
     * AI 문장 안에 토큰으로 박히는 값(D-58). 변화량(`_yoy`의 %, 모든 %p)은 부호를 완전히 뺀다("69.3% 줄었고",
     * "28.7%p 높아졌어요") — 방향은 같은 절의 증감 어휘가 말하고 검증기 규칙 6이 어휘와 부호의 일치를 보장하므로, 부호는
     * 겹말이다("-69.3% 줄었어요"). 수준값(그 밖의 %·금액)은 {@link #formatForReader}와 같다 — 음수는 값이라 남는다.
     */
    static String formatForSentence(String metric, BigDecimal value, String unit) {
        if (isChangeQuantity(metric, unit)) {
            return value.abs().setScale(1, RoundingMode.HALF_UP).toPlainString() + unit;
        }
        return formatForReader(metric, value, unit);
    }

    private static boolean isChangeQuantity(String metric, String unit) {
        return "%p".equals(unit) || ("%".equals(unit) && metric.endsWith("_yoy"));
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
