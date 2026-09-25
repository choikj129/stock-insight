package org.stockinsight.financial;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Period;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.stockinsight.financial.AccountMapper.MappedAccounts;

/**
 * 기업 재무 요약을 만든다(ai-analysis.md §3.5~§3.8). 시계열 기준(연결/별도)·통화는 최신 기간 기준으로 고정한다(D-37).
 * 계산만 하고 저장하지 않는다.
 */
@Service
public class FinancialSummaryService {

    /** 최근 12분기(최대 4개 회계연도까지 거슬러 올라가며 채운다). */
    private static final int QUARTER_WINDOW = 12;
    private static final int ANNUAL_WINDOW = 3;
    private static final String KRW = "KRW";

    private final FinancialRepository repository;

    FinancialSummaryService(FinancialRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public FinancialSummary summarize(long companyId) {
        List<StoredFinancialReport> reports = repository.findAllByCompany(companyId);
        if (reports.isEmpty()) {
            return new FinancialSummary(companyId, false, null, null, null, null, List.of(), List.of(), List.of());
        }

        StoredFinancialReport latestOverall = latestReport(reports);
        String basis = latestOverall.fsDiv();
        String currency = latestOverall.currency();

        List<StoredFinancialReport> basisReports = reports.stream().filter(r -> r.fsDiv().equals(basis)).toList();

        List<QuarterEntry> quarters = buildQuarters(basisReports);
        List<AnnualEntry> annual = buildAnnual(basisReports);
        boolean basisGap = detectBasisGap(reports, basis, quarters);

        List<SummaryFlag> flags = buildFlags(currency, basisReports, quarters, annual, basisGap);

        return new FinancialSummary(companyId, true, basis, currency, latestOverall.periodEnd(), latestOverall.receiptNo(),
                flags, quarters, annual);
    }

    private static StoredFinancialReport latestReport(List<StoredFinancialReport> reports) {
        LocalDate maxPeriodEnd = reports.stream().map(StoredFinancialReport::periodEnd).max(Comparator.naturalOrder()).orElseThrow();
        List<StoredFinancialReport> atLatest = reports.stream().filter(r -> r.periodEnd().equals(maxPeriodEnd)).toList();
        return atLatest.stream().filter(r -> "CFS".equals(r.fsDiv())).findFirst().orElse(atLatest.get(0));
    }

    private List<QuarterEntry> buildQuarters(List<StoredFinancialReport> basisReports) {
        Map<LocalDate, Map<PeriodType, StoredFinancialReport>> byFiscalYear = basisReports.stream()
                .collect(Collectors.groupingBy(StoredFinancialReport::fiscalYearStart,
                        Collectors.toMap(StoredFinancialReport::periodType, r -> r, (a, b) -> a)));

        List<QuarterEntry> all = new ArrayList<>();
        for (Map.Entry<LocalDate, Map<PeriodType, StoredFinancialReport>> yearEntry : byFiscalYear.entrySet()) {
            LocalDate fiscalYearStart = yearEntry.getKey();
            Map<PeriodType, StoredFinancialReport> byType = yearEntry.getValue();

            addReportedQuarter(all, byType.get(PeriodType.Q1), new PeriodKey(fiscalYearStart, 1), PeriodType.Q1);
            addReportedQuarter(all, byType.get(PeriodType.H1), new PeriodKey(fiscalYearStart, 2), PeriodType.H1);
            addReportedQuarter(all, byType.get(PeriodType.Q3), new PeriodKey(fiscalYearStart, 3), PeriodType.Q3);

            StoredFinancialReport fy = byType.get(PeriodType.FY);
            if (fy != null) {
                all.add(deriveFourthQuarter(new PeriodKey(fiscalYearStart, 4), fy, byType.get(PeriodType.Q3)));
            }
        }
        return all.stream()
                .sorted(Comparator.comparing(QuarterEntry::periodEnd).reversed())
                .limit(QUARTER_WINDOW)
                .toList();
    }

    private void addReportedQuarter(List<QuarterEntry> out, StoredFinancialReport report, PeriodKey key, PeriodType type) {
        if (report == null) {
            return;
        }
        MappedAccounts accounts = AccountMapper.map(report.lines(), "company=" + report.companyId() + " " + key.flowBasisKey(type));
        out.add(new QuarterEntry(key, type, report.periodEnd(), report.receiptNo(), false, true,
                accounts.format(), isBalanceConsistent(accounts), accounts.revenue(), accounts.operatingIncome(),
                accounts.netIncome(), accounts.totalAssets(), accounts.totalLiabilities(), accounts.totalEquity(),
                accounts.capitalStock()));
    }

    /** 4분기 = 연간 − 3분기 누적(사업보고서와 3분기 보고서의 기준·통화·회계연도 시작일이 같을 때만, ai-analysis.md §3.6). */
    private QuarterEntry deriveFourthQuarter(PeriodKey key, StoredFinancialReport fy, StoredFinancialReport q3) {
        MappedAccounts fyAccounts = AccountMapper.map(fy.lines(), "company=" + fy.companyId() + " " + key.stateBasisKey());
        boolean canDerive = q3 != null && q3.currency().equals(fy.currency()) && q3.fiscalYearStart().equals(fy.fiscalYearStart());

        MetricValue revenue = MetricValue.EMPTY;
        MetricValue operatingIncome = MetricValue.EMPTY;
        MetricValue netIncome = MetricValue.EMPTY;
        boolean valid = false;
        if (canDerive) {
            AccountMapper.CumulativeAccounts q3Cumulative = AccountMapper.mapCumulative(q3.lines(), "company=" + q3.companyId() + " q3");
            BigDecimal derivedRevenue = subtractCumulative(fyAccounts.revenue().current(), q3Cumulative.revenue());
            valid = derivedRevenue == null || derivedRevenue.signum() >= 0;
            if (valid) {
                revenue = derivedRevenue == null ? MetricValue.EMPTY : new MetricValue(derivedRevenue, null);
                BigDecimal derivedOperating = subtractCumulative(fyAccounts.operatingIncome().current(), q3Cumulative.operatingIncome());
                operatingIncome = derivedOperating == null ? MetricValue.EMPTY : new MetricValue(derivedOperating, null);
                BigDecimal derivedNet = subtractCumulative(fyAccounts.netIncome().current(), q3Cumulative.netIncome());
                netIncome = derivedNet == null ? MetricValue.EMPTY : new MetricValue(derivedNet, null);
            }
        }

        return new QuarterEntry(key, PeriodType.FY, fy.periodEnd(), fy.receiptNo(), true, valid,
                fyAccounts.format(), isBalanceConsistent(fyAccounts), revenue, operatingIncome, netIncome,
                fyAccounts.totalAssets(), fyAccounts.totalLiabilities(), fyAccounts.totalEquity(), fyAccounts.capitalStock());
    }

    private static BigDecimal subtractCumulative(BigDecimal annualCurrent, BigDecimal q3Cumulative) {
        if (annualCurrent == null || q3Cumulative == null) {
            return null;
        }
        return annualCurrent.subtract(q3Cumulative);
    }

    private List<AnnualEntry> buildAnnual(List<StoredFinancialReport> basisReports) {
        return basisReports.stream()
                .filter(r -> r.periodType() == PeriodType.FY)
                .sorted(Comparator.comparing(StoredFinancialReport::periodEnd).reversed())
                .limit(ANNUAL_WINDOW)
                .map(r -> {
                    MappedAccounts accounts = AccountMapper.map(r.lines(), "company=" + r.companyId() + " annual " + r.fiscalYearStart());
                    boolean irregular = Math.abs(Period.between(r.fiscalYearStart(), r.periodEnd().plusDays(1)).toTotalMonths() - 12) >= 1;
                    return new AnnualEntry(r.fiscalYearStart(), r.periodEnd(), r.receiptNo(), irregular,
                            isBalanceConsistent(accounts), accounts.format(), accounts.revenue(), accounts.operatingIncome(),
                            accounts.netIncome(), accounts.totalAssets(), accounts.totalLiabilities(), accounts.totalEquity(),
                            accounts.capitalStock());
                })
                .toList();
    }

    /** 최근 분기 창 안에서 다른 기준(연결/별도)에만 있던 기간이 있으면 기준이 바뀐 것이다(D-37). */
    private boolean detectBasisGap(List<StoredFinancialReport> allReports, String basis, List<QuarterEntry> fixedQuarters) {
        String otherBasis = "CFS".equals(basis) ? "OFS" : "CFS";
        if (fixedQuarters.isEmpty()) {
            return false;
        }
        LocalDate windowStart = fixedQuarters.get(fixedQuarters.size() - 1).periodEnd();
        Set<String> fixedPeriods = fixedQuarters.stream()
                .map(q -> q.key().fiscalYearStart() + ":" + q.reportType())
                .collect(Collectors.toCollection(HashSet::new));
        return allReports.stream()
                .filter(r -> r.fsDiv().equals(otherBasis))
                .filter(r -> !r.periodEnd().isBefore(windowStart))
                .anyMatch(r -> !fixedPeriods.contains(r.fiscalYearStart() + ":" + r.periodType()));
    }

    private List<SummaryFlag> buildFlags(String currency, List<StoredFinancialReport> basisReports,
            List<QuarterEntry> quarters, List<AnnualEntry> annual, boolean basisGap) {
        List<SummaryFlag> flags = new ArrayList<>();
        if (!KRW.equals(currency)) {
            flags.add(new SummaryFlag(SummaryFlag.NON_KRW));
        }
        if (!quarters.isEmpty() && quarters.get(0).format() == FinancialFormat.FINANCIAL) {
            flags.add(new SummaryFlag(SummaryFlag.NOT_APPLICABLE_FORMAT));
        }
        if (basisGap) {
            flags.add(new SummaryFlag(SummaryFlag.BASIS_GAP));
        }
        for (QuarterEntry q : quarters) {
            if (!q.balanceConsistent()) {
                flags.add(new SummaryFlag(SummaryFlag.INCONSISTENT_BALANCE, q.key().displayKey()));
            }
            if (q.derived() && !q.derivedValid()) {
                flags.add(new SummaryFlag(SummaryFlag.DERIVED_INVALID, q.key().displayKey()));
            }
        }
        for (AnnualEntry a : annual) {
            if (!a.balanceConsistent()) {
                flags.add(new SummaryFlag(SummaryFlag.INCONSISTENT_BALANCE, a.fiscalYearStart() + ":FY"));
            }
        }
        return flags;
    }

    /** 자산총계 = 부채총계 + 자본총계 (차이 0.5% 이내, ai-analysis.md §3.6). 값이 없으면 점검하지 않는다(일치로 본다). */
    private static boolean isBalanceConsistent(MappedAccounts accounts) {
        return FinancialRatios.isBalanceConsistent(accounts.totalAssets().current(),
                accounts.totalLiabilities().current(), accounts.totalEquity().current());
    }
}
