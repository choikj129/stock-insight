package org.stockinsight.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;
import org.stockinsight.financial.PeriodKey;

/**
 * 금액 표시 형식의 경계를 검증한다(D-41, implementation-plan.md §8.5). 통화와 관계없이 한국어 수 단위(조·억·만)로
 * 줄이고 통화명을 붙이며, 환산하지 않는다.
 */
class PeriodLabelsTest {

    @Test
    void zeroHasNoUnit() {
        assertThat(PeriodLabels.formatValue(BigDecimal.ZERO, "KRW")).isEqualTo("0원");
    }

    @Test
    void negativeAmountKeepsSign() {
        assertThat(PeriodLabels.formatValue(new BigDecimal("-53254120"), "KRW")).isEqualTo("-5,325만원");
    }

    @Test
    void belowTenThousandIsAGroupedInteger() {
        assertThat(PeriodLabels.formatValue(new BigDecimal("9999"), "KRW")).isEqualTo("9,999원");
    }

    @Test
    void tenThousandBecomesMan() {
        assertThat(PeriodLabels.formatValue(new BigDecimal("10000"), "KRW")).isEqualTo("1만원");
    }

    @Test
    void justBelowEokStaysInMan() {
        // 9999만원(반올림 경계에서 멀리 떨어진 값): 아직 억 단위로 올리지 않는다.
        assertThat(PeriodLabels.formatValue(new BigDecimal("99990000"), "KRW")).isEqualTo("9,999만원");
    }

    @Test
    void oneHundredMillionBecomesEok() {
        assertThat(PeriodLabels.formatValue(new BigDecimal("100000000"), "KRW")).isEqualTo("1.0억원");
    }

    @Test
    void justBelowOneHundredMillionRoundsUpToEok() {
        // 100,000,000원 바로 밑(99,999,999원 = 9999.9999만)도 반올림하면 10000만이라 억으로 올라간다.
        assertThat(PeriodLabels.formatValue(new BigDecimal("99999999"), "KRW")).isEqualTo("1.0억원");
    }

    @Test
    void justBelowJoStaysInEok() {
        // 9999억원(반올림 경계에서 멀리 떨어진 값): 아직 조 단위로 올리지 않는다.
        assertThat(PeriodLabels.formatValue(new BigDecimal("999900000000"), "KRW")).isEqualTo("9,999.0억원");
    }

    @Test
    void oneTrillionBecomesJo() {
        assertThat(PeriodLabels.formatValue(new BigDecimal("1000000000000"), "KRW")).isEqualTo("1.0조원");
    }

    @Test
    void roundingCarriesOverFromEokToJo() {
        // 9,999.95억 = 999,995,000,000원. 억 단위로 반올림하면 10000.0억이 되므로 조 단위로 올린다.
        assertThat(PeriodLabels.formatValue(new BigDecimal("999995000000"), "KRW")).isEqualTo("1.0조원");
        // 1조 바로 밑(999,999,999,999원 = 9999.99999999억)도 반올림하면 10000.0억이라 조로 올라간다.
        assertThat(PeriodLabels.formatValue(new BigDecimal("999999999999"), "KRW")).isEqualTo("1.0조원");
    }

    @Test
    void roundingCarriesOverFromManToEok() {
        // 9,999.5만 = 99,995,000원. 만 단위로 반올림하면 10000만이 되므로 억 단위로 올린다.
        assertThat(PeriodLabels.formatValue(new BigDecimal("99995000"), "KRW")).isEqualTo("1.0억원");
    }

    @Test
    void namedCurrenciesUseKoreanNameWithoutSpace() {
        assertThat(PeriodLabels.formatValue(new BigDecimal("1370000000"), "CNY")).isEqualTo("13.7억위안");
        assertThat(PeriodLabels.formatValue(new BigDecimal("120000000"), "USD")).isEqualTo("1.2억달러");
        assertThat(PeriodLabels.formatValue(new BigDecimal("34560000"), "JPY")).isEqualTo("3,456만엔");
        assertThat(PeriodLabels.formatValue(new BigDecimal("210000000"), "GBP")).isEqualTo("2.1억파운드");
    }

    @Test
    void unnamedCurrencyUsesCodeWithLeadingSpace() {
        assertThat(PeriodLabels.formatValue(new BigDecimal("1230000000"), "CHF")).isEqualTo("12.3억 CHF");
    }

    // ---- D-59: 기간 라벨 — 비12월 결산 분기는 달만, 12개월이 아닌 회계연도는 실제 기간 ----

    @Test
    void decemberFiscalYearQuarterKeepsFiscalQuarterNumber() {
        assertThat(PeriodLabels.ofQuarter(new PeriodKey(LocalDate.of(2026, 1, 1), 2), 12)).isEqualTo("2026년 2분기");
    }

    @Test
    void nonDecemberFiscalYearQuarterUsesMonthsOnlyWithoutFiscalQuarterNumber() {
        // 608·810 사람 검토: "2026.01~03(3분기)"는 회계연도 분기 번호와 달력 분기가 부딪힌다(§7.4.33).
        assertThat(PeriodLabels.ofQuarter(new PeriodKey(LocalDate.of(2025, 7, 1), 3), 6)).isEqualTo("2026년 1~3월");
        assertThat(PeriodLabels.ofQuarter(new PeriodKey(LocalDate.of(2026, 4, 1), 1), 3)).isEqualTo("2026년 4~6월");
    }

    @Test
    void quarterCrossingCalendarYearNamesEachMonthWithItsOwnYear() {
        assertThat(PeriodLabels.ofQuarter(new PeriodKey(LocalDate.of(2025, 9, 1), 1), 9)).isEqualTo("2025년 9~11월");
        assertThat(PeriodLabels.ofQuarter(new PeriodKey(LocalDate.of(2025, 9, 1), 2), 9)).isEqualTo("2025년 12월~2026년 2월");
    }

    @Test
    void regularAnnualPeriodsAreUnaffectedByTheIrregularCheck() {
        LocalDate start = LocalDate.of(2025, 1, 1);
        assertThat(PeriodLabels.ofAnnual(start, LocalDate.of(2025, 12, 31), 12)).isEqualTo("2025년(연간)");
        LocalDate julyStart = LocalDate.of(2025, 7, 1);
        assertThat(PeriodLabels.ofAnnual(julyStart, LocalDate.of(2026, 6, 30), 6)).isEqualTo("2025.07~2026.06 회계연도");
        // periodEnd가 없는(옛 스냅샷 계열) 호출도 12개월로 본다.
        assertThat(PeriodLabels.ofAnnual(start, null, 12)).isEqualTo("2025년(연간)");
    }

    @Test
    void irregularAnnualPeriodUsesActualMonthsInsteadOfAnnualWording() {
        // 2386: 분할 신설 뒤 첫 회계연도가 2개월이다 — "2025년(연간)"이라고 하면 1년 치처럼 읽힌다(§7.4.33).
        assertThat(PeriodLabels.ofAnnual(LocalDate.of(2025, 11, 1), LocalDate.of(2025, 12, 31), 12))
                .isEqualTo("2025.11~12 회계연도");
    }

    @Test
    void irregularAnnualPeriodCrossingCalendarYearsUsesTwoYearRange() {
        assertThat(PeriodLabels.ofAnnual(LocalDate.of(2025, 11, 1), LocalDate.of(2026, 1, 31), 12))
                .isEqualTo("2025.11~2026.01 회계연도");
    }
}
