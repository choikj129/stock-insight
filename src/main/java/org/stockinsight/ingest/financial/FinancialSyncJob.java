package org.stockinsight.ingest.financial;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.stockinsight.common.config.TimeConfig;
import org.stockinsight.common.pipeline.PipelineRunRecorder;
import org.stockinsight.common.pipeline.PipelineRunRecorder.RunStatus;
import org.stockinsight.company.CompanyService;
import org.stockinsight.disclosure.DisclosureService;
import org.stockinsight.disclosure.PeriodicTrigger;
import org.stockinsight.financial.FinancialPeriodException;
import org.stockinsight.financial.FinancialService;
import org.stockinsight.financial.FinancialService.ReplaceResult;
import org.stockinsight.financial.PeriodType;
import org.stockinsight.financial.PeriodicReportName;
import org.stockinsight.financial.PeriodicReportName.QueryKey;
import org.stockinsight.financial.PeriodicReportNameException;
import org.stockinsight.financial.RawAccountLine;
import org.stockinsight.ingest.checkpoint.IngestCheckpoint;
import org.stockinsight.ingest.checkpoint.IngestCheckpointRepository;
import org.stockinsight.ingest.dart.DartApi;
import org.stockinsight.ingest.dart.DartApiException;
import org.stockinsight.ingest.dart.DartKeyAccount;

/**
 * 재무 수집 (docs/implementation-plan.md §4). 다중회사 주요계정(fnlttMultiAcnt)을 (bsns_year, report_code) ×
 * 기업 100개 묶음으로 받는다.
 * <ol>
 *     <li>계기: 정기공시(원 공시·정정 모두)의 보고서명에서 조회 키를 계산해 그 기업·기간을 다시 받는다.</li>
 *     <li>초기 적재: 최근 {@code initialLoadYears}+1개 연도 × 4개 보고서 종류를 체크포인트 없는 기업만 받는다.</li>
 *     <li>계기 대상을 먼저 처리하고 초기 적재를 그 뒤에 처리한다. 호출 한도에 닿으면 멈추고 다음 실행이 이어서 처리한다.</li>
 * </ol>
 */
@Component
public class FinancialSyncJob {

    public static final String JOB_NAME = "financial-sync";
    public static final String CHECKPOINT_SOURCE = "DART_FINANCIAL";

    static final int BATCH_SIZE = 100;
    private static final List<PeriodType> REPORT_KINDS = List.of(PeriodType.Q1, PeriodType.H1, PeriodType.Q3, PeriodType.FY);

    private static final Logger log = LoggerFactory.getLogger(FinancialSyncJob.class);

    private final DartApi dart;
    private final CompanyService companyService;
    private final DisclosureService disclosureService;
    private final FinancialService financialService;
    private final IngestCheckpointRepository checkpoints;
    private final PipelineRunRecorder runRecorder;
    private final FinancialSyncProperties properties;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final AtomicBoolean running = new AtomicBoolean(false);

    FinancialSyncJob(DartApi dart, CompanyService companyService, DisclosureService disclosureService,
            FinancialService financialService, IngestCheckpointRepository checkpoints, PipelineRunRecorder runRecorder,
            FinancialSyncProperties properties, TransactionTemplate transaction, Clock clock) {
        this.dart = dart;
        this.companyService = companyService;
        this.disclosureService = disclosureService;
        this.financialService = financialService;
        this.checkpoints = checkpoints;
        this.runRecorder = runRecorder;
        this.properties = properties;
        this.transaction = transaction;
        this.clock = clock;
    }

    public Result run() {
        if (!running.compareAndSet(false, true)) {
            log.info("재무 수집이 이미 실행 중이라 건너뜁니다");
            return Result.skipped();
        }
        long runId = runRecorder.start(JOB_NAME);
        Progress progress = new Progress();
        try {
            Result result = sync(progress);
            runRecorder.finish(runId, result.status(), progress.processed, progress.failed, result.summary());
            log.info("재무 수집 완료: {}", result.summary());
            return result;
        } catch (DartApiException e) {
            String message = "중단: " + e.getMessage() + " / " + progress;
            runRecorder.finish(runId, RunStatus.FAILED, progress.processed, progress.failed, message);
            log.error("재무 수집 {}", message);
            return Result.failed(message);
        } catch (RuntimeException e) {
            String message = "오류: " + e.getClass().getSimpleName() + ": " + e.getMessage() + " / " + progress;
            runRecorder.finish(runId, RunStatus.FAILED, progress.processed, progress.failed, message);
            log.error("재무 수집 실패", e);
            return Result.failed(message);
        } finally {
            running.set(false);
        }
    }

    private Result sync(Progress progress) {
        Map<String, Long> companyIdByCorpCode = companyService.activeCompanyIdsByDartCorpCode();
        if (companyIdByCorpCode.isEmpty()) {
            throw new IllegalStateException("ACTIVE 기업이 없습니다. 기업 목록 동기화(company-sync)를 먼저 실행해야 합니다");
        }
        Map<Long, String> corpCodeByCompanyId = new HashMap<>();
        companyIdByCorpCode.forEach((corpCode, companyId) -> corpCodeByCompanyId.put(companyId, corpCode));
        Map<Long, Integer> fiscalMonthByCompanyId = companyService.activeFiscalMonthsByCompanyId();

        LocalDate today = LocalDate.now(clock.withZone(TimeConfig.SERVICE_ZONE));
        int minBsnsYear = today.getYear() - properties.initialLoadYears();

        Map<String, IngestCheckpoint> checkpointByKey = checkpoints.findAllBySource(CHECKPOINT_SOURCE);
        Map<TargetKey, TriggerInfo> triggers = resolveTriggers(
                disclosureService.latestPeriodicTriggers(), corpCodeByCompanyId, fiscalMonthByCompanyId, minBsnsYear, progress);

        List<Unit> catchUp = dueCatchUpUnits(triggers, checkpointByKey);
        List<Unit> backfill = dueBackfillUnits(companyIdByCorpCode.keySet(), checkpointByKey, triggers, today.getYear(), minBsnsYear);
        List<Unit> due = new ArrayList<>(catchUp);
        due.addAll(backfill);

        int attempted = 0;
        for (Unit unit : due) {
            if (progress.calls >= properties.maxCallsPerRun()) {
                break;
            }
            processUnit(unit, companyIdByCorpCode, triggers, progress);
            attempted++;
        }
        int remaining = due.size() - attempted;
        return new Result(remaining > 0 ? RunStatus.PARTIAL : RunStatus.SUCCEEDED,
                "묶음 %d(계기 %d, 초기 적재 %d), 처리 %d, 실패 %d, 보고서 신규 %d·갱신 %d·변경없음 %d·삭제 %d, 데이터 없음 %d, 남은 묶음 %d, 호출 %d, 보고서명 해석 오류 %d"
                        .formatted(due.size(), catchUp.size(), backfill.size(), progress.processed, progress.failed,
                                progress.created, progress.updated, progress.unchanged, progress.removed,
                                progress.noData, remaining, progress.calls, progress.resolutionErrors));
    }

    /** 정기공시 계기를 (기업, 조회 키) 단위로 정리한다. 적재 기간보다 오래된 계기와 해석할 수 없는 보고서명은 뺀다. */
    private Map<TargetKey, TriggerInfo> resolveTriggers(List<PeriodicTrigger> periodicTriggers,
            Map<Long, String> corpCodeByCompanyId, Map<Long, Integer> fiscalMonthByCompanyId, int minBsnsYear,
            Progress progress) {
        Map<TargetKey, TriggerInfo> triggers = new HashMap<>();
        for (PeriodicTrigger trigger : periodicTriggers) {
            String corpCode = corpCodeByCompanyId.get(trigger.companyId());
            if (corpCode == null) {
                continue; // ACTIVE 기업이 아니다 (EXCLUDED·DELISTED)
            }
            Integer fiscalMonth = fiscalMonthByCompanyId.get(trigger.companyId());
            Optional<QueryKey> queryKey;
            try {
                queryKey = PeriodicReportName.resolve(trigger.baseReportName(), fiscalMonth);
            } catch (PeriodicReportNameException e) {
                progress.resolutionErrors++;
                log.warn("정기공시 보고서명을 조회 키로 바꾸지 못했습니다 (기업 {}): {}", trigger.companyId(), e.getMessage());
                continue;
            }
            if (queryKey.isEmpty() || queryKey.get().bsnsYear() < minBsnsYear) {
                continue; // 정기공시가 아니거나(등록법인결산서류 등) 적재 기간보다 오래됨
            }
            QueryKey key = queryKey.get();
            TargetKey targetKey = new TargetKey(corpCode, key.bsnsYear(), key.periodType().reportCode());
            TriggerInfo existing = triggers.get(targetKey);
            if (existing == null || trigger.receiptNo().compareTo(existing.receiptNo()) > 0) {
                triggers.put(targetKey, new TriggerInfo(trigger.receiptNo(), key.periodEndMonth()));
            }
        }
        return triggers;
    }

    /** 체크포인트가 있는 대상 중 다시 받아야 할 것: 직전 결과 ERROR, 또는 원천 버전보다 큰 계기가 있음(null은 모든 계기보다 작다). */
    private List<Unit> dueCatchUpUnits(Map<TargetKey, TriggerInfo> triggers, Map<String, IngestCheckpoint> checkpointByKey) {
        Set<TargetKey> due = new LinkedHashSet<>();
        checkpointByKey.forEach((key, checkpoint) -> {
            if (checkpoint.result() == IngestCheckpoint.Result.ERROR) {
                due.add(TargetKey.parse(key));
            }
        });
        triggers.forEach((targetKey, trigger) -> {
            IngestCheckpoint checkpoint = checkpointByKey.get(targetKey.asString());
            if (checkpoint == null) {
                return; // 체크포인트가 없으면 초기 적재 대상이다
            }
            if (checkpoint.sourceVersion() == null || trigger.receiptNo().compareTo(checkpoint.sourceVersion()) > 0) {
                due.add(targetKey);
            }
        });
        return groupIntoUnits(due, Kind.CATCH_UP);
    }

    /** 체크포인트가 아예 없는 (기업, 조회 키). */
    private List<Unit> dueBackfillUnits(Set<String> corpCodes, Map<String, IngestCheckpoint> checkpointByKey,
            Map<TargetKey, TriggerInfo> triggers, int currentYear, int minBsnsYear) {
        Set<TargetKey> due = new LinkedHashSet<>();
        for (int bsnsYear = currentYear; bsnsYear >= minBsnsYear; bsnsYear--) {
            for (PeriodType kind : REPORT_KINDS) {
                for (String corpCode : corpCodes) {
                    TargetKey key = new TargetKey(corpCode, bsnsYear, kind.reportCode());
                    if (!checkpointByKey.containsKey(key.asString())) {
                        due.add(key);
                    }
                }
            }
        }
        return groupIntoUnits(due, Kind.BACKFILL);
    }

    private List<Unit> groupIntoUnits(Set<TargetKey> keys, Kind kind) {
        Map<String, List<String>> byReportKey = new LinkedHashMap<>();
        for (TargetKey key : sorted(keys)) {
            byReportKey.computeIfAbsent(key.bsnsYear() + ":" + key.reportCode(), k -> new ArrayList<>()).add(key.corpCode());
        }
        List<Unit> units = new ArrayList<>();
        byReportKey.forEach((reportKey, corpCodes) -> {
            String[] parts = reportKey.split(":", 2);
            int bsnsYear = Integer.parseInt(parts[0]);
            String reportCode = parts[1];
            for (int i = 0; i < corpCodes.size(); i += BATCH_SIZE) {
                units.add(new Unit(bsnsYear, reportCode, List.copyOf(corpCodes.subList(i, Math.min(corpCodes.size(), i + BATCH_SIZE))), kind));
            }
        });
        return units;
    }

    private static List<TargetKey> sorted(Set<TargetKey> keys) {
        return keys.stream()
                .sorted(Comparator.comparingInt(TargetKey::bsnsYear).reversed()
                        .thenComparing(TargetKey::reportCode)
                        .thenComparing(TargetKey::corpCode))
                .toList();
    }

    /** 한 묶음(같은 bsns_year·report_code, 기업 최대 100개)을 호출하고 기업별로 저장한다. */
    private void processUnit(Unit unit, Map<String, Long> companyIdByCorpCode, Map<TargetKey, TriggerInfo> triggers,
            Progress progress) {
        Instant attemptedAt = clock.instant();
        progress.calls++;
        List<DartKeyAccount> rows;
        try {
            rows = dart.fetchKeyAccounts(unit.corpCodes(), unit.bsnsYear(), unit.reportCode());
        } catch (DartApiException e) {
            if (e.stopsRun()) {
                throw e;
            }
            // 묶음 호출이 실패하면 묶음의 기업 모두 ERROR로 남긴다.
            for (String corpCode : unit.corpCodes()) {
                TargetKey key = new TargetKey(corpCode, unit.bsnsYear(), unit.reportCode());
                recordError(key, triggers.get(key), e.getMessage(), attemptedAt);
                progress.failed++;
            }
            return;
        }
        Map<String, List<DartKeyAccount>> byCorpCode = rows.stream().collect(Collectors.groupingBy(DartKeyAccount::corpCode));
        for (String corpCode : unit.corpCodes()) {
            TargetKey key = new TargetKey(corpCode, unit.bsnsYear(), unit.reportCode());
            processCompany(key, companyIdByCorpCode.get(corpCode), byCorpCode.getOrDefault(corpCode, List.of()),
                    triggers.get(key), attemptedAt, progress);
        }
    }

    private void processCompany(TargetKey key, long companyId, List<DartKeyAccount> rows, TriggerInfo trigger,
            Instant attemptedAt, Progress progress) {
        String sourceVersion = trigger == null ? null : trigger.receiptNo();
        if (rows.isEmpty()) {
            transaction.executeWithoutResult(status ->
                    recordSuccess(key, sourceVersion, IngestCheckpoint.Result.NO_DATA, "응답에 없음", attemptedAt));
            progress.noData++;
            progress.processed++;
            return;
        }
        List<RawAccountLine> raw = rows.stream().map(FinancialSyncJob::toRawLine).toList();
        YearMonth expectedPeriodEndMonth = trigger == null ? null : trigger.periodEndMonth();
        try {
            ReplaceResult result = transaction.execute(status -> {
                ReplaceResult r = financialService.replace(companyId, key.bsnsYear(), key.reportCode(), raw, expectedPeriodEndMonth);
                recordSuccess(key, sourceVersion, IngestCheckpoint.Result.SUCCESS,
                        "신규 %d·갱신 %d·변경없음 %d·삭제 %d".formatted(r.created(), r.updated(), r.unchanged(), r.removed()), attemptedAt);
                return r;
            });
            progress.created += result.created();
            progress.updated += result.updated();
            progress.unchanged += result.unchanged();
            progress.removed += result.removed();
            progress.processed++;
        } catch (FinancialPeriodException e) {
            recordError(key, sourceVersion, e.getMessage(), attemptedAt);
            progress.failed++;
        } catch (RuntimeException e) {
            recordError(key, sourceVersion, e.getClass().getSimpleName() + ": " + e.getMessage(), attemptedAt);
            progress.failed++;
            log.warn("재무 반영 실패: {}", key.asString(), e);
        }
    }

    private void recordSuccess(TargetKey key, String sourceVersion, IngestCheckpoint.Result result, String message, Instant attemptedAt) {
        checkpoints.record(CHECKPOINT_SOURCE, key.asString(), sourceVersion, result, message, attemptedAt);
    }

    private void recordError(TargetKey key, TriggerInfo trigger, String message, Instant attemptedAt) {
        recordError(key, trigger == null ? null : trigger.receiptNo(), message, attemptedAt);
    }

    private void recordError(TargetKey key, String sourceVersion, String message, Instant attemptedAt) {
        checkpoints.record(CHECKPOINT_SOURCE, key.asString(), sourceVersion, IngestCheckpoint.Result.ERROR, message, attemptedAt);
    }

    private static RawAccountLine toRawLine(DartKeyAccount item) {
        return new RawAccountLine(
                item.fsDiv(), item.statement(), item.accountName(), item.ord(), item.currentPeriod(),
                item.currentAmount(), item.currentCumulativeAmount(), item.priorAmount(), item.priorCumulativeAmount(),
                item.prior2Amount(), item.currency(), item.receiptNo());
    }

    /** 대상 키. 체크포인트의 target_key(예: 00126380:2026:11012)와 같은 형식이다. */
    record TargetKey(String corpCode, int bsnsYear, String reportCode) {
        String asString() {
            return corpCode + ":" + bsnsYear + ":" + reportCode;
        }

        static TargetKey parse(String value) {
            String[] parts = value.split(":", 3);
            return new TargetKey(parts[0], Integer.parseInt(parts[1]), parts[2]);
        }
    }

    /** 계기가 된 정기공시의 공시번호와, 그 보고서명이 가리키는 기간 종료월. */
    record TriggerInfo(String receiptNo, YearMonth periodEndMonth) {
    }

    enum Kind {
        /** 체크포인트가 있는 대상: 직전 오류 재시도 또는 새 계기 공시. */
        CATCH_UP,
        /** 체크포인트가 없는 대상 (초기 적재). */
        BACKFILL
    }

    record Unit(int bsnsYear, String reportCode, List<String> corpCodes, Kind kind) {
    }

    private static final class Progress {
        int calls;
        int processed;
        int failed;
        int created;
        int updated;
        int unchanged;
        int removed;
        int noData;
        int resolutionErrors;

        @Override
        public String toString() {
            return "호출 %d, 처리 %d, 실패 %d".formatted(calls, processed, failed);
        }
    }

    public record Result(RunStatus status, String summary) {

        static Result skipped() {
            return new Result(null, "이미 실행 중");
        }

        static Result failed(String message) {
            return new Result(RunStatus.FAILED, message);
        }
    }
}
