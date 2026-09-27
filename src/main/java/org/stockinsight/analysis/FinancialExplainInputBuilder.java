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
import org.stockinsight.financial.FlowRuns;
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

    /**
     * fx-input-3(D-54): 신호 기준 기간·섹션 배정·재무상태 사실 구성. fx-input-4(D-54 보완·D-55): 네 섹션 모두 사실 배정
     * (사실표 = 배정의 합집합), 흑자·적자 상태·전환 사실, 관계 항목, {@code history} 시간순. fx-input-5: 전환만 있는 첫 묶음의
     * 전환 사실을 개요에 배정(§7.4.28). fx-input-6(D-59): 사실·기간에서 사람이 읽는 표시 문자열을 뺀다({@link
     * FinancialExplainInput.Fact}에 {@code display} 없음, {@link FinancialExplainInput.PeriodLabel}은 라벨 대신
     * {@code kind}). 사실 이름에 실제 부호로 정해지는 방향을 드러낸다("매출 증가율"/"매출 감소율" 등). 12개월이 아닌
     * 회계연도(결산기 변경)는 "최근 사업연도 사실"(우선순위 7)로 배정하지 않는다. 올릴 때마다 지문이 바뀐다.
     */
    public static final String INPUT_BUILDER_VERSION = "fx-input-6";

    private static final String KRW = "KRW";
    /** 상태·전환 사실의 단위 표시(값 스냅샷). 표시 값이 닫힌 문구라 숫자 형식을 쓰지 않는다. */
    static final String STATE_UNIT = "상태";
    /** unavailable 지표 → 언급 금지 이름. 검증기 규칙 8과 같은 대응을 써서 배정과 검증이 어긋나지 않게 한다. */
    private static final Map<String, String> UNAVAILABLE_NAMES = FinancialExplainValidator.UNAVAILABLE_METRIC_NAMES;
    private static final int MAX_PAST_SIGNALS = 4;
    /** 섹션별 사실 토큰 상한(§4.4.3). 코드가 이 수를 넘게 배정하지 않는다. */
    private static final int OVERVIEW_FACT_LIMIT = 3;
    private static final int SALES_PROFIT_FACT_LIMIT = 6;

    static final String OVERVIEW = "overview";
    static final String SALES_PROFIT = "sales_profit";
    static final String STRUCTURE = "structure";
    static final String HISTORY = "history";

    /**
     * 신호 유형 → 근거 사실 키(기본 이름). ai-analysis.md §4.4.2 "신호 선별과 묶음". 부채비율 급등·자본잠식은 D-54의
     * {@code structure} 구성에 맞춰 전기말 부채비율·잠식률을 뺐다. 매출·영업이익률의 전년 원값은 변화량과 같은 정보라
     * 빼고, 흑자·적자 전환은 전년 영업이익 대신 전환 사실 하나다(D-54 보완·D-55). 배정되지 않은 키는 입력에서 걸러진다.
     */
    private static final Map<SignalType, List<String>> SIGNAL_FACT_KEYS = Map.of(
            SignalType.FIN_REVENUE_CHANGE, List.of("revenue", "revenue_yoy"),
            SignalType.FIN_OPERATING_MARGIN_CHANGE, List.of("operating_margin", "operating_margin_diff"),
            SignalType.FIN_OPERATING_TURN, List.of("operating_income", "operating_income_turn"),
            SignalType.FIN_DEBT_RATIO_JUMP, List.of("debt_ratio", "debt_ratio_diff"),
            SignalType.FIN_OPERATING_LOSS_STREAK, List.of("operating_loss_run"),
            SignalType.FIN_CAPITAL_IMPAIRMENT, List.of("total_equity", "capital_stock"));

    /** NONE일 때 개요에 지정할 변화량 사실의 우선순위(D-54). 처음 있는 하나만 쓴다. */
    private static final List<String> NONE_OVERVIEW_PRIORITY = List.of("revenue_yoy", "operating_margin_diff", "debt_ratio_diff");

    /** 주제 → 활성 신호의 섹션(D-54). */
    private static final Map<String, String> TOPIC_SECTION = Map.of("SALES_PROFIT", SALES_PROFIT, "STRUCTURE", STRUCTURE);

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
        // 자본총계·자본금 비교는 코드가 판정한 자본잠식(활성 신호)이 있을 때만 AI에 준다(D-54).
        boolean activeImpairment = allSignals.stream().anyMatch(
                s -> s.status() == SignalStatus.ACTIVE && s.signalType() == SignalType.FIN_CAPITAL_IMPAIRMENT);
        if (balanceInconsistent) {
            fc.markBalanceUnavailable(latest.periodKey, latest.isAnnual, latest.periodEnd);
        } else {
            fc.addBalanceFacts(latest.periodKey, latest.isAnnual, latest.totalLiabilities,
                    latest.totalEquity, latest.capitalStock, latest.receiptNo, latest.periodEnd, activeImpairment);
        }
        fc.addFlowFacts(summary.quarters());
        PeriodKey flowPeriod = summary.quarters().isEmpty() ? latest.periodKey : summary.quarters().get(0).key();

        SignalSelection selection = selectSignals(allSignals);
        List<FinancialExplainInput.SignalRef> signalRefs = new ArrayList<>();
        Map<String, ValueSnapshot.SignalSnapshot> signalSnapshots = new LinkedHashMap<>();
        Map<CompanySignal, String> refByOriginal = new LinkedHashMap<>();
        Map<CompanySignal, PeriodKey> periodByOriginal = new LinkedHashMap<>();
        int refIndex = 1;
        for (CompanySignal signal : selection.ordered()) {
            String ref = "S" + refIndex++;
            refByOriginal.put(signal, ref);
            // 설명 기준 기간(D-54): 활성 신호는 지금 성립하는 상태이므로 최신 기간이다. 상태 신호·같은 회계연도의
            // 부채비율 급등은 발생 기간이 과거라도 최신 기간으로 설명한다(발생 시점은 배지 → 타임라인이 보여 준다).
            // 이력 신호는 성립한 기간이다.
            boolean active = signal.status() == SignalStatus.ACTIVE;
            PeriodKey referencePeriod = active ? latest.periodKey : derivePeriodKey(signal);
            periodByOriginal.put(signal, referencePeriod);
            // 이력 신호의 기간은 그 기간에 등록된 사실이 없어도 periods에 있어야 한다 — 프롬프트가 "지난 {per.기간}에는
            // {sig.S}…"로 쓰게 하므로 라벨이 없으면 쓸 토큰이 없다(§7.4.7). label()은 putIfAbsent라 이미 있는
            // 라벨(최신·사업연도)을 덮어쓰지 않는다. 이력 신호는 분기 단위라 periodEnd는 쓰지 않는다(null).
            fc.label(referencePeriod, false, null);
            // 이력 신호는 근거 사실을 두지 않는다 — history는 기간 토큰과 배지로만 쓴다(D-54 보완).
            List<PeriodKey> factPeriods = List.of(referencePeriod, flowPeriod);
            List<String> factKeys = !active ? List.of() : SIGNAL_FACT_KEYS.getOrDefault(signal.signalType(), List.of()).stream()
                    .map(base -> factPeriods.stream()
                            .map(p -> "fin." + base + "." + p.displayKey())
                            .filter(fc.facts::containsKey)
                            .findFirst().orElse(null))
                    .filter(java.util.Objects::nonNull)
                    .toList();
            signalRefs.add(new FinancialExplainInput.SignalRef(ref, signal.signalType().name(),
                    signal.status().name(), natureOf(signal), signal.direction().name(), signal.severity().name(),
                    referencePeriod.displayKey(), sectionOf(signal), aiPersistence(signal), factKeys));
            signalSnapshots.put(ref, new ValueSnapshot.SignalSnapshot(
                    signal.signalType().name() + ":" + signal.basisKey(), signal.signalType().name(),
                    signal.status().name(), signal.direction().name(), signal.severity().name(),
                    signal.occurredOn().toString(), signal.sourceReceiptNo()));
        }

        List<FinancialExplainInput.Group> groups = buildGroups(selection.ordered(), refByOriginal, periodByOriginal);
        String changeStatus = selection.hasActiveChange() ? "CHANGED" : "NONE";
        // 최근 사업연도 사실(우선순위 7)은 12개월짜리 회계연도에서만 뽑는다(D-59). 12개월이 아니면(결산기 변경 등)
        // "최근 사업연도 영업이익은 …였어요"가 1년 치처럼 읽힌다(2386 사례, implementation-plan.md §7.4.33).
        PeriodKey latestAnnualKey = latest.isAnnual || summary.annual().isEmpty() || summary.annual().get(0).irregular() ? null
                : annualKey(summary.annual().get(0).fiscalYearStart());
        Assignment assignment = assignSectionFacts(changeStatus, groups, signalRefs, fc,
                latest.periodKey.displayKey(), flowPeriod.displayKey(), latestAnnualKey);
        Map<String, List<String>> sectionFacts = assignment.sectionFacts();

        // 사실표 = 섹션 배정의 합집합(D-54 보완). 값 스냅샷도 같은 사실만 담는다(입력 토큰을 렌더링하기 위한 것, §4.4.5).
        Set<String> assigned = new LinkedHashSet<>();
        sectionFacts.values().forEach(assigned::addAll);
        List<FinancialExplainInput.Fact> facts = fc.facts.values().stream().filter(f -> assigned.contains(f.key())).toList();
        Map<String, ValueSnapshot.FactSnapshot> factSnapshots = new LinkedHashMap<>();
        fc.factSnapshots.forEach((k, v) -> {
            if (assigned.contains(k)) {
                factSnapshots.put(k, v);
            }
        });
        signalRefs = signalRefs.stream()
                .map(s -> new FinancialExplainInput.SignalRef(s.ref(), s.type(), s.status(), s.nature(), s.direction(),
                        s.severity(), s.period(), s.section(), s.persistence(),
                        s.factKeys().stream().filter(assigned::contains).toList()))
                .toList();
        List<String> sections = resolveSections(sectionFacts, signalRefs, selection);

        // 기간은 최신 기간, 배정된 사실의 기간, 신호의 설명 기준 기간만 준다. AI 입력에는 kind만 주고(D-59), 값
        // 스냅샷에는 렌더링용 라벨을 그대로 둔다.
        Set<String> usedPeriods = new LinkedHashSet<>();
        usedPeriods.add(latest.periodKey.displayKey());
        facts.forEach(f -> usedPeriods.add(f.period()));
        signalRefs.forEach(s -> usedPeriods.add(s.period()));
        Map<String, String> periodLabels = new LinkedHashMap<>();
        fc.periodLabels.forEach((k, v) -> {
            if (usedPeriods.contains(k)) {
                periodLabels.put(k, v);
            }
        });
        List<FinancialExplainInput.PeriodLabel> periods = fc.periodKinds.entrySet().stream()
                .filter(e -> usedPeriods.contains(e.getKey()))
                .map(e -> new FinancialExplainInput.PeriodLabel(e.getKey(), e.getValue()))
                .toList();

        FinancialExplainInput input = new FinancialExplainInput(
                new FinancialExplainInput.Company(company.getName(), format.name(), summary.currency(), summary.basis(),
                        company.getFiscalMonth()),
                new FinancialExplainInput.Latest(latest.periodKey.displayKey(), latest.isAnnual ? "ANNUAL" : "QUARTER"),
                changeStatus,
                sections,
                periods,
                facts,
                signalRefs,
                groups,
                sectionFacts,
                assignment.relations(),
                fc.unavailable,
                List.copyOf(fc.doNotMention));

        List<String> limitCodes = allSignals.stream()
                .filter(s -> s.status() == SignalStatus.ACTIVE && s.signalType().name().startsWith("FIN_DATA_"))
                .map(s -> s.signalType().name())
                .sorted()
                .toList();

        ValueSnapshot snapshot = new ValueSnapshot(
                Map.copyOf(factSnapshots),
                Map.copyOf(periodLabels),
                Map.copyOf(signalSnapshots),
                limitCodes,
                new ValueSnapshot.Header(summary.basis(), summary.currency(), format.name(), latest.periodKey.displayKey()));

        Set<String> receiptNos = factSnapshots.values().stream()
                .map(ValueSnapshot.FactSnapshot::receiptNo)
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        String fingerprint = Fingerprint.compute(receiptNos, selection.ordered(), fc.unavailable, INPUT_BUILDER_VERSION);
        return Optional.of(new BuildResult(input, snapshot, fingerprint));
    }

    private static String natureOf(CompanySignal signal) {
        return signal.signalType() == SignalType.FIN_OPERATING_LOSS_STREAK
                || signal.signalType() == SignalType.FIN_CAPITAL_IMPAIRMENT ? "STATE" : "CHANGE";
    }

    /**
     * AI 입력의 신호 지속. 변화 신호의 지속(D-61로 매출·영업이익률에 채움)은 넣지 않는다 — AI는 이어진 기간 수를 흐름
     * 사실 토큰으로만 받고(토큰 밖 숫자는 규칙 3), 입력 계약 {@code fx-input-6}이 채우기 전과 같게 남는다.
     */
    private static Integer aiPersistence(CompanySignal signal) {
        return "STATE".equals(natureOf(signal)) ? signal.persistence() : null;
    }

    /** 신호의 섹션(D-54): 활성 → 주제 섹션, 이력 → history. */
    private static String sectionOf(CompanySignal signal) {
        if (signal.status() != SignalStatus.ACTIVE) {
            return HISTORY;
        }
        return TOPIC_SECTION.get(SIGNAL_TOPIC.getOrDefault(signal.signalType(), "SALES_PROFIT"));
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

    /**
     * 묶음(D-54): 같은 섹션·같은 설명 기준 기간·같은 주제의 신호. 섹션이 다르면 묶지 않으므로 활성 신호와 이력 신호가
     * 한 묶음이 되지 않는다. 묶음 간 순서는 활성 변화 → 활성 상태 → 이력이다. 활성 무리 안에서는 최고 심각도 → 최근 기간
     * → 먼저 나온 순이다(§4.4.2). 이력 묶음은 서술 순서라 설명 기준 기간의 종료일 내림차순(최근 → 과거)이고, 같은 기간끼리만
     * 심각도 순이다(D-54 보완). 이력 신호를 고르는 기준(심각도·최근성 최대 4개)은 {@link #selectSignals}에서 그대로다.
     */
    private List<FinancialExplainInput.Group> buildGroups(List<CompanySignal> ordered, Map<CompanySignal, String> refs,
            Map<CompanySignal, PeriodKey> periods) {
        Map<String, List<CompanySignal>> bySectionPeriodTopic = new LinkedHashMap<>();
        for (CompanySignal s : ordered) {
            String topic = SIGNAL_TOPIC.getOrDefault(s.signalType(), "SALES_PROFIT");
            String key = sectionOf(s) + "|" + periods.get(s).displayKey() + "|" + topic;
            bySectionPeriodTopic.computeIfAbsent(key, k -> new ArrayList<>()).add(s);
        }
        record Ranked(FinancialExplainInput.Group group, int category, int maxSeverity, String period, LocalDate periodEnd,
                int order) {
        }
        List<Ranked> ranked = new ArrayList<>();
        int order = 0;
        for (Map.Entry<String, List<CompanySignal>> entry : bySectionPeriodTopic.entrySet()) {
            String[] parts = entry.getKey().split("\\|", 3);
            List<CompanySignal> members = entry.getValue().stream()
                    .sorted(Comparator.comparingInt(s -> WITHIN_GROUP_ORDER.indexOf(s.signalType())))
                    .toList();
            List<String> groupRefs = members.stream().map(refs::get).toList();
            boolean mixed = members.stream().map(CompanySignal::direction).distinct().count() > 1;
            boolean active = members.get(0).status() == SignalStatus.ACTIVE;
            boolean hasChange = members.stream().anyMatch(s -> "CHANGE".equals(natureOf(s)));
            int category = !active ? 2 : hasChange ? 0 : 1;
            int maxSeverity = members.stream().mapToInt(s -> s.severity().ordinal()).max().orElse(0);
            ranked.add(new Ranked(new FinancialExplainInput.Group(parts[0], parts[1], parts[2], groupRefs, mixed),
                    category, maxSeverity, parts[1], periodEnd(periods.get(members.get(0))), order++));
        }
        Comparator<Ranked> activeOrder = Comparator.comparingInt(Ranked::maxSeverity).reversed()
                .thenComparing(Comparator.comparing(Ranked::period).reversed())
                .thenComparingInt(Ranked::order);
        Comparator<Ranked> historyOrder = Comparator.comparing(Ranked::periodEnd).reversed()
                .thenComparing(Comparator.comparingInt(Ranked::maxSeverity).reversed())
                .thenComparingInt(Ranked::order);
        return ranked.stream()
                .sorted(Comparator.comparingInt(Ranked::category)
                        .thenComparing((a, b) -> a.category() == 2 ? historyOrder.compare(a, b) : activeOrder.compare(a, b)))
                .map(Ranked::group)
                .toList();
    }

    /** 기간 키의 종료일(회계연도 시작일 + 분기 × 3개월 − 1일). 연간 키는 4분기와 같다. 결산월이 달라도 달력으로 비교된다. */
    static LocalDate periodEnd(PeriodKey key) {
        return key.fiscalYearStart().plusMonths(3L * key.quarterNumber()).minusDays(1);
    }

    /** 섹션 배정 결과: 섹션 → 사실 키, 그리고 배정된 사실 사이에 코드가 판정한 관계(D-55). */
    private record Assignment(Map<String, List<String>> sectionFacts, List<FinancialExplainInput.Relation> relations) {
    }

    /**
     * 네 섹션의 사실 배정(D-54, D-54 보완). 한 사실은 한 섹션에만 배정하고, 섹션마다 사실 토큰 상한을 넘지 않는다.
     * <ul>
     * <li>개요: 코드가 지정한 변화 근거 사실만. CHANGED면 첫 묶음 신호의 변화량 사실(전환 신호면 전환 사실), NONE이면 매출 증가율 → 영업이익률 변화
     * → 부채비율 변화 순서로 처음 있는 하나.</li>
     * <li>structure: 재무상태 고정 구성(최대 4개)에서 개요에 지정한 사실을 뺀 나머지.</li>
     * <li>sales_profit: 상한 6. {@link #assignSalesProfit} 우선순위로 채우고 넘치면 뒤를 뺀다.</li>
     * <li>history: 사실을 배정하지 않는다(기간 토큰과 배지로만).</li>
     * </ul>
     * 금지 용어({@code doNotMention})나 {@code unavailable}과 이름이 모순되는 사실은 어느 섹션에도 배정하지 않는다.
     */
    private static Assignment assignSectionFacts(String changeStatus, List<FinancialExplainInput.Group> groups,
            List<FinancialExplainInput.SignalRef> signalRefs, FactCollector fc, String latestPeriod, String flowPeriod,
            PeriodKey latestAnnualKey) {
        Map<String, FinancialExplainInput.SignalRef> byRef = new LinkedHashMap<>();
        signalRefs.forEach(s -> byRef.put(s.ref(), s));
        Set<String> overview = new LinkedHashSet<>();
        if ("CHANGED".equals(changeStatus)) {
            // 첫 묶음 신호의 변화 근거 사실: 변화량, 또는 흑자·적자 전환 신호의 전환 사실(fx-v8 재검증 §7.4.28 — 전환만 있는 첫
            // 묶음을 "배지만"으로 두자 716이 3회 모두 개요에 전환을 글자로 쓰려 했다. 전환 사실을 개요에 배정하면 한 사실 한 섹션은 유지된다).
            groups.stream().findFirst().ifPresent(first -> first.refs().stream()
                    .map(byRef::get)
                    .flatMap(s -> s.factKeys().stream())
                    .filter(k -> isChangeFact(k) || isTurnFact(k))
                    .filter(fc::assignable)
                    .forEach(overview::add));
        } else {
            NONE_OVERVIEW_PRIORITY.stream()
                    .map(metric -> "fin." + metric + "." + latestPeriod)
                    .filter(fc::assignable)
                    .findFirst()
                    .ifPresent(overview::add);
        }
        List<String> overviewFacts = overview.stream().limit(OVERVIEW_FACT_LIMIT).toList();
        List<String> structureFacts = fc.structureFactKeys.stream()
                .filter(k -> !overviewFacts.contains(k))
                .filter(fc::assignable)
                .toList();

        Set<String> taken = new LinkedHashSet<>(overviewFacts);
        taken.addAll(structureFacts);
        List<String> activeSalesProfitRefs = groups.stream()
                .filter(g -> SALES_PROFIT.equals(g.section()))
                .flatMap(g -> g.refs().stream())
                .toList();
        List<FinancialExplainInput.Relation> relations = new ArrayList<>();
        List<String> salesProfitFacts = assignSalesProfit(fc, latestPeriod, flowPeriod, latestAnnualKey, taken,
                activeSalesProfitRefs.stream().map(byRef::get).toList(), relations);

        Map<String, List<String>> sectionFacts = new LinkedHashMap<>();
        sectionFacts.put(OVERVIEW, overviewFacts);
        sectionFacts.put(SALES_PROFIT, salesProfitFacts);
        sectionFacts.put(STRUCTURE, structureFacts);
        sectionFacts.put(HISTORY, List.of());
        return new Assignment(java.util.Collections.unmodifiableMap(sectionFacts), List.copyOf(relations));
    }

    /**
     * {@code sales_profit} 배정(D-54 보완, 상한 6). 우선순위대로 채우고 넘치면 뒤를 뺀다. 관계 단위(순이익과 그 상태·전환
     * 사실)는 쪼개지 않는다 — 상한 때문에 한쪽만 남기지 않는다.
     * <ol>
     * <li>최신 매출·영업이익(규모)</li>
     * <li>이 섹션 활성 신호의 최신 근거 사실(흑자·적자 전환 신호면 영업이익 전환 사실)</li>
     * <li>순이익 + 상태 또는 전환 사실: 순이익이 전년과 비교해 바뀌었으면 전환, 아니면 영업이익과 상태가 다를 때만 상태</li>
     * <li>영업이익률</li>
     * <li>흐름 사실(연속 영업적자 → 매출 연속 흐름)</li>
     * <li>전년 같은 기간과의 영업이익 비교: 부호가 같으면 전년 영업이익(둘 다 흑자면 방향 관계 R2 함께), 부호가 바뀌었는데
     * 전환 신호가 없으면 영업이익 전환 사실</li>
     * <li>최근 사업연도 사실 하나(연간 흐름): 최신 기간이 분기일 때만, 매출 증가율이 있으면 그것, 없으면 영업이익</li>
     * </ol>
     * 개요·structure에 배정된 사실, 매출·영업이익률·순이익의 전년 원값은 배정하지 않는다(변화량이 있으면 같은 정보이고,
     * 없으면 코드가 일부러 방향을 주지 않은 경우라 원값 짝이 그 방향을 되살린다, D-35·D-41).
     */
    private static List<String> assignSalesProfit(FactCollector fc, String latestPeriod, String flowPeriod,
            PeriodKey latestAnnualKey, Set<String> taken, List<FinancialExplainInput.SignalRef> activeSignals,
            List<FinancialExplainInput.Relation> relations) {
        List<String> out = new ArrayList<>();
        java.util.function.Predicate<List<String>> addUnit = keys -> {
            List<String> fresh = keys.stream().filter(k -> !out.contains(k)).toList();
            boolean ok = !fresh.isEmpty() && fresh.stream().allMatch(k -> fc.assignable(k) && !taken.contains(k))
                    && out.size() + fresh.size() <= SALES_PROFIT_FACT_LIMIT;
            if (ok) {
                out.addAll(fresh);
            }
            return ok || (!keys.isEmpty() && fresh.isEmpty());
        };
        String revenue = "fin.revenue." + latestPeriod;
        String operatingIncome = "fin.operating_income." + latestPeriod;
        String operatingIncomePrior = "fin.operating_income_prior." + latestPeriod;
        String operatingTurn = "fin.operating_income_turn." + latestPeriod;
        String netIncome = "fin.net_income." + latestPeriod;
        String netTurn = "fin.net_income_turn." + latestPeriod;
        String netStatus = "fin.net_income_status." + latestPeriod;

        // 1. 규모
        addUnit.test(List.of(revenue));
        addUnit.test(List.of(operatingIncome));
        // 2. 활성 신호의 근거 사실(전년 원값은 근거 목록에 없다)
        for (FinancialExplainInput.SignalRef signal : activeSignals) {
            signal.factKeys().forEach(k -> addUnit.test(List.of(k)));
        }
        // 3. 순이익과 그 상태·전환(관계 단위)
        String netState = null;
        if (fc.facts.containsKey(netTurn)) {
            netState = addUnit.test(List.of(netIncome, netTurn)) ? netTurn : null;
        } else {
            Integer opSign = fc.signOf(operatingIncome);
            Integer netSign = fc.signOf(netIncome);
            if (fc.facts.containsKey(netStatus) && opSign != null && netSign != null && !opSign.equals(netSign)) {
                netState = addUnit.test(List.of(netIncome, netStatus)) ? netStatus : null;
            }
        }
        // 4. 영업이익률
        addUnit.test(List.of("fin.operating_margin." + latestPeriod));
        // 5. 흐름 사실: 연속 영업적자 → 매출 연속 흐름. 매출의 방향은 매출 변화량·신호가 이미 담는 경우가 많아 뒤에 둔다
        //    (§7.4.26 모의 배정의 순서, 737: 매출 연속 흐름이 상한으로 빠진다).
        addUnit.test(List.of("fin.operating_loss_run." + flowPeriod));
        addUnit.test(List.of("fin.revenue_yoy_run." + flowPeriod));
        // 6. 전년 같은 기간과의 영업이익 비교
        Integer opSign = fc.signOf(operatingIncome);
        Integer opPriorSign = fc.signOf(operatingIncomePrior);
        if (fc.facts.containsKey(operatingTurn)) {
            addUnit.test(List.of(operatingTurn));
        } else if (opSign != null && opSign.equals(opPriorSign) && out.contains(operatingIncome)
                && addUnit.test(List.of(operatingIncomePrior)) && opSign > 0) {
            int cmp = fc.rawValue(operatingIncome).compareTo(fc.rawValue(operatingIncomePrior));
            relations.add(new FinancialExplainInput.Relation(FinancialExplainInput.Relation.OPERATING_INCOME_DIRECTION,
                    List.of(operatingIncome, operatingIncomePrior), cmp > 0 ? "UP" : cmp < 0 ? "DOWN" : "SAME"));
        }
        // 7. 최근 사업연도 사실 하나
        if (latestAnnualKey != null) {
            String annualGrowth = "fin.revenue_yoy." + latestAnnualKey.displayKey();
            String annualOperating = "fin.operating_income." + latestAnnualKey.displayKey();
            if (!(fc.assignable(annualGrowth) && addUnit.test(List.of(annualGrowth)))) {
                addUnit.test(List.of(annualOperating));
            }
        }
        // R5: 순이익의 흑자·적자가 영업이익과 다름. 두 사실이 모두 배정됐을 때만.
        Integer netSign = fc.signOf(netIncome);
        if (netState != null && out.contains(operatingIncome) && opSign != null && netSign != null && !opSign.equals(netSign)) {
            relations.add(new FinancialExplainInput.Relation(FinancialExplainInput.Relation.STATE_DIFFERENCE,
                    List.of(netState, operatingIncome), "DIFFERENT"));
        }
        return List.copyOf(out);
    }

    /** 변화량 사실(전년 동기 대비 증가율·차이). 흐름 사실({@code _yoy_run})은 아니다. */
    static boolean isChangeFact(String factKey) {
        return factKey.contains("_yoy.") || factKey.contains("_diff.");
    }

    /** 흑자·적자 전환 사실(D-55 R4). */
    static boolean isTurnFact(String factKey) {
        return factKey.contains("_turn.");
    }

    /**
     * 섹션은 배정된 사실이나 신호가 있을 때 연다(D-54). 흐름 사실(revenue_yoy_run, operating_loss_run)은 최신 기간까지
     * 이어지는 상태라 history(과거형) 대상이 아니다(D-45). history는 오직 PAST 신호가 있을 때만 연다. structure는
     * 재무상태 사실이 없어도(재무상태표 불일치) 활성 재무 구조 신호가 있으면 연다 — 배지만으로 쓴다(D-52).
     */
    private static List<String> resolveSections(Map<String, List<String>> sectionFacts,
            List<FinancialExplainInput.SignalRef> signalRefs, SignalSelection selection) {
        List<String> sections = new ArrayList<>();
        sections.add(OVERVIEW);
        if (!sectionFacts.get(SALES_PROFIT).isEmpty() || signalRefs.stream().anyMatch(s -> SALES_PROFIT.equals(s.section()))) {
            sections.add(SALES_PROFIT);
        }
        if (!sectionFacts.get(STRUCTURE).isEmpty() || signalRefs.stream().anyMatch(s -> STRUCTURE.equals(s.section()))) {
            sections.add(STRUCTURE);
        }
        if (!selection.past().isEmpty()) {
            sections.add(HISTORY);
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
        /** 기간 종류(QUARTER·ANNUAL, D-59) — AI 입력의 {@code periods[].kind}. 라벨과 같은 자리에서 함께 등록한다. */
        final Map<String, String> periodKinds = new LinkedHashMap<>();
        final List<FinancialExplainInput.Unavailable> unavailable = new ArrayList<>();
        final Set<String> doNotMention = new LinkedHashSet<>();
        /** structure에 배정할 재무상태 사실 키(D-54 고정 구성). */
        final List<String> structureFactKeys = new ArrayList<>();
        final boolean nonKrw;
        final FinancialFormat format;
        final String currency;
        final String basis;
        final Integer fiscalMonth;

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
            label(period, isAnnual, periodEnd);
            // 비교 기준 명사(D-59): 최신 기간이 분기면 "전년 같은 분기", 사업보고서면 "전년 같은 기간". 사실 이름이 이
            // 명사를 정확히 담아야 AI가 옮겨 적어도 틀리지 않는다(737 fx-v11의 "전년 같은 기간" 복사, §7.4.30).
            String comparePeriodNoun = isAnnual ? "기간" : "분기";
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
                        put(period, "revenue_yoy", directionalName("매출", "증가율", "감소율", pct)
                                + "(전년 같은 " + comparePeriodNoun + " 대비)", pct, "%", receiptNo, periodEnd);
                    }
                }
            } else {
                unavailable.add(new FinancialExplainInput.Unavailable("revenue", "ACCOUNT_MISSING"));
                // 금융형은 이미 생성자에서 "매출"을 doNotMention에 넣지만(형식 때문에), 일반형인데 계정 자체가
                // 없는 경우는 그 신호가 없었다 — unavailable만으로는 "매출" 언급을 막는 힘이 약해(§7.4.9,
                // implementation-plan.md) doNotMention에도 명시적으로 넣는다. Set이라 중복 추가는 안전하다.
                doNotMention.add("매출");
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
                        BigDecimal marginDiff = marginCurrent.subtract(marginPrior);
                        put(period, "operating_margin_diff", directionalName("영업이익률", "상승폭", "하락폭", marginDiff)
                                + "(전년 같은 " + comparePeriodNoun + " 대비)", marginDiff, "%p", receiptNo, periodEnd);
                    }
                }
            }
            if (netIncome.hasCurrent()) {
                put(period, "net_income", "당기순이익", netIncome.current(), currency, receiptNo, periodEnd);
                if (netIncome.hasPrior()) {
                    put(period, "net_income_prior", "당기순이익(전년 동기)", netIncome.prior(), currency, receiptNo, periodEnd);
                }
                // 흑자·적자 상태 사실은 순이익에만 둔다(D-55 R3). 영업이익의 상태는 금액 부호·전환 사실·연속 적자로 드러난다.
                if (netIncome.current().signum() != 0) {
                    putState(period, "net_income_status", "당기순이익 흑자·적자",
                            netIncome.current().signum() > 0 ? "흑자" : "적자", netIncome.current(), receiptNo, periodEnd);
                }
            }
            putTurn(period, "operating_income_turn", "영업이익 흑자·적자 전환(전년 같은 " + comparePeriodNoun + " 대비)",
                    operatingIncome, receiptNo, periodEnd);
            putTurn(period, "net_income_turn", "당기순이익 흑자·적자 전환(전년 같은 " + comparePeriodNoun + " 대비)",
                    netIncome, receiptNo, periodEnd);
        }

        /** D-59: 사실 이름에 실제 부호가 정한 방향을 드러낸다. 0은 상승·증가 쪽으로 본다({@link #put}의 부호 규칙과 같다). */
        private static String directionalName(String base, String risePhrase, String fallPhrase, BigDecimal value) {
            return base + " " + (value.signum() < 0 ? fallPhrase : risePhrase);
        }

        /**
         * 전환 사실(D-55 R4, D-54 보완 "전환의 정의"): 같은 보고서의 최신·전년 같은 기간 값의 부호가 다를 때만(둘 다 0이 아님).
         * 신호와 무관한 부호 사실이다. 표시 문구는 닫힌 집합("적자에서 흑자로"·"흑자에서 적자로")이다.
         */
        private void putTurn(PeriodKey period, String metric, String name, MetricValue value, String receiptNo,
                LocalDate periodEnd) {
            if (!value.hasCurrent() || !value.hasPrior()) {
                return;
            }
            int now = value.current().signum();
            int before = value.prior().signum();
            if (now == 0 || before == 0 || now == before) {
                return;
            }
            putState(period, metric, name, now > 0 ? "적자에서 흑자로" : "흑자에서 적자로", value.current(), receiptNo, periodEnd);
        }

        /**
         * 상태·전환 사실. AI 입력에는 사실 이름·부호만 준다(D-59) — 표시 값(닫힌 문구)은 AI가 보지 않아도 된다.
         * 전환·상태 문장의 서술어("돌아섰어요" 등)는 값과 무관하게 성립하는 고정 문형이기 때문이다(구현 중 정정,
         * decisions.md D-59 "구현 중 정정" 참고). 값 스냅샷에는 렌더링용 닫힌 문구와 부호의 근거인 원값을 담는다.
         */
        private void putState(PeriodKey period, String metric, String name, String display, BigDecimal basisValue,
                String receiptNo, LocalDate periodEnd) {
            String key = "fin." + metric + "." + period.displayKey();
            String sign = basisValue.signum() < 0 ? "-" : "+";
            facts.put(key, new FinancialExplainInput.Fact(key, name, sign, period.displayKey()));
            factSnapshots.put(key, new ValueSnapshot.FactSnapshot(display, basisValue, STATE_UNIT, period.displayKey(),
                    periodEnd == null ? null : periodEnd.toString(), receiptNo, basis, currency));
        }

        /**
         * 배정할 수 있는 사실인가: 사실표에 있고, 이름이 금지 용어({@code doNotMention})나 계산하지 못한 지표
         * ({@code unavailable})의 이름과 모순되지 않는다(D-54 보완, 2715형 자기모순 방지). 검증기 규칙 8과 같은 이름 대응을 쓴다.
         */
        boolean assignable(String key) {
            FinancialExplainInput.Fact fact = facts.get(key);
            if (fact == null) {
                return false;
            }
            if (doNotMention.stream().anyMatch(fact.name()::contains)) {
                return false;
            }
            return unavailable.stream()
                    .map(u -> UNAVAILABLE_NAMES.get(u.metric()))
                    .filter(java.util.Objects::nonNull)
                    .noneMatch(fact.name()::contains);
        }

        /** 사실 원값의 부호(−1·0·1). 사실이 없으면 null. */
        Integer signOf(String key) {
            ValueSnapshot.FactSnapshot s = factSnapshots.get(key);
            return s == null ? null : s.rawValue().signum();
        }

        BigDecimal rawValue(String key) {
            return factSnapshots.get(key).rawValue();
        }

        void addAnnualFacts(PeriodKey period, MetricValue revenue, MetricValue operatingIncome, MetricValue netIncome,
                String receiptNo, LocalDate periodEnd) {
            label(period, true, periodEnd);
            String revenueName = format == FinancialFormat.FINANCIAL ? "영업수익" : "매출액";
            if (revenue.hasCurrent()) {
                put(period, "revenue", revenueName, revenue.current(), currency, receiptNo, periodEnd);
                if (format != FinancialFormat.FINANCIAL && revenue.hasPrior() && !nonKrw && revenue.prior().signum() > 0
                        && revenue.prior().abs().compareTo(FinancialRatios.REVENUE_BASE_ANNUAL) >= 0) {
                    BigDecimal pct = FinancialRatios.percentChange(revenue.current(), revenue.prior());
                    put(period, "revenue_yoy", directionalName("매출", "증가율", "감소율", pct) + "(전기 대비)",
                            pct, "%", receiptNo, periodEnd);
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

        /**
         * 재무상태 사실(D-54 고정 구성, 최대 4개 = structure 상한). 일반형은 부채비율 + 전기말 대비 변화량, 부채비율을
         * 계산할 수 없는 일반형과 금융형은 자본총계. 자본총계·자본금 비교는 코드가 판정한 자본잠식(활성 신호)이 있을
         * 때만 둘 다 준다. 전기말 부채비율(= 부채비율 − 변화량, 반복), 자산·부채 총계(부채비율이 관계를 담는 수준 값),
         * 잠식률(크기는 신호 심각도가 담는다)은 AI 입력과 값 스냅샷에 넣지 않는다 — 화면은 재무 요약에서 코드가 보여 준다.
         */
        void addBalanceFacts(PeriodKey period, boolean isAnnual, MetricValue totalLiabilities,
                MetricValue totalEquity, MetricValue capitalStock, String receiptNo, LocalDate periodEnd,
                boolean activeImpairment) {
            label(period, isAnnual, periodEnd);
            boolean hasDebtRatio = false;
            if (format == FinancialFormat.FINANCIAL) {
                unavailable.add(new FinancialExplainInput.Unavailable("debt_ratio", "NOT_APPLICABLE_FORMAT"));
            } else {
                BigDecimal debtRatio = FinancialRatios.debtRatio(totalLiabilities.current(), totalEquity.current());
                BigDecimal debtRatioPrior = FinancialRatios.debtRatio(totalLiabilities.prior(), totalEquity.prior());
                if (debtRatio == null) {
                    unavailable.add(new FinancialExplainInput.Unavailable("debt_ratio", "ACCOUNT_MISSING"));
                } else {
                    hasDebtRatio = true;
                    putStructure(period, "debt_ratio", "부채비율", debtRatio, "%", receiptNo, periodEnd);
                    if (debtRatioPrior != null) {
                        // "(전기말 대비)"는 프롬프트가 쓰는 "지난 회계연도 말"과 말이 달랐다(D-59, 2386의 "변화했어요"에
                        // 거든 것으로 보이는 원인 중 하나, implementation-plan.md §7.4.33).
                        BigDecimal debtRatioDiff = debtRatio.subtract(debtRatioPrior);
                        putStructure(period, "debt_ratio_diff",
                                directionalName("부채비율", "상승폭", "하락폭", debtRatioDiff) + "(지난 회계연도 말 대비)",
                                debtRatioDiff, "%p", receiptNo, periodEnd);
                    }
                }
            }
            if ((!hasDebtRatio || activeImpairment) && totalEquity.hasCurrent()) {
                putStructure(period, "total_equity", "자본총계", totalEquity.current(), currency, receiptNo, periodEnd);
            }
            if (activeImpairment && capitalStock.hasCurrent()) {
                putStructure(period, "capital_stock", "자본금", capitalStock.current(), currency, receiptNo, periodEnd);
            }

        }

        private void putStructure(PeriodKey period, String metric, String name, BigDecimal value, String unit,
                String receiptNo, LocalDate periodEnd) {
            put(period, metric, name, value, unit, receiptNo, periodEnd);
            structureFactKeys.add("fin." + metric + "." + period.displayKey());
        }

        /** 재무상태표 불일치 기간: 재무상태 지표 전체를 unavailable로 두고 사실은 넣지 않는다(§4.4.2). */
        void markBalanceUnavailable(PeriodKey period, boolean isAnnual, LocalDate periodEnd) {
            label(period, isAnnual, periodEnd);
            for (String metric : List.of("total_assets", "total_liabilities", "total_equity", "capital_stock",
                    "debt_ratio", "impairment_ratio")) {
                unavailable.add(new FinancialExplainInput.Unavailable(metric, "INCONSISTENT_BALANCE"));
            }
        }

        void addFlowFacts(List<QuarterEntry> quartersDesc) {
            int revenueRun = FlowRuns.signedQuarterRun(quartersDesc, 0, FlowRuns::revenueYoySign);
            int lossRun = FlowRuns.signedQuarterRun(quartersDesc, 0,
                    q -> q.hasOperatingIncome() ? -q.operatingIncome().current().signum() : null);
            if (quartersDesc.isEmpty()) {
                return;
            }
            PeriodKey latest = quartersDesc.get(0).key();
            // 최신 기간의 revenue_yoy를 줄 수 있을 때만 준다(D-41). 못 주면(금융형·비원화·기준값 미만 등) 흐름도 주지 않는다.
            boolean latestRevenueYoyAvailable = facts.containsKey("fin.revenue_yoy." + latest.displayKey());
            if (latestRevenueYoyAvailable && Math.abs(revenueRun) >= 2) {
                label(latest, false, null);
                String runName = "매출 " + (revenueRun >= 0 ? "증가" : "감소") + " 연속 분기 수";
                put(latest, "revenue_yoy_run", runName, BigDecimal.valueOf(revenueRun), "분기", null, null);
            }
            if (lossRun >= 2) {
                label(latest, false, null);
                put(latest, "operating_loss_run", "연속 영업적자 분기 수", BigDecimal.valueOf(lossRun), "분기", null, null);
            }
        }

        /**
         * 라벨(값 스냅샷·렌더링용, D-59)과 종류({@code kind}, AI 입력용)를 함께 등록한다. {@code periodEnd}는 12개월이
         * 아닌 회계연도(결산기 변경)를 가려내는 데만 쓴다({@code isAnnual}이 아니면 무시된다, null 가능).
         */
        private void label(PeriodKey period, boolean isAnnual, LocalDate periodEnd) {
            String key = period.displayKey();
            periodLabels.putIfAbsent(key,
                    isAnnual ? PeriodLabels.ofAnnual(period.fiscalYearStart(), periodEnd, fiscalMonth)
                            : PeriodLabels.ofQuarter(period, fiscalMonth));
            periodKinds.putIfAbsent(key, isAnnual ? "ANNUAL" : "QUARTER");
        }

        private void put(PeriodKey period, String metric, String name, BigDecimal value, String unit, String receiptNo,
                LocalDate periodEnd) {
            String key = "fin." + metric + "." + period.displayKey();
            String display = PeriodLabels.formatValue(value, unit);
            String sign = value.signum() < 0 ? "-" : "+";
            facts.put(key, new FinancialExplainInput.Fact(key, name, sign, period.displayKey()));
            factSnapshots.put(key, new ValueSnapshot.FactSnapshot(display, value, unit, period.displayKey(),
                    periodEnd == null ? null : periodEnd.toString(), receiptNo, basis, currency));
        }
    }
}
