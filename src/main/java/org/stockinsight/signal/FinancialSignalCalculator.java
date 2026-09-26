package org.stockinsight.signal;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.stockinsight.financial.AnnualEntry;
import org.stockinsight.financial.FinancialFormat;
import org.stockinsight.financial.FinancialRatios;
import org.stockinsight.financial.FinancialSummary;
import org.stockinsight.financial.MetricValue;
import org.stockinsight.financial.PeriodKey;
import org.stockinsight.financial.QuarterEntry;
import org.stockinsight.financial.SummaryFlag;

/**
 * 재무 요약에서 신호 초안을 계산한다(D-35, ai-analysis.md §3.7). DB 없이 동작하는 순수 계산이다.
 * 판정 단위는 재무 기간 하나(보고서 하나)이고, 대상은 시계열 기준의 최근 12분기와 3개 사업연도다.
 */
public final class FinancialSignalCalculator {

    static final String RULE_VERSION = FinancialRuleCatalog.RULE_VERSION;
    private static final String KRW = "KRW";

    private FinancialSignalCalculator() {
    }

    /**
     * @return 신호 초안과, "최신 재무 미확인"이 시간만으로 다시 바뀔 수 있는 다음 날(D-43). 이미 그 신호가 활성이거나
     *         재무가 아예 없으면 null이다(그 뒤로는 재무 변경이나 규칙 버전 변경만이 재판정 계기다).
     */
    public static CalculationResult calculate(FinancialSummary summary, LocalDate asOf) {
        List<SignalDraft> drafts = new ArrayList<>();
        if (!summary.hasAnyReport() || summary.quarters().isEmpty()) {
            return new CalculationResult(drafts, null);
        }
        boolean nonKrw = !KRW.equals(summary.currency());
        LocalDate latestPeriodEnd = summary.latestPeriodEnd();

        List<FlowPeriod> flowPeriods = new ArrayList<>();
        for (QuarterEntry q : summary.quarters()) {
            if (q.flowSignalEligible()) {
                flowPeriods.add(FlowPeriod.of(q));
            }
        }
        for (AnnualEntry a : summary.annual()) {
            flowPeriods.add(FlowPeriod.of(a));
        }
        for (FlowPeriod period : flowPeriods) {
            boolean active = period.periodEnd.equals(latestPeriodEnd);
            Optional<SignalDraft> turn = turnSignal(period, active);
            turn.ifPresent(drafts::add);
            if (turn.isEmpty()) {
                marginChangeSignal(period, nonKrw, active).ifPresent(drafts::add);
            }
            revenueChangeSignal(period, nonKrw, active).ifPresent(drafts::add);
        }

        debtRatioJumpSignals(summary.quarters(), latestPeriodEnd).forEach(drafts::add);
        lossStreakSignals(summary.quarters(), latestPeriodEnd).forEach(drafts::add);
        capitalImpairmentSignals(summary.quarters(), latestPeriodEnd).forEach(drafts::add);

        DataLimitResult dataLimit = dataLimitSignals(summary, asOf);
        drafts.addAll(dataLimit.drafts());
        return new CalculationResult(drafts, dataLimit.staleRecheckAt());
    }

    public record CalculationResult(List<SignalDraft> drafts, LocalDate staleRecheckAt) {
    }

    // ---- 매출 큰 폭 증감 ----

    private static Optional<SignalDraft> revenueChangeSignal(FlowPeriod p, boolean nonKrw, boolean active) {
        if (p.format == FinancialFormat.FINANCIAL || nonKrw) {
            return Optional.empty();
        }
        if (!p.revenue.hasCurrent() || !p.revenue.hasPrior()) {
            return Optional.empty();
        }
        BigDecimal prior = p.revenue.prior();
        if (prior.signum() <= 0 || prior.abs().compareTo(p.revenueBase) < 0) {
            return Optional.empty();
        }
        BigDecimal pct = percentChange(p.revenue.current(), prior);
        SignalDirection direction;
        SignalSeverity severity;
        if (pct.compareTo(FinancialRuleCatalog.REVENUE_UP_THRESHOLD) >= 0) {
            direction = SignalDirection.POSITIVE;
            severity = pct.compareTo(FinancialRuleCatalog.REVENUE_UP_HIGH) >= 0 ? SignalSeverity.HIGH
                    : pct.compareTo(FinancialRuleCatalog.REVENUE_UP_MEDIUM) >= 0 ? SignalSeverity.MEDIUM : SignalSeverity.LOW;
        } else if (pct.compareTo(FinancialRuleCatalog.REVENUE_DOWN_THRESHOLD) <= 0) {
            direction = SignalDirection.NEGATIVE;
            severity = pct.compareTo(FinancialRuleCatalog.REVENUE_DOWN_HIGH) <= 0 ? SignalSeverity.HIGH
                    : pct.compareTo(FinancialRuleCatalog.REVENUE_DOWN_MEDIUM) <= 0 ? SignalSeverity.MEDIUM : SignalSeverity.LOW;
        } else {
            return Optional.empty();
        }
        Map<String, Object> calc = calcValues(p, "revenueYoyPct", pct);
        return Optional.of(new SignalDraft(SignalType.FIN_REVENUE_CHANGE, p.basisKey, SignalNature.CHANGE, direction,
                severity, p.periodEnd, null, calc, List.of("revenue"), p.receiptNo, active));
    }

    // ---- 영업이익률 큰 폭 변화 ----

    private static Optional<SignalDraft> marginChangeSignal(FlowPeriod p, boolean nonKrw, boolean active) {
        if (p.format == FinancialFormat.FINANCIAL || nonKrw) {
            return Optional.empty();
        }
        BigDecimal marginCurrent = margin(p.revenue.current(), p.operatingIncome.current());
        BigDecimal marginPrior = margin(p.revenue.prior(), p.operatingIncome.prior());
        if (marginCurrent == null || marginPrior == null
                || p.revenue.current().compareTo(p.revenueBase) < 0 || p.revenue.prior().compareTo(p.revenueBase) < 0) {
            return Optional.empty();
        }
        BigDecimal diff = marginCurrent.subtract(marginPrior);
        if (diff.abs().compareTo(FinancialRuleCatalog.MARGIN_DIFF_THRESHOLD) < 0) {
            return Optional.empty();
        }
        SignalDirection direction = diff.signum() > 0 ? SignalDirection.POSITIVE : SignalDirection.NEGATIVE;
        BigDecimal absDiff = diff.abs();
        SignalSeverity severity = absDiff.compareTo(FinancialRuleCatalog.MARGIN_DIFF_HIGH) >= 0 ? SignalSeverity.HIGH
                : absDiff.compareTo(FinancialRuleCatalog.MARGIN_DIFF_MEDIUM) >= 0 ? SignalSeverity.MEDIUM : SignalSeverity.LOW;
        Map<String, Object> calc = calcValues(p, "marginDiffPp", diff);
        calc.put("marginCurrentPct", marginCurrent);
        calc.put("marginPriorPct", marginPrior);
        return Optional.of(new SignalDraft(SignalType.FIN_OPERATING_MARGIN_CHANGE, p.basisKey, SignalNature.CHANGE,
                direction, severity, p.periodEnd, null, calc, List.of("operating_margin"), p.receiptNo, active));
    }

    // ---- 흑자·적자 전환 ----

    private static Optional<SignalDraft> turnSignal(FlowPeriod p, boolean active) {
        if (!p.operatingIncome.hasCurrent() || !p.operatingIncome.hasPrior()) {
            return Optional.empty();
        }
        BigDecimal current = p.operatingIncome.current();
        BigDecimal prior = p.operatingIncome.prior();
        if (current.signum() == 0 || prior.signum() == 0 || current.signum() == prior.signum()) {
            return Optional.empty();
        }
        boolean revenueBased = p.format != FinancialFormat.FINANCIAL && p.revenue.hasCurrent();
        BigDecimal marginCurrent = null;
        if (revenueBased) {
            marginCurrent = margin(p.revenue.current(), current);
            BigDecimal marginPrior = margin(p.revenue.prior(), prior);
            if (marginCurrent == null || marginPrior == null
                    || marginCurrent.abs().compareTo(FinancialRuleCatalog.TURN_MARGIN_THRESHOLD) < 0
                    || marginPrior.abs().compareTo(FinancialRuleCatalog.TURN_MARGIN_THRESHOLD) < 0) {
                return Optional.empty();
            }
        } else if (!p.isAnnual) {
            // 매출이 없거나 금융형이면 연간으로만 판정한다.
            return Optional.empty();
        }
        SignalDirection direction = current.signum() > 0 ? SignalDirection.POSITIVE : SignalDirection.NEGATIVE;
        SignalSeverity severity = SignalSeverity.MEDIUM;
        if (direction == SignalDirection.NEGATIVE && marginCurrent != null
                && marginCurrent.compareTo(FinancialRuleCatalog.TURN_SEVERE_LOSS_MARGIN) <= 0) {
            severity = SignalSeverity.HIGH;
        }
        Map<String, Object> calc = calcValues(p, "operatingIncomeCurrent", current);
        calc.put("operatingIncomePrior", prior);
        return Optional.of(new SignalDraft(SignalType.FIN_OPERATING_TURN, p.basisKey, SignalNature.CHANGE, direction,
                severity, p.periodEnd, null, calc, List.of("operating_income", "operating_margin"), p.receiptNo, active));
    }

    // ---- 부채비율 급등 (회계연도당 한 건, 처음 성립한 기간) ----

    private static List<SignalDraft> debtRatioJumpSignals(List<QuarterEntry> quartersDesc, LocalDate latestPeriodEnd) {
        List<QuarterEntry> ascending = ascending(quartersDesc);
        Map<LocalDate, List<QuarterEntry>> byFiscalYear = new LinkedHashMap<>();
        for (QuarterEntry q : ascending) {
            byFiscalYear.computeIfAbsent(q.key().fiscalYearStart(), k -> new ArrayList<>()).add(q);
        }
        LocalDate latestFiscalYear = ascending.isEmpty() ? null
                : ascending.get(ascending.size() - 1).key().fiscalYearStart();

        List<SignalDraft> out = new ArrayList<>();
        for (Map.Entry<LocalDate, List<QuarterEntry>> entry : byFiscalYear.entrySet()) {
            for (QuarterEntry q : entry.getValue()) {
                if (q.format() == FinancialFormat.FINANCIAL || !q.balanceConsistent()) {
                    continue;
                }
                if (!q.totalEquity().hasCurrent() || q.totalEquity().current().signum() <= 0) {
                    continue;
                }
                if (!q.totalLiabilities().hasCurrent() || !q.totalEquity().hasPrior() || !q.totalLiabilities().hasPrior()
                        || q.totalEquity().prior().signum() <= 0) {
                    continue;
                }
                BigDecimal ratio = FinancialRatios.debtRatio(q.totalLiabilities().current(), q.totalEquity().current());
                BigDecimal priorRatio = FinancialRatios.debtRatio(q.totalLiabilities().prior(), q.totalEquity().prior());
                BigDecimal diff = ratio.subtract(priorRatio);
                if (ratio.compareTo(FinancialRuleCatalog.DEBT_RATIO_LEVEL) < 0
                        || diff.compareTo(FinancialRuleCatalog.DEBT_RATIO_JUMP) < 0) {
                    continue;
                }
                SignalSeverity severity = (diff.compareTo(FinancialRuleCatalog.DEBT_RATIO_JUMP_HIGH) >= 0
                        || ratio.compareTo(FinancialRuleCatalog.DEBT_RATIO_LEVEL_HIGH) >= 0)
                        ? SignalSeverity.HIGH : SignalSeverity.MEDIUM;
                Map<String, Object> calc = new LinkedHashMap<>();
                calc.put("debtRatioCurrentPct", ratio);
                calc.put("debtRatioPriorPct", priorRatio);
                calc.put("debtRatioDiffPp", diff);
                calc.put("periodLabel", q.key().displayKey());
                calc.put("receiptNo", q.receiptNo());
                out.add(new SignalDraft(SignalType.FIN_DEBT_RATIO_JUMP, q.key().debtJumpBasisKey(), SignalNature.CHANGE,
                        SignalDirection.NEGATIVE, severity, q.periodEnd(), null, calc, List.of("debt_ratio"),
                        q.receiptNo(), entry.getKey().equals(latestFiscalYear)));
                break; // 처음 성립한 기간 하나만 쓴다.
            }
        }
        return out;
    }

    // ---- 영업적자 지속 ----

    private static List<SignalDraft> lossStreakSignals(List<QuarterEntry> quartersDesc, LocalDate latestPeriodEnd) {
        List<QuarterEntry> ascending = ascending(quartersDesc);
        List<SignalDraft> out = new ArrayList<>();
        List<QuarterEntry> streak = new ArrayList<>();
        for (int i = 0; i <= ascending.size(); i++) {
            QuarterEntry q = i < ascending.size() ? ascending.get(i) : null;
            boolean continues = q != null && q.hasOperatingIncome() && q.operatingIncome().current().signum() < 0
                    && (streak.isEmpty() || isConsecutive(streak.get(streak.size() - 1), q));
            if (continues) {
                streak.add(q);
                continue;
            }
            if (streak.size() >= FinancialRuleCatalog.LOSS_STREAK_MIN_QUARTERS) {
                out.add(lossStreakSignal(streak, latestPeriodEnd));
            }
            streak = new ArrayList<>();
            if (q != null && q.hasOperatingIncome() && q.operatingIncome().current().signum() < 0) {
                streak.add(q);
            }
        }
        return out;
    }

    private static SignalDraft lossStreakSignal(List<QuarterEntry> streak, LocalDate latestPeriodEnd) {
        QuarterEntry start = streak.get(0);
        QuarterEntry end = streak.get(streak.size() - 1);
        SignalSeverity severity = streak.size() >= FinancialRuleCatalog.LOSS_STREAK_HIGH_QUARTERS
                ? SignalSeverity.HIGH : SignalSeverity.MEDIUM;
        Map<String, Object> calc = new LinkedHashMap<>();
        calc.put("streakQuarters", streak.size());
        calc.put("latestOperatingIncome", end.operatingIncome().current());
        calc.put("periodLabel", start.key().displayKey() + "~" + end.key().displayKey());
        calc.put("receiptNo", end.receiptNo());
        boolean active = end.periodEnd().equals(latestPeriodEnd);
        return new SignalDraft(SignalType.FIN_OPERATING_LOSS_STREAK, start.key().stateBasisKey(), SignalNature.STATE,
                SignalDirection.NEGATIVE, severity, start.periodEnd(), streak.size(), calc, List.of("operating_income"),
                end.receiptNo(), active);
    }

    // ---- 자본잠식 ----

    private static List<SignalDraft> capitalImpairmentSignals(List<QuarterEntry> quartersDesc, LocalDate latestPeriodEnd) {
        List<QuarterEntry> ascending = ascending(quartersDesc);
        List<SignalDraft> out = new ArrayList<>();
        List<QuarterEntry> streak = new ArrayList<>();
        for (int i = 0; i <= ascending.size(); i++) {
            QuarterEntry q = i < ascending.size() ? ascending.get(i) : null;
            boolean impaired = q != null && impaired(q) && (streak.isEmpty() || isConsecutive(streak.get(streak.size() - 1), q));
            if (impaired) {
                streak.add(q);
                continue;
            }
            if (!streak.isEmpty()) {
                out.add(capitalImpairmentSignal(streak, latestPeriodEnd));
            }
            streak = new ArrayList<>();
            if (q != null && impaired(q)) {
                streak.add(q);
            }
        }
        return out;
    }

    private static boolean impaired(QuarterEntry q) {
        return q.balanceConsistent() && q.capitalStock().hasCurrent() && q.totalEquity().hasCurrent()
                && q.totalEquity().current().compareTo(q.capitalStock().current()) < 0;
    }

    private static SignalDraft capitalImpairmentSignal(List<QuarterEntry> streak, LocalDate latestPeriodEnd) {
        QuarterEntry start = streak.get(0);
        QuarterEntry end = streak.get(streak.size() - 1);
        BigDecimal equity = end.totalEquity().current();
        BigDecimal capital = end.capitalStock().current();
        BigDecimal ratio = FinancialRatios.impairmentRatio(capital, equity);
        SignalSeverity severity = equity.signum() <= 0 || (ratio != null && ratio.compareTo(FinancialRuleCatalog.IMPAIRMENT_RATIO_HIGH) >= 0)
                ? SignalSeverity.HIGH : SignalSeverity.MEDIUM;
        Map<String, Object> calc = new LinkedHashMap<>();
        calc.put("totalEquity", equity);
        calc.put("capitalStock", capital);
        calc.put("impairmentRatioPct", ratio);
        calc.put("periodLabel", start.key().displayKey() + "~" + end.key().displayKey());
        calc.put("receiptNo", end.receiptNo());
        boolean active = end.periodEnd().equals(latestPeriodEnd);
        return new SignalDraft(SignalType.FIN_CAPITAL_IMPAIRMENT, start.key().stateBasisKey(), SignalNature.STATE,
                SignalDirection.NEGATIVE, severity, start.periodEnd(), streak.size(), calc, List.of("total_equity"),
                end.receiptNo(), active);
    }

    // ---- 데이터 한계 ----

    private static DataLimitResult dataLimitSignals(FinancialSummary summary, LocalDate asOf) {
        List<SignalDraft> out = new ArrayList<>();
        QuarterEntry latest = summary.quarters().get(0);
        String latestKey = latest.key().stateBasisKey();

        if (summary.quarters().size() < FinancialRuleCatalog.MIN_HISTORY_QUARTERS) {
            out.add(dataLimitSignal(SignalType.FIN_DATA_HISTORY_SHORT, latestKey, latest.periodEnd(), latest.receiptNo()));
        }
        if (latest.format() == FinancialFormat.FINANCIAL) {
            out.add(dataLimitSignal(SignalType.FIN_DATA_FINANCIAL_FORMAT, latestKey, latest.periodEnd(), latest.receiptNo()));
        }
        if (latest.format() == FinancialFormat.GENERAL && !latest.revenue().hasCurrent()) {
            out.add(dataLimitSignal(SignalType.FIN_DATA_REVENUE_MISSING, latestKey, latest.periodEnd(), latest.receiptNo()));
        }
        if (summary.flags().stream().anyMatch(f -> SummaryFlag.BASIS_GAP.equals(f.code()))) {
            out.add(dataLimitSignal(SignalType.FIN_DATA_BASIS_CHANGED, latestKey, latest.periodEnd(), latest.receiptNo()));
        }
        summary.annual().stream().filter(AnnualEntry::irregular).findFirst().ifPresent(a ->
                out.add(dataLimitSignal(SignalType.FIN_DATA_IRREGULAR_PERIOD, a.basisKey(), a.periodEnd(), a.receiptNo())));
        if (!latest.balanceConsistent()) {
            out.add(dataLimitSignal(SignalType.FIN_DATA_INCONSISTENT, latestKey, latest.periodEnd(), latest.receiptNo()));
        }
        StaleAssessment stale = assessStale(latest, asOf);
        if (stale.stale()) {
            Map<String, Object> calc = new LinkedHashMap<>();
            calc.put("periodLabel", latest.periodEnd().toString());
            calc.put("deadline", stale.deadline().toString());
            out.add(new SignalDraft(SignalType.FIN_DATA_STALE, latestKey, SignalNature.STATE, SignalDirection.UNCERTAIN,
                    SignalSeverity.LOW, latest.periodEnd(), null, calc, List.of(), latest.receiptNo(), true));
        }
        return new DataLimitResult(out, stale.nextRecheckDate());
    }

    private static SignalDraft dataLimitSignal(SignalType type, String basisKey, LocalDate occurredOn, String receiptNo) {
        return new SignalDraft(type, basisKey, SignalNature.STATE, SignalDirection.UNCERTAIN, SignalSeverity.LOW,
                occurredOn, null, Map.of("periodLabel", occurredOn.toString()), List.of(), receiptNo, true);
    }

    /**
     * "최신 재무 미확인" 판정(D-43, 서비스 내부 데이터 품질 기준. 실제 법정 제출기한과는 다르다). 다음 기간
     * 종료월의 말일 + 기한일(분기·반기 60일, 사업보고서 120일) + 7일 유예가 지나면(달력일) 미확인이다.
     */
    static StaleAssessment assessStale(QuarterEntry latest, LocalDate asOf) {
        int deadlineDays = latest.key().quarterNumber() == 3
                ? FinancialRuleCatalog.ANNUAL_DEADLINE_DAYS : FinancialRuleCatalog.QUARTERLY_DEADLINE_DAYS;
        LocalDate nextPeriodEnd = YearMonth.from(latest.periodEnd().plusMonths(3)).atEndOfMonth();
        LocalDate deadline = nextPeriodEnd.plusDays(deadlineDays);
        LocalDate staleFrom = deadline.plusDays(FinancialRuleCatalog.STALE_GRACE_DAYS + 1);
        boolean stale = !asOf.isBefore(staleFrom);
        return new StaleAssessment(stale, deadline, stale ? null : staleFrom);
    }

    /**
     * @param nextRecheckDate 시간만으로 판정이 바뀌는 다음 날. 이미 미확인이면 그런 날이 없다(null, D-43).
     */
    record StaleAssessment(boolean stale, LocalDate deadline, LocalDate nextRecheckDate) {
    }

    private record DataLimitResult(List<SignalDraft> drafts, LocalDate staleRecheckAt) {
    }

    // ---- 공통 유틸 ----

    private static List<QuarterEntry> ascending(List<QuarterEntry> descending) {
        List<QuarterEntry> copy = new ArrayList<>(descending);
        java.util.Collections.reverse(copy);
        return copy;
    }

    /** 두 분기가 바로 이어지는가(약 1분기 간격). 벗어나면 빈 기간이다. */
    private static boolean isConsecutive(QuarterEntry earlier, QuarterEntry later) {
        return FinancialRatios.isConsecutiveQuarter(earlier.periodEnd(), later.periodEnd());
    }

    private static BigDecimal percentChange(BigDecimal current, BigDecimal prior) {
        return FinancialRatios.percentChange(current, prior);
    }

    private static BigDecimal margin(BigDecimal revenue, BigDecimal operatingIncome) {
        return FinancialRatios.margin(revenue, operatingIncome);
    }

    private static Map<String, Object> calcValues(FlowPeriod p, String changeKey, BigDecimal changeValue) {
        Map<String, Object> calc = new LinkedHashMap<>();
        calc.put("current", p.revenue.hasCurrent() ? p.revenue.current() : p.operatingIncome.current());
        calc.put("prior", p.revenue.hasPrior() ? p.revenue.prior() : p.operatingIncome.prior());
        calc.put(changeKey, changeValue);
        calc.put("periodLabel", p.isAnnual ? p.periodKey.fiscalYearStart() + " 연간" : p.periodKey.displayKey());
        calc.put("receiptNo", p.receiptNo);
        return calc;
    }

    /** 분기·연간 판정을 같은 코드로 다루기 위한 흐름 지표 묶음. */
    private record FlowPeriod(String basisKey, PeriodKey periodKey, LocalDate periodEnd,
            MetricValue revenue, MetricValue operatingIncome, FinancialFormat format, String receiptNo,
            BigDecimal revenueBase, boolean isAnnual) {

        static FlowPeriod of(QuarterEntry q) {
            return new FlowPeriod(q.key().flowBasisKey(q.reportType()), q.key(), q.periodEnd(), q.revenue(),
                    q.operatingIncome(), q.format(), q.receiptNo(), FinancialRuleCatalog.REVENUE_BASE_QUARTER, false);
        }

        static FlowPeriod of(AnnualEntry a) {
            return new FlowPeriod(a.basisKey(), new PeriodKey(a.fiscalYearStart(), 4),
                    a.periodEnd(), a.revenue(), a.operatingIncome(), a.format(), a.receiptNo(),
                    FinancialRuleCatalog.REVENUE_BASE_ANNUAL, true);
        }
    }
}
