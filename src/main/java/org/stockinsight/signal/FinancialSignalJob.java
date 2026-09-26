package org.stockinsight.signal;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.stockinsight.common.config.TimeConfig;
import org.stockinsight.common.pipeline.PipelineRunRecorder;
import org.stockinsight.common.pipeline.PipelineRunRecorder.RunStatus;
import org.stockinsight.financial.FinancialService;
import org.stockinsight.financial.FinancialSummary;
import org.stockinsight.financial.FinancialSummaryService;
import org.stockinsight.ingest.checkpoint.IngestCheckpoint;
import org.stockinsight.ingest.checkpoint.IngestCheckpointRepository;

/**
 * 재무 신호 계산 (docs/implementation-plan.md §6, architecture.md §4.4). 재무가 바뀐 기업만 다시 판정한다.
 * 체크포인트 원천 버전 = "규칙 버전:그 기업 재무 보고서의 마지막 변경 시각". 입력은 DB 값뿐이고 외부 호출이 없다.
 */
@Component
public class FinancialSignalJob {

    public static final String JOB_NAME = "financial-signal";
    public static final String CHECKPOINT_SOURCE = "SIGNAL_FINANCIAL";
    /** 규칙 버전 변경이 아닌데 대상 기업의 이 비율 이상에서 철회가 나오면 경고한다(architecture.md §4.4). */
    private static final double WITHDRAWAL_SPIKE_RATIO = 0.10;
    /** 대상의 이 비율 넘게 한꺼번에 다시 계산되면 규칙 버전 변경 등 전체 재판정으로 보고 철회 급증 경고를 건너뛴다. */
    private static final double FULL_RECOMPUTE_RATIO = 0.5;

    private static final Logger log = LoggerFactory.getLogger(FinancialSignalJob.class);

    private final FinancialSummaryService summaryService;
    private final CompanySignalService signalService;
    private final IngestCheckpointRepository checkpoints;
    private final PipelineRunRecorder runRecorder;
    private final FinancialService financialService;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final AtomicBoolean running = new AtomicBoolean(false);

    FinancialSignalJob(FinancialSummaryService summaryService, CompanySignalService signalService,
            IngestCheckpointRepository checkpoints, PipelineRunRecorder runRecorder,
            FinancialService financialService, TransactionTemplate transaction, Clock clock) {
        this.summaryService = summaryService;
        this.signalService = signalService;
        this.checkpoints = checkpoints;
        this.runRecorder = runRecorder;
        this.financialService = financialService;
        this.transaction = transaction;
        this.clock = clock;
    }

    public Result run() {
        if (!running.compareAndSet(false, true)) {
            log.info("재무 신호 계산이 이미 실행 중이라 건너뜁니다");
            return Result.skipped();
        }
        long runId = runRecorder.start(JOB_NAME);
        Progress progress = new Progress();
        try {
            Result result = sync(progress);
            runRecorder.finish(runId, result.status(), progress.processed, progress.failed, result.summary());
            log.info("재무 신호 계산 완료: {}", result.summary());
            return result;
        } catch (RuntimeException e) {
            String message = "오류: " + e.getClass().getSimpleName() + ": " + e.getMessage() + " / " + progress;
            runRecorder.finish(runId, RunStatus.FAILED, progress.processed, progress.failed, message);
            log.error("재무 신호 계산 실패", e);
            return Result.failed(message);
        } finally {
            running.set(false);
        }
    }

    private Result sync(Progress progress) {
        Map<Long, Instant> lastChangedByCompanyId = financialService.lastChangedByCompanyId();
        if (lastChangedByCompanyId.isEmpty()) {
            throw new IllegalStateException("재무 데이터가 없습니다. 재무 수집(financial-sync)을 먼저 실행해야 합니다");
        }
        Map<String, IngestCheckpoint> checkpointByKey = checkpoints.findAllBySource(CHECKPOINT_SOURCE);
        LocalDate today = LocalDate.now(clock.withZone(TimeConfig.SERVICE_ZONE));

        List<Long> due = new ArrayList<>();
        for (Map.Entry<Long, Instant> entry : lastChangedByCompanyId.entrySet()) {
            String sourceVersion = sourceVersion(entry.getValue());
            IngestCheckpoint checkpoint = checkpointByKey.get(String.valueOf(entry.getKey()));
            boolean dueByChange = checkpoint == null || checkpoint.result() == IngestCheckpoint.Result.ERROR
                    || !sourceVersion.equals(checkpoint.sourceVersion());
            // "최신 재무 미확인"이 시간만으로 바뀌는 날이 지난 기업도 대상에 넣는다(재무는 그대로다, D-43).
            boolean dueByRecheck = checkpoint != null && checkpoint.nextCheckAt() != null
                    && !today.isBefore(checkpoint.nextCheckAt());
            if (dueByChange || dueByRecheck) {
                due.add(entry.getKey());
            }
        }

        boolean fullRecompute = due.size() > lastChangedByCompanyId.size() * FULL_RECOMPUTE_RATIO;
        int companiesWithWithdrawal = 0;
        for (Long companyId : due) {
            Instant attemptedAt = clock.instant();
            String sourceVersion = sourceVersion(lastChangedByCompanyId.get(companyId));
            try {
                CompanySignalService.ApplyResult result = transaction.execute(status -> {
                    FinancialSummary summary = summaryService.summarize(companyId);
                    FinancialSignalCalculator.CalculationResult calculation = FinancialSignalCalculator.calculate(summary, today);
                    String latestKey = summary.quarters().isEmpty() ? null : summary.quarters().get(0).key().stateBasisKey();
                    CompanySignalService.ApplyResult r = signalService.applyFinancial(companyId, calculation.drafts(),
                            FinancialRuleCatalog.RULE_VERSION, latestKey);
                    checkpoints.record(CHECKPOINT_SOURCE, String.valueOf(companyId), sourceVersion,
                            IngestCheckpoint.Result.SUCCESS, "반영 %d·철회 %d".formatted(r.applied(), r.withdrawn()), attemptedAt,
                            calculation.staleRecheckAt());
                    return r;
                });
                progress.processed++;
                progress.applied += result.applied();
                progress.withdrawn += result.withdrawn();
                if (result.withdrawn() > 0) {
                    companiesWithWithdrawal++;
                }
            } catch (RuntimeException e) {
                checkpoints.record(CHECKPOINT_SOURCE, String.valueOf(companyId), sourceVersion,
                        IngestCheckpoint.Result.ERROR, e.getClass().getSimpleName() + ": " + e.getMessage(), attemptedAt);
                progress.failed++;
                log.warn("재무 신호 판정 실패: 기업 {}", companyId, e);
            }
        }

        boolean withdrawalSpike = !fullRecompute && !due.isEmpty()
                && companiesWithWithdrawal > due.size() * WITHDRAWAL_SPIKE_RATIO;
        if (withdrawalSpike) {
            log.warn("재무 신호 철회 급증: 대상 {}개 기업 중 {}개에서 철회 발생. 원천 데이터 이상 여부를 확인하세요.",
                    due.size(), companiesWithWithdrawal);
        }

        String summary = "대상 %d, 처리 %d, 실패 %d, 반영 %d, 철회 %d, 철회 기업 %d%s"
                .formatted(due.size(), progress.processed, progress.failed, progress.applied, progress.withdrawn,
                        companiesWithWithdrawal, withdrawalSpike ? " [경고: 철회 급증]" : "");
        return new Result(progress.failed > 0 ? RunStatus.PARTIAL : RunStatus.SUCCEEDED, summary);
    }

    private static String sourceVersion(Instant financialLastChangedAt) {
        return FinancialRuleCatalog.RULE_VERSION + ":" + financialLastChangedAt;
    }

    private static final class Progress {
        int processed;
        int failed;
        int applied;
        int withdrawn;

        @Override
        public String toString() {
            return "처리 %d, 실패 %d".formatted(processed, failed);
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
