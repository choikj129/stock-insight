package org.stockinsight.signal;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.stockinsight.financial.AnnualEntry;
import org.stockinsight.financial.FinancialFormat;
import org.stockinsight.financial.FinancialSummary;
import org.stockinsight.financial.MetricValue;
import org.stockinsight.financial.PeriodKey;
import org.stockinsight.financial.PeriodType;
import org.stockinsight.financial.QuarterEntry;
import org.stockinsight.financial.SummaryFlag;

/**
 * 재무 신호 판정의 문턱값 경계를 검증한다(D-35, ai-analysis.md §3.7). 값은 implementation-plan.md §6.1의 실제 분포로 정했다.
 */
class FinancialSignalCalculatorTest {

    private static final LocalDate FY_START = LocalDate.of(2026, 1, 1);
    private static final LocalDate ASOF = LocalDate.of(2026, 9, 25);
    private static final BigDecimal BASE = new BigDecimal("2000000000"); // 20억 (분기 기준값 10억 이상)

    @Test
    void revenueChangeJustBelowThresholdMakesNoSignal() {
        // +29.9%
        BigDecimal current = BASE.multiply(new BigDecimal("1.299"));
        FinancialSummary summary = oneQuarterSummary(revenue(current, BASE), MetricValue.EMPTY);

        assertThat(signalsOf(summary, SignalType.FIN_REVENUE_CHANGE)).isEmpty();
    }

    @Test
    void revenueChangeAtThresholdIsLowSeverity() {
        BigDecimal current = BASE.multiply(new BigDecimal("1.30"));
        FinancialSummary summary = oneQuarterSummary(revenue(current, BASE), MetricValue.EMPTY);

        List<SignalDraft> signals = signalsOf(summary, SignalType.FIN_REVENUE_CHANGE);
        assertThat(signals).hasSize(1);
        assertThat(signals.get(0).direction()).isEqualTo(SignalDirection.POSITIVE);
        assertThat(signals.get(0).severity()).isEqualTo(SignalSeverity.LOW);
    }

    @Test
    void revenueDropJustAboveThresholdMakesNoSignal() {
        // -19.9%
        BigDecimal current = BASE.multiply(new BigDecimal("0.801"));
        FinancialSummary summary = oneQuarterSummary(revenue(current, BASE), MetricValue.EMPTY);

        assertThat(signalsOf(summary, SignalType.FIN_REVENUE_CHANGE)).isEmpty();
    }

    @Test
    void revenueDropAtThresholdIsLowSeverity() {
        BigDecimal current = BASE.multiply(new BigDecimal("0.80"));
        FinancialSummary summary = oneQuarterSummary(revenue(current, BASE), MetricValue.EMPTY);

        List<SignalDraft> signals = signalsOf(summary, SignalType.FIN_REVENUE_CHANGE);
        assertThat(signals).hasSize(1);
        assertThat(signals.get(0).direction()).isEqualTo(SignalDirection.NEGATIVE);
        assertThat(signals.get(0).severity()).isEqualTo(SignalSeverity.LOW);
    }

    @Test
    void revenueBelowBaseValueIsExcludedEvenWithLargeChange() {
        BigDecimal smallPrior = new BigDecimal("500000000"); // 5억, 기준값(10억) 미만
        BigDecimal current = smallPrior.multiply(new BigDecimal("10"));
        FinancialSummary summary = oneQuarterSummary(revenue(current, smallPrior), MetricValue.EMPTY);

        assertThat(signalsOf(summary, SignalType.FIN_REVENUE_CHANGE)).isEmpty();
    }

    @Test
    void financialFormatExcludesRevenueAndMarginSignals() {
        QuarterEntry q = quarter(1, revenue(BASE.multiply(new BigDecimal("2")), BASE),
                income(BASE, BASE.multiply(new BigDecimal("0.1"))), FinancialFormat.FINANCIAL, true);
        FinancialSummary summary = summaryOf(q);

        assertThat(signalsOf(summary, SignalType.FIN_REVENUE_CHANGE)).isEmpty();
        assertThat(signalsOf(summary, SignalType.FIN_OPERATING_MARGIN_CHANGE)).isEmpty();
    }

    @Test
    void nonKrwExcludesRevenueAndMarginSignals() {
        QuarterEntry q = quarter(1, revenue(BASE.multiply(new BigDecimal("2")), BASE),
                income(BASE, BASE.multiply(new BigDecimal("0.1"))), FinancialFormat.GENERAL, true);
        FinancialSummary summary = new FinancialSummary(1L, true, "CFS", "USD", q.periodEnd(), "R1", List.of(),
                List.of(q), List.of());

        assertThat(signalsOf(summary, SignalType.FIN_REVENUE_CHANGE)).isEmpty();
        assertThat(signalsOf(summary, SignalType.FIN_OPERATING_MARGIN_CHANGE)).isEmpty();
    }

    @Test
    void marginDiffJustBelowThresholdMakesNoSignal() {
        // 매출 고정, 영업이익률 차이 9.9%p: 전기 10%, 당기 19.9%
        MetricValue revenue = revenue(BASE, BASE);
        MetricValue operating = income(BASE.multiply(new BigDecimal("0.199")), BASE.multiply(new BigDecimal("0.10")));
        FinancialSummary summary = oneQuarterSummary(revenue, operating);

        assertThat(signalsOf(summary, SignalType.FIN_OPERATING_MARGIN_CHANGE)).isEmpty();
    }

    @Test
    void marginDiffAtThresholdIsLowSeverity() {
        MetricValue revenue = revenue(BASE, BASE);
        MetricValue operating = income(BASE.multiply(new BigDecimal("0.20")), BASE.multiply(new BigDecimal("0.10")));
        FinancialSummary summary = oneQuarterSummary(revenue, operating);

        List<SignalDraft> signals = signalsOf(summary, SignalType.FIN_OPERATING_MARGIN_CHANGE);
        assertThat(signals).hasSize(1);
        assertThat(signals.get(0).direction()).isEqualTo(SignalDirection.POSITIVE);
        assertThat(signals.get(0).severity()).isEqualTo(SignalSeverity.LOW);
    }

    @Test
    void turnMarginJustBelowThresholdMakesNoSignal() {
        // 영업이익률 1.9% (매출 대비), 부호는 반대
        BigDecimal revenueAmount = BASE;
        MetricValue revenue = revenue(revenueAmount, revenueAmount);
        MetricValue operating = income(revenueAmount.multiply(new BigDecimal("0.019")), revenueAmount.multiply(new BigDecimal("-0.05")));
        FinancialSummary summary = oneQuarterSummary(revenue, operating);

        assertThat(signalsOf(summary, SignalType.FIN_OPERATING_TURN)).isEmpty();
    }

    @Test
    void turnMarginAtThresholdSignals() {
        BigDecimal revenueAmount = BASE;
        MetricValue revenue = revenue(revenueAmount, revenueAmount);
        MetricValue operating = income(revenueAmount.multiply(new BigDecimal("0.02")), revenueAmount.multiply(new BigDecimal("-0.05")));
        FinancialSummary summary = oneQuarterSummary(revenue, operating);

        List<SignalDraft> signals = signalsOf(summary, SignalType.FIN_OPERATING_TURN);
        assertThat(signals).hasSize(1);
        assertThat(signals.get(0).direction()).isEqualTo(SignalDirection.POSITIVE);
    }

    @Test
    void turnAndMarginChangeAreMutuallyExclusive() {
        BigDecimal revenueAmount = BASE;
        MetricValue revenue = revenue(revenueAmount, revenueAmount);
        // 부호가 반대이고 양쪽 다 |margin| >= 2% 이므로 전환 신호가 나오고, 영업이익률 변화는 억제되어야 한다.
        MetricValue operating = income(revenueAmount.multiply(new BigDecimal("0.05")), revenueAmount.multiply(new BigDecimal("-0.05")));
        FinancialSummary summary = oneQuarterSummary(revenue, operating);

        assertThat(signalsOf(summary, SignalType.FIN_OPERATING_TURN)).hasSize(1);
        assertThat(signalsOf(summary, SignalType.FIN_OPERATING_MARGIN_CHANGE)).isEmpty();
    }

    @Test
    void debtRatioBelowLevelMakesNoSignalEvenWithLargeJump() {
        // 199% (기준 200% 미만), 전기 대비 +100%p
        QuarterEntry q = quarterWithBalance(1, new BigDecimal("199"), new BigDecimal("99"));
        FinancialSummary summary = summaryOf(q);

        assertThat(signalsOf(summary, SignalType.FIN_DEBT_RATIO_JUMP)).isEmpty();
    }

    @Test
    void debtRatioAtLevelAndJumpSignals() {
        QuarterEntry q = quarterWithBalance(1, new BigDecimal("200"), new BigDecimal("150"));
        FinancialSummary summary = summaryOf(q);

        List<SignalDraft> signals = signalsOf(summary, SignalType.FIN_DEBT_RATIO_JUMP);
        assertThat(signals).hasSize(1);
        assertThat(signals.get(0).severity()).isEqualTo(SignalSeverity.MEDIUM);
    }

    @Test
    void debtRatioJumpJustBelow50PointsMakesNoSignal() {
        // 기간 말 250% (>=200 만족), 전기 대비 +49.9%p
        QuarterEntry q = quarterWithBalance(1, new BigDecimal("250"), new BigDecimal("200.1"));
        FinancialSummary summary = summaryOf(q);

        assertThat(signalsOf(summary, SignalType.FIN_DEBT_RATIO_JUMP)).isEmpty();
    }

    @Test
    void capitalImpairmentPartialIsMedium() {
        QuarterEntry q = quarterWithCapital(1, new BigDecimal("900"), new BigDecimal("1000")); // 잠식률 10%
        FinancialSummary summary = summaryOf(q);

        List<SignalDraft> signals = signalsOf(summary, SignalType.FIN_CAPITAL_IMPAIRMENT);
        assertThat(signals).hasSize(1);
        assertThat(signals.get(0).severity()).isEqualTo(SignalSeverity.MEDIUM);
    }

    @Test
    void capitalImpairmentCompleteIsHigh() {
        QuarterEntry q = quarterWithCapital(1, new BigDecimal("-100"), new BigDecimal("1000"));
        FinancialSummary summary = summaryOf(q);

        List<SignalDraft> signals = signalsOf(summary, SignalType.FIN_CAPITAL_IMPAIRMENT);
        assertThat(signals).hasSize(1);
        assertThat(signals.get(0).severity()).isEqualTo(SignalSeverity.HIGH);
    }

    @Test
    void operatingLossStreakNeedsFourQuarters() {
        List<QuarterEntry> threeLosses = List.of(
                lossQuarter(1, LocalDate.of(2025, 3, 31)),
                lossQuarter(2, LocalDate.of(2025, 6, 30)),
                lossQuarter(3, LocalDate.of(2025, 9, 30)));
        FinancialSummary threeSummary = summaryOf(reversed(threeLosses));
        assertThat(signalsOf(threeSummary, SignalType.FIN_OPERATING_LOSS_STREAK)).isEmpty();

        List<QuarterEntry> fourLosses = List.of(
                lossQuarter(1, LocalDate.of(2025, 3, 31)),
                lossQuarter(2, LocalDate.of(2025, 6, 30)),
                lossQuarter(3, LocalDate.of(2025, 9, 30)),
                lossQuarterAt(LocalDate.of(2025, 1, 1), 4, LocalDate.of(2025, 12, 31)));
        FinancialSummary fourSummary = summaryOf(reversed(fourLosses));
        List<SignalDraft> signals = signalsOf(fourSummary, SignalType.FIN_OPERATING_LOSS_STREAK);
        assertThat(signals).hasSize(1);
        assertThat(signals.get(0).persistence()).isEqualTo(4);
        assertThat(signals.get(0).active()).isTrue();
    }

    @Test
    void operatingLossStreakBreaksOnGapAndOnlyCountsAfterTheGap() {
        // 처음 2분기 적자 뒤 9개월 공백(빈 기간), 그 뒤 다시 4분기 연속 적자.
        List<QuarterEntry> ascending = List.of(
                lossQuarter(1, LocalDate.of(2024, 3, 31)),
                lossQuarter(2, LocalDate.of(2024, 6, 30)),
                lossQuarterAt(LocalDate.of(2025, 1, 1), 1, LocalDate.of(2025, 3, 31)),
                lossQuarterAt(LocalDate.of(2025, 1, 1), 2, LocalDate.of(2025, 6, 30)),
                lossQuarterAt(LocalDate.of(2025, 1, 1), 3, LocalDate.of(2025, 9, 30)),
                lossQuarterAt(LocalDate.of(2025, 1, 1), 4, LocalDate.of(2025, 12, 31)));
        FinancialSummary summary = summaryOf(reversed(ascending));

        List<SignalDraft> signals = signalsOf(summary, SignalType.FIN_OPERATING_LOSS_STREAK);
        // 공백 앞의 2분기는 4분기에 못 미쳐 신호가 없고, 공백 뒤 4분기 연속만 신호가 된다.
        assertThat(signals).hasSize(1);
        assertThat(signals.get(0).persistence()).isEqualTo(4);
    }

    private static List<QuarterEntry> reversed(List<QuarterEntry> ascending) {
        List<QuarterEntry> copy = new java.util.ArrayList<>(ascending);
        java.util.Collections.reverse(copy);
        return copy;
    }

    // ---- 헬퍼 ----

    private static List<SignalDraft> signalsOf(FinancialSummary summary, SignalType type) {
        return FinancialSignalCalculator.calculate(summary, ASOF).stream()
                .filter(d -> d.type() == type)
                .toList();
    }

    private static FinancialSummary oneQuarterSummary(MetricValue revenue, MetricValue operatingIncome) {
        QuarterEntry q = quarter(1, revenue, operatingIncome, FinancialFormat.GENERAL, true);
        return summaryOf(q);
    }

    private static FinancialSummary summaryOf(QuarterEntry q) {
        return new FinancialSummary(1L, true, "CFS", "KRW", q.periodEnd(), q.receiptNo(), List.of(), List.of(q), List.of());
    }

    private static FinancialSummary summaryOf(List<QuarterEntry> quartersDesc) {
        QuarterEntry latest = quartersDesc.get(0);
        return new FinancialSummary(1L, true, "CFS", "KRW", latest.periodEnd(), latest.receiptNo(), List.of(), quartersDesc, List.of());
    }

    private static QuarterEntry quarter(int quarterNumber, MetricValue revenue, MetricValue operatingIncome,
            FinancialFormat format, boolean balanceConsistent) {
        PeriodType reportType = switch (quarterNumber) {
            case 1 -> PeriodType.Q1;
            case 2 -> PeriodType.H1;
            case 3 -> PeriodType.Q3;
            default -> PeriodType.FY;
        };
        LocalDate periodEnd = FY_START.plusMonths(3L * quarterNumber).minusDays(1);
        return new QuarterEntry(new PeriodKey(FY_START, quarterNumber), reportType, periodEnd, "R1", false, true,
                format, balanceConsistent, revenue, operatingIncome, MetricValue.EMPTY,
                MetricValue.EMPTY, MetricValue.EMPTY, MetricValue.EMPTY, MetricValue.EMPTY);
    }

    private static QuarterEntry quarterWithBalance(int quarterNumber, BigDecimal currentRatioPct, BigDecimal priorRatioPct) {
        // 자본 100으로 고정하고 부채를 비율에 맞춰 정한다(자산총계는 검사하지 않으므로 임의로 둔다).
        BigDecimal equity = new BigDecimal("100");
        BigDecimal liabilitiesCurrent = equity.multiply(currentRatioPct).divide(new BigDecimal("100"));
        BigDecimal liabilitiesPrior = equity.multiply(priorRatioPct).divide(new BigDecimal("100"));
        QuarterEntry base = quarter(quarterNumber, MetricValue.EMPTY, MetricValue.EMPTY, FinancialFormat.GENERAL, true);
        return new QuarterEntry(base.key(), base.reportType(), base.periodEnd(), base.receiptNo(), false, true,
                FinancialFormat.GENERAL, true, MetricValue.EMPTY, MetricValue.EMPTY, MetricValue.EMPTY,
                MetricValue.EMPTY, new MetricValue(liabilitiesCurrent, liabilitiesPrior), new MetricValue(equity, equity),
                MetricValue.EMPTY);
    }

    private static QuarterEntry quarterWithCapital(int quarterNumber, BigDecimal equity, BigDecimal capitalStock) {
        QuarterEntry base = quarter(quarterNumber, MetricValue.EMPTY, MetricValue.EMPTY, FinancialFormat.GENERAL, true);
        return new QuarterEntry(base.key(), base.reportType(), base.periodEnd(), base.receiptNo(), false, true,
                FinancialFormat.GENERAL, true, MetricValue.EMPTY, MetricValue.EMPTY, MetricValue.EMPTY,
                MetricValue.EMPTY, MetricValue.EMPTY, new MetricValue(equity, equity), new MetricValue(capitalStock, capitalStock));
    }

    private static QuarterEntry lossQuarter(int quarterNumber, LocalDate periodEnd) {
        return lossQuarterAt(FY_START, quarterNumber, periodEnd);
    }

    private static QuarterEntry lossQuarterAt(LocalDate fiscalYearStart, int quarterNumber, LocalDate periodEnd) {
        PeriodType reportType = switch (quarterNumber) {
            case 1 -> PeriodType.Q1;
            case 2 -> PeriodType.H1;
            case 3 -> PeriodType.Q3;
            default -> PeriodType.FY;
        };
        MetricValue operating = income(new BigDecimal("-1000000000"), new BigDecimal("-500000000"));
        return new QuarterEntry(new PeriodKey(fiscalYearStart, quarterNumber), reportType, periodEnd, "R1", false, true,
                FinancialFormat.GENERAL, true, MetricValue.EMPTY, operating, MetricValue.EMPTY,
                MetricValue.EMPTY, MetricValue.EMPTY, MetricValue.EMPTY, MetricValue.EMPTY);
    }

    private static MetricValue revenue(BigDecimal current, BigDecimal prior) {
        return new MetricValue(current, prior);
    }

    private static MetricValue income(BigDecimal current, BigDecimal prior) {
        return new MetricValue(current, prior);
    }
}
