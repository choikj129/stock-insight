package org.stockinsight.analysis;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.stockinsight.company.Company;
import org.stockinsight.company.CompanyService;
import org.stockinsight.financial.AnnualEntry;
import org.stockinsight.financial.FinancialFormat;
import org.stockinsight.financial.FinancialRatios;
import org.stockinsight.financial.FinancialSummary;
import org.stockinsight.financial.FinancialSummaryService;
import org.stockinsight.financial.MetricValue;
import org.stockinsight.financial.PeriodKey;
import org.stockinsight.financial.QuarterEntry;
import org.stockinsight.signal.CompanySignal;
import org.stockinsight.signal.CompanySignalService;
import org.stockinsight.signal.SignalStatus;
import org.stockinsight.signal.SignalType;

/**
 * 재무 쉬운 설명의 AI 입력을 만든다(ai-analysis.md §4.4.2, D-38). 같은 데이터면 항상 같은 결과를 만든다(결정적 입력).
 * 원천 행·분기 시계열 전체·공시번호·내부 ID는 결과에 넣지 않는다.
 */
@Service
public class FinancialExplainInputBuilder {

    public static final String INPUT_BUILDER_VERSION = "fx-input-1";

    private static final String KRW = "KRW";
    private static final int MAX_PAST_SIGNALS = 4;

    /** 신호 유형 → 사실 키(기본 이름). ai-analysis.md §4.4.2 "신호 선별과 묶음". */
    private static final Map<SignalType, List<String>> SIGNAL_FACT_KEYS = Map.of(
            SignalType.FIN_REVENUE_CHANGE, List.of("revenue", "revenue_prior", "revenue_yoy"),
            SignalType.FIN_OPERATING_MARGIN_CHANGE, List.of("operating_margin", "operating_margin_prior", "operating_margin_diff"),
            SignalType.FIN_OPERATING_TURN, List.of("operating_income", "operating_income_prior"),
            SignalType.FIN_DEBT_RATIO_JUMP, List.of("debt_ratio", "debt_ratio_prior_end", "debt_ratio_diff"),
            SignalType.FIN_OPERATING_LOSS_STREAK, List.of("operating_loss_run"),
            SignalType.FIN_CAPITAL_IMPAIRMENT, List.of("total_equity", "capital_stock", "impairment_ratio"));

    /** 묶음 주제. 손익 신호와 재무 구조 신호를 나눈다. */
    private static final Map<SignalType, String> SIGNAL_TOPIC = Map.of(
            SignalType.FIN_REVENUE_CHANGE, "SALES_PROFIT",
            SignalType.FIN_OPERATING_MARGIN_CHANGE, "SALES_PROFIT",
            SignalType.FIN_OPERATING_TURN, "SALES_PROFIT",
            SignalType.FIN_OPERATING_LOSS_STREAK, "SALES_PROFIT",
            SignalType.FIN_DEBT_RATIO_JUMP, "STRUCTURE",
            SignalType.FIN_CAPITAL_IMPAIRMENT, "STRUCTURE");

    /** 묶음 안 순서: 흑자·적자 전환 → 매출 → 영업이익률 → 적자 지속. */
    private static final List<SignalType> WITHIN_GROUP_ORDER = List.of(
            SignalType.FIN_OPERATING_TURN, SignalType.FIN_REVENUE_CHANGE,
            SignalType.FIN_OPERATING_MARGIN_CHANGE, SignalType.FIN_OPERATING_LOSS_STREAK,
            SignalType.FIN_DEBT_RATIO_JUMP, SignalType.FIN_CAPITAL_IMPAIRMENT);

    private final CompanyService companyService;
    private final FinancialSummaryService summaryService;
    private final CompanySignalService signalService;

    FinancialExplainInputBuilder(CompanyService companyService, FinancialSummaryService summaryService,
            CompanySignalService signalService) {
        this.companyService = companyService;
        this.summaryService = summaryService;
        this.signalService = signalService;
    }

    /** 재무 보고서가 없는 기업은 빈 값이다(ai-analysis.md §4.4.6). */
    @Transactional(readOnly = true)
    public Optional<BuildResult> build(long companyId) {
        FinancialSummary summary = summaryService.summarize(companyId);
        if (!summary.hasAnyReport()) {
            return Optional.empty();
        }
        Company company = companyService.findById(companyId)
                .orElseThrow(() -> new IllegalStateException("기업을 찾을 수 없습니다: " + companyId));

        boolean nonKrw = !KRW.equals(summary.currency());
        FinancialFormat format = summary.quarters().isEmpty() ? summary.annual().get(0).format() : summary.quarters().get(0).format();
        LatestPeriod latest = resolveLatest(summary);

        List<CompanySignal> allSignals = signalService.findByCompany(companyId);

        FactCollector fc = new FactCollector(nonKrw, format, summary.currency(), summary.basis(), company.getFiscalMonth());
        fc.addIncomeFacts(latest.periodKey, latest.isAnnual, latest.revenue, latest.operatingIncome, latest.netIncome,
                latest.receiptNo, latest.periodEnd, latest.revenueBase);
        for (AnnualEntry a : summary.annual()) {
            PeriodKey key = annualKey(a.fiscalYearStart());
            fc.addAnnualFacts(key, a.revenue(), a.operatingIncome(), a.netIncome(), a.receiptNo(), a.periodEnd());
        }
        // 최신 기간 재무상태표가 불일치하면(FIN_DATA_INCONSISTENT) 재무상태 지표 전체를 뺀다(§4.4.2).
        boolean balanceInconsistent = allSignals.stream().anyMatch(
                s -> s.status() == SignalStatus.ACTIVE && s.signalType() == SignalType.FIN_DATA_INCONSISTENT);
        if (balanceInconsistent) {
            fc.markBalanceUnavailable(latest.periodKey, latest.isAnnual);
        } else {
            fc.addBalanceFacts(latest.periodKey, latest.isAnnual, latest.totalAssets, latest.totalLiabilities,
                    latest.totalEquity, latest.capitalStock, latest.receiptNo, latest.periodEnd);
        }
        fc.addFlowFacts(summary.quarters());

        SignalSelection selection = selectSignals(allSignals);
        List<FinancialExplainInput.SignalRef> signalRefs = new ArrayList<>();
        Map<String, ValueSnapshot.SignalSnapshot> signalSnapshots = new LinkedHashMap<>();
        Map<CompanySignal, String> refByOriginal = new LinkedHashMap<>();
        int refIndex = 1;
        for (CompanySignal signal : selection.ordered()) {
            String ref = "S" + refIndex++;
            refByOriginal.put(signal, ref);
            PeriodKey signalPeriod = derivePeriodKey(signal);
            List<String> factKeys = SIGNAL_FACT_KEYS.getOrDefault(signal.signalType(), List.of()).stream()
                    .map(base -> "fin." + base + "." + signalPeriod.displayKey())
                    .filter(fc.facts::containsKey)
                    .toList();
            signalRefs.add(new FinancialExplainInput.SignalRef(ref, signal.signalType().name(),
                    signal.status().name(), natureOf(signal), signal.direction().name(), signal.severity().name(),
                    signalPeriod.displayKey(), signal.persistence(), factKeys));
            signalSnapshots.put(ref, new ValueSnapshot.SignalSnapshot(
                    signal.signalType().name() + ":" + signal.basisKey(), signal.signalType().name(),
                    signal.status().name(), signal.direction().name(), signal.severity().name(),
                    signal.occurredOn().toString(), signal.sourceReceiptNo()));
        }

        List<FinancialExplainInput.Group> groups = buildGroups(selection.ordered(), refByOriginal);
        String changeStatus = selection.hasActiveChange() ? "CHANGED" : "NONE";
        List<String> sections = resolveSections(changeStatus, fc, selection);

        List<FinancialExplainInput.PeriodLabel> periods = fc.periodLabels.entrySet().stream()
                .map(e -> new FinancialExplainInput.PeriodLabel(e.getKey(), e.getValue()))
                .toList();

        FinancialExplainInput input = new FinancialExplainInput(
                new FinancialExplainInput.Company(company.getName(), format.name(), summary.currency(), summary.basis(),
                        company.getFiscalMonth()),
                new FinancialExplainInput.Latest(latest.periodKey.displayKey(), latest.isAnnual ? "ANNUAL" : "QUARTER"),
                changeStatus,
                sections,
                periods,
                List.copyOf(fc.facts.values()),
                signalRefs,
                groups,
                fc.unavailable,
                List.copyOf(fc.doNotMention));

        List<String> limitCodes = allSignals.stream()
                .filter(s -> s.status() == SignalStatus.ACTIVE && s.signalType().name().startsWith("FIN_DATA_"))
                .map(s -> s.signalType().name())
                .sorted()
                .toList();

        ValueSnapshot snapshot = new ValueSnapshot(
                Map.copyOf(fc.factSnapshots),
                Map.copyOf(fc.periodLabels),
                Map.copyOf(signalSnapshots),
                limitCodes,
                new ValueSnapshot.Header(summary.basis(), summary.currency(), format.name(), latest.periodKey.displayKey()));

        Set<String> receiptNos = fc.factSnapshots.values().stream()
                .map(ValueSnapshot.FactSnapshot::receiptNo)
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        String fingerprint = Fingerprint.compute(receiptNos, selection.ordered(), limitCodes, INPUT_BUILDER_VERSION);
        return Optional.of(new BuildResult(input, snapshot, fingerprint));
    }

    private static String natureOf(CompanySignal signal) {
        return signal.signalType() == SignalType.FIN_OPERATING_LOSS_STREAK
                || signal.signalType() == SignalType.FIN_CAPITAL_IMPAIRMENT ? "STATE" : "CHANGE";
    }

    private static PeriodKey annualKey(LocalDate fiscalYearStart) {
        return new PeriodKey(fiscalYearStart, 4);
    }

    /** basisKey의 회계연도 시작일과 occurredOn(기간 종료일)로 분기 키를 되짚는다. */
    private static PeriodKey derivePeriodKey(CompanySignal signal) {
        LocalDate fiscalYearStart = LocalDate.parse(signal.basisKey().substring(0, signal.basisKey().indexOf(':')));
        long months = java.time.temporal.ChronoUnit.MONTHS.between(fiscalYearStart, signal.occurredOn());
        int quarterNumber = (int) Math.max(1, Math.min(4, Math.round(months / 3.0)));
        return new PeriodKey(fiscalYearStart, quarterNumber);
    }

    private LatestPeriod resolveLatest(FinancialSummary summary) {
        QuarterEntry latestRealQuarter = summary.quarters().stream()
                .filter(QuarterEntry::flowSignalEligible).findFirst().orElse(null);
        AnnualEntry latestAnnual = summary.annual().isEmpty() ? null : summary.annual().get(0);
        QuarterEntry latestQuarterAny = summary.quarters().isEmpty() ? null : summary.quarters().get(0);

        boolean useAnnual = latestAnnual != null
                && (latestRealQuarter == null || !latestAnnual.periodEnd().isBefore(latestRealQuarter.periodEnd()));

        // 재무상태(BS)는 파생 4분기라도 사업보고서 자체 값이라 그대로 쓴다.
        QuarterEntry balanceSource = latestQuarterAny != null ? latestQuarterAny : null;
        MetricValue totalAssets = balanceSource != null ? balanceSource.totalAssets() : latestAnnual.totalAssets();
        MetricValue totalLiabilities = balanceSource != null ? balanceSource.totalLiabilities() : latestAnnual.totalLiabilities();
        MetricValue totalEquity = balanceSource != null ? balanceSource.totalEquity() : latestAnnual.totalEquity();
        MetricValue capitalStock = balanceSource != null ? balanceSource.capitalStock() : latestAnnual.capitalStock();
        String balanceReceiptNo = balanceSource != null ? balanceSource.receiptNo() : latestAnnual.receiptNo();
        LocalDate balancePeriodEnd = balanceSource != null ? balanceSource.periodEnd() : latestAnnual.periodEnd();

        if (useAnnual) {
            return new LatestPeriod(true, annualKey(latestAnnual.fiscalYearStart()), latestAnnual.periodEnd(),
                    latestAnnual.receiptNo(), latestAnnual.revenue(), latestAnnual.operatingIncome(),
                    latestAnnual.netIncome(), totalAssets, totalLiabilities, totalEquity, capitalStock,
                    balanceReceiptNo, balancePeriodEnd, FinancialRatios.REVENUE_BASE_ANNUAL);
        }
        QuarterEntry q = latestRealQuarter != null ? latestRealQuarter : latestQuarterAny;
        return new LatestPeriod(false, q.key(), q.periodEnd(), q.receiptNo(), q.revenue(), q.operatingIncome(),
                q.netIncome(), totalAssets, totalLiabilities, totalEquity, capitalStock, balanceReceiptNo,
                balancePeriodEnd, FinancialRatios.REVENUE_BASE_QUARTER);
    }

    /** 활성 신호는 모두, 이력 신호는 심각도·최근성 순 최대 4개. FIN_DATA_*(데이터 한계)는 제외한다. */
    private SignalSelection selectSignals(List<CompanySignal> allSignals) {
        List<CompanySignal> active = allSignals.stream()
                .filter(s -> s.status() == SignalStatus.ACTIVE && !s.signalType().name().startsWith("FIN_DATA_"))
                .sorted(Comparator.comparing(CompanySignal::signalType, Comparator.comparingInt(WITHIN_GROUP_ORDER::indexOf)))
                .toList();
        List<CompanySignal> past = allSignals.stream()
                .filter(s -> s.status() == SignalStatus.PAST && !s.signalType().name().startsWith("FIN_DATA_"))
                .sorted(Comparator.comparing(CompanySignal::severity, Comparator.reverseOrder())
                        .thenComparing(CompanySignal::occurredOn, Comparator.reverseOrder()))
                .limit(MAX_PAST_SIGNALS)
                .toList();
        List<CompanySignal> ordered = new ArrayList<>(active);
        ordered.addAll(past);
        return new SignalSelection(ordered, hasActiveChange(active));
    }

    private static boolean hasActiveChange(List<CompanySignal> active) {
        return active.stream().anyMatch(s -> "CHANGE".equals(natureOf(s)));
    }

    private List<FinancialExplainInput.Group> buildGroups(List<CompanySignal> ordered, Map<CompanySignal, String> refs) {
        Map<String, List<CompanySignal>> byPeriodTopic = new LinkedHashMap<>();
        for (CompanySignal s : ordered) {
            String topic = SIGNAL_TOPIC.getOrDefault(s.signalType(), "SALES_PROFIT");
            String key = derivePeriodKey(s).displayKey() + ":" + topic;
            byPeriodTopic.computeIfAbsent(key, k -> new ArrayList<>()).add(s);
        }
        List<FinancialExplainInput.Group> groups = new ArrayList<>();
        for (Map.Entry<String, List<CompanySignal>> entry : byPeriodTopic.entrySet()) {
            String[] parts = entry.getKey().split(":", 2);
            List<CompanySignal> members = entry.getValue().stream()
                    .sorted(Comparator.comparingInt(s -> WITHIN_GROUP_ORDER.indexOf(s.signalType())))
                    .toList();
            List<String> groupRefs = members.stream().map(refs::get).toList();
            boolean mixed = members.stream().map(CompanySignal::direction).distinct().count() > 1;
            groups.add(new FinancialExplainInput.Group(parts[0], parts[1], groupRefs, mixed));
        }
        return groups;
    }

    private static List<String> resolveSections(String changeStatus, FactCollector fc, SignalSelection selection) {
        List<String> sections = new ArrayList<>();
        sections.add("overview");
        if (fc.hasIncomeFacts) {
            sections.add("sales_profit");
        }
        if (fc.hasBalanceFacts) {
            sections.add("structure");
        }
        if (!selection.past().isEmpty() || fc.hasFlowFacts) {
            sections.add("history");
        }
        return sections;
    }

    public record BuildResult(FinancialExplainInput input, ValueSnapshot snapshot, String fingerprint) {
    }

    private record LatestPeriod(boolean isAnnual, PeriodKey periodKey, LocalDate periodEnd, String receiptNo,
            MetricValue revenue, MetricValue operatingIncome, MetricValue netIncome, MetricValue totalAssets,
            MetricValue totalLiabilities, MetricValue totalEquity, MetricValue capitalStock, String balanceReceiptNo,
            LocalDate balancePeriodEnd, BigDecimal revenueBase) {
    }

    private record SignalSelection(List<CompanySignal> ordered, boolean hasActiveChange) {
        List<CompanySignal> past() {
            return ordered.stream().filter(s -> s.status() == SignalStatus.PAST).toList();
        }
    }

    /** 사실·플래그를 모으는 내부 헬퍼. 계산은 {@link FinancialRatios}를 쓴다(신호 계산기와 같은 코드). */
    private static final class FactCollector {
        final Map<String, FinancialExplainInput.Fact> facts = new LinkedHashMap<>();
        final Map<String, ValueSnapshot.FactSnapshot> factSnapshots = new LinkedHashMap<>();
        final Map<String, String> periodLabels = new LinkedHashMap<>();
        final List<FinancialExplainInput.Unavailable> unavailable = new ArrayList<>();
        final Set<String> doNotMention = new LinkedHashSet<>();
        final boolean nonKrw;
        final FinancialFormat format;
        final String currency;
        final String basis;
        final Integer fiscalMonth;
        boolean hasIncomeFacts;
        boolean hasBalanceFacts;
        boolean hasFlowFacts;

        FactCollector(boolean nonKrw, FinancialFormat format, String currency, String basis, Integer fiscalMonth) {
            this.nonKrw = nonKrw;
            this.format = format;
            this.currency = currency;
            this.basis = basis;
            this.fiscalMonth = fiscalMonth;
            if (format == FinancialFormat.FINANCIAL) {
                doNotMention.add("부채비율");
                doNotMention.add("영업이익률");
                doNotMention.add("매출");
            }
        }

        void addIncomeFacts(PeriodKey period, boolean isAnnual, MetricValue revenue, MetricValue operatingIncome,
                MetricValue netIncome, String receiptNo, LocalDate periodEnd, BigDecimal revenueBase) {
            label(period, isAnnual);
            String revenueName = format == FinancialFormat.FINANCIAL ? "영업수익" : "매출액";
            if (revenue.hasCurrent()) {
                put(period, "revenue", revenueName, revenue.current(), currency, receiptNo, periodEnd);
                if (revenue.hasPrior()) {
                    put(period, "revenue_prior", revenueName + "(전년 동기)", revenue.prior(), currency, receiptNo, periodEnd);
                }
                // 금융형은 매출 증가율을 쓰지 않는다(§4.4.2). 원값(영업수익)은 그대로 사실표에 남긴다.
                if (format == FinancialFormat.FINANCIAL) {
                    unavailable.add(new FinancialExplainInput.Unavailable("revenue_yoy", "NOT_APPLICABLE_FORMAT"));
                } else if (revenue.hasPrior()) {
                    BigDecimal prior = revenue.prior();
                    if (nonKrw) {
                        unavailable.add(new FinancialExplainInput.Unavailable("revenue_yoy", "NON_KRW"));
                    } else if (prior.signum() <= 0) {
                        unavailable.add(new FinancialExplainInput.Unavailable("revenue_yoy", "BASE_NOT_POSITIVE"));
                    } else if (prior.abs().compareTo(revenueBase) < 0) {
                        unavailable.add(new FinancialExplainInput.Unavailable("revenue_yoy", "SMALL_BASE"));
                    } else {
                        BigDecimal pct = FinancialRatios.percentChange(revenue.current(), prior);
                        put(period, "revenue_yoy", "매출 증가율(전년 같은 분기 대비)", pct, "%", receiptNo, periodEnd);
                    }
                }
            } else {
                unavailable.add(new FinancialExplainInput.Unavailable("revenue", "ACCOUNT_MISSING"));
            }

            if (operatingIncome.hasCurrent()) {
                put(period, "operating_income", "영업이익", operatingIncome.current(), currency, receiptNo, periodEnd);
                if (operatingIncome.hasPrior()) {
                    put(period, "operating_income_prior", "영업이익(전년 동기)", operatingIncome.prior(), currency, receiptNo, periodEnd);
                }
                BigDecimal marginCurrent = FinancialRatios.margin(revenue.current(), operatingIncome.current());
                BigDecimal marginPrior = FinancialRatios.margin(revenue.prior(), operatingIncome.prior());
                if (format == FinancialFormat.FINANCIAL) {
                    unavailable.add(new FinancialExplainInput.Unavailable("operating_margin", "NOT_APPLICABLE_FORMAT"));
                } else if (marginCurrent == null) {
                    unavailable.add(new FinancialExplainInput.Unavailable("operating_margin", "ACCOUNT_MISSING"));
                } else {
                    put(period, "operating_margin", "영업이익률", marginCurrent, "%", receiptNo, periodEnd);
                    if (marginPrior != null) {
                        put(period, "operating_margin_prior", "영업이익률(전년 동기)", marginPrior, "%", receiptNo, periodEnd);
                        put(period, "operating_margin_diff", "영업이익률 변화", marginCurrent.subtract(marginPrior), "%p", receiptNo, periodEnd);
                    }
                }
            }
            if (netIncome.hasCurrent()) {
                put(period, "net_income", "당기순이익", netIncome.current(), currency, receiptNo, periodEnd);
                if (netIncome.hasPrior()) {
                    put(period, "net_income_prior", "당기순이익(전년 동기)", netIncome.prior(), currency, receiptNo, periodEnd);
                }
            }
            hasIncomeFacts = true;
        }

        void addAnnualFacts(PeriodKey period, MetricValue revenue, MetricValue operatingIncome, MetricValue netIncome,
                String receiptNo, LocalDate periodEnd) {
            label(period, true);
            String revenueName = format == FinancialFormat.FINANCIAL ? "영업수익" : "매출액";
            if (revenue.hasCurrent()) {
                put(period, "revenue", revenueName, revenue.current(), currency, receiptNo, periodEnd);
                if (format != FinancialFormat.FINANCIAL && revenue.hasPrior() && !nonKrw && revenue.prior().signum() > 0
                        && revenue.prior().abs().compareTo(FinancialRatios.REVENUE_BASE_ANNUAL) >= 0) {
                    put(period, "revenue_yoy", "매출 증가율(전기 대비)",
                            FinancialRatios.percentChange(revenue.current(), revenue.prior()), "%", receiptNo, periodEnd);
                }
            }
            if (operatingIncome.hasCurrent()) {
                put(period, "operating_income", "영업이익", operatingIncome.current(), currency, receiptNo, periodEnd);
                BigDecimal margin = FinancialRatios.margin(revenue.current(), operatingIncome.current());
                if (margin != null && format != FinancialFormat.FINANCIAL) {
                    put(period, "operating_margin", "영업이익률", margin, "%", receiptNo, periodEnd);
                }
            }
            if (netIncome.hasCurrent()) {
                put(period, "net_income", "당기순이익", netIncome.current(), currency, receiptNo, periodEnd);
            }
        }

        void addBalanceFacts(PeriodKey period, boolean isAnnual, MetricValue totalAssets, MetricValue totalLiabilities,
                MetricValue totalEquity, MetricValue capitalStock, String receiptNo, LocalDate periodEnd) {
            label(period, isAnnual);
            if (totalAssets.hasCurrent()) {
                put(period, "total_assets", "자산총계", totalAssets.current(), currency, receiptNo, periodEnd);
            }
            if (totalLiabilities.hasCurrent()) {
                put(period, "total_liabilities", "부채총계", totalLiabilities.current(), currency, receiptNo, periodEnd);
            }
            if (totalEquity.hasCurrent()) {
                put(period, "total_equity", "자본총계", totalEquity.current(), currency, receiptNo, periodEnd);
            }
            if (capitalStock.hasCurrent()) {
                put(period, "capital_stock", "자본금", capitalStock.current(), currency, receiptNo, periodEnd);
            }
            if (format == FinancialFormat.FINANCIAL) {
                unavailable.add(new FinancialExplainInput.Unavailable("debt_ratio", "NOT_APPLICABLE_FORMAT"));
            } else {
                BigDecimal debtRatio = FinancialRatios.debtRatio(totalLiabilities.current(), totalEquity.current());
                BigDecimal debtRatioPrior = FinancialRatios.debtRatio(totalLiabilities.prior(), totalEquity.prior());
                if (debtRatio == null) {
                    unavailable.add(new FinancialExplainInput.Unavailable("debt_ratio", "ACCOUNT_MISSING"));
                } else {
                    put(period, "debt_ratio", "부채비율", debtRatio, "%", receiptNo, periodEnd);
                    if (debtRatioPrior != null) {
                        put(period, "debt_ratio_prior_end", "부채비율(전기말)", debtRatioPrior, "%", receiptNo, periodEnd);
                        put(period, "debt_ratio_diff", "부채비율 변화", debtRatio.subtract(debtRatioPrior), "%p", receiptNo, periodEnd);
                    }
                }
            }
            BigDecimal impairment = FinancialRatios.impairmentRatio(capitalStock.current(), totalEquity.current());
            if (impairment != null) {
                put(period, "impairment_ratio", "자본잠식률", impairment, "%", receiptNo, periodEnd);
            }
            hasBalanceFacts = true;
        }

        /** 재무상태표 불일치 기간: 재무상태 지표 전체를 unavailable로 두고 사실은 넣지 않는다(§4.4.2). */
        void markBalanceUnavailable(PeriodKey period, boolean isAnnual) {
            label(period, isAnnual);
            for (String metric : List.of("total_assets", "total_liabilities", "total_equity", "capital_stock",
                    "debt_ratio", "impairment_ratio")) {
                unavailable.add(new FinancialExplainInput.Unavailable(metric, "INCONSISTENT_BALANCE"));
            }
        }

        void addFlowFacts(List<QuarterEntry> quartersDesc) {
            int revenueRun = signedRun(quartersDesc, q -> q.flowSignalEligible() && q.revenue().hasCurrent() && q.revenue().hasPrior()
                    ? q.revenue().current().subtract(q.revenue().prior()).signum() : null);
            int lossRun = signedRun(quartersDesc, q -> q.hasOperatingIncome() ? -q.operatingIncome().current().signum() : null);
            if (quartersDesc.isEmpty()) {
                return;
            }
            PeriodKey latest = quartersDesc.get(0).key();
            if (Math.abs(revenueRun) >= 2) {
                label(latest, false);
                put(latest, "revenue_yoy_run", "매출 증가율 같은 방향 지속 분기 수", BigDecimal.valueOf(revenueRun), "분기", null, null);
                hasFlowFacts = true;
            }
            if (lossRun >= 2) {
                label(latest, false);
                put(latest, "operating_loss_run", "연속 영업적자 분기 수", BigDecimal.valueOf(lossRun), "분기", null, null);
                hasFlowFacts = true;
            }
        }

        /** 최신 분기부터 거슬러 올라가며 부호가 같은(0 제외) 연속 구간 길이. 부호가 없으면(null) 중단. 빈 기간도 중단. */
        private static int signedRun(List<QuarterEntry> quartersDesc, java.util.function.Function<QuarterEntry, Integer> signFn) {
            int count = 0;
            Integer sign = null;
            QuarterEntry prev = null;
            for (QuarterEntry q : quartersDesc) {
                Integer s = signFn.apply(q);
                if (s == null || s == 0) {
                    break;
                }
                if (prev != null && !FinancialRatios.isConsecutiveQuarter(q.periodEnd(), prev.periodEnd())) {
                    break;
                }
                if (sign == null) {
                    sign = s;
                } else if (!sign.equals(s)) {
                    break;
                }
                count++;
                prev = q;
            }
            return sign != null && sign < 0 ? -count : count;
        }

        private void label(PeriodKey period, boolean isAnnual) {
            periodLabels.putIfAbsent(period.displayKey(),
                    isAnnual ? PeriodLabels.ofAnnual(period.fiscalYearStart(), fiscalMonth)
                            : PeriodLabels.ofQuarter(period, fiscalMonth));
        }

        private void put(PeriodKey period, String metric, String name, BigDecimal value, String unit, String receiptNo,
                LocalDate periodEnd) {
            String key = "fin." + metric + "." + period.displayKey();
            String display = PeriodLabels.formatValue(value, unit);
            String sign = value.signum() < 0 ? "-" : "+";
            facts.put(key, new FinancialExplainInput.Fact(key, name, display, sign, period.displayKey()));
            factSnapshots.put(key, new ValueSnapshot.FactSnapshot(display, value, unit, period.displayKey(),
                    periodEnd == null ? null : periodEnd.toString(), receiptNo, basis, currency));
        }
    }
}
