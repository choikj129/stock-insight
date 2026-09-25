package org.stockinsight.ingest.company;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
import org.stockinsight.company.CompanyService.ListedCompany;
import org.stockinsight.company.Market;
import org.stockinsight.ingest.checkpoint.IngestCheckpoint;
import org.stockinsight.ingest.checkpoint.IngestCheckpointRepository;
import org.stockinsight.ingest.dart.DartApi;
import org.stockinsight.ingest.dart.DartApiException;
import org.stockinsight.ingest.dart.DartCompanyOverview;
import org.stockinsight.ingest.dart.DartCorpCode;

/**
 * 기업 목록 동기화 (docs/architecture.md §4.4).
 * <ol>
 *     <li>고유번호 파일에서 상장사(종목코드가 있는 기업)를 고른다.</li>
 *     <li>목록에서 사라진 기업을 상장폐지로 표시한다.</li>
 *     <li>체크포인트를 기준으로 기업개황을 받아야 하는 기업만 호출해 반영한다.
 *         호출 한도에 닿으면 멈추고 다음 실행이 이어서 처리한다.</li>
 * </ol>
 */
@Component
public class CompanySyncJob {

    public static final String JOB_NAME = "company-sync";
    public static final String CHECKPOINT_SOURCE = "DART_COMPANY";

    private static final Logger log = LoggerFactory.getLogger(CompanySyncJob.class);

    private final DartApi dart;
    private final CompanyService companyService;
    private final IngestCheckpointRepository checkpoints;
    private final PipelineRunRecorder runRecorder;
    private final CompanySyncProperties properties;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final AtomicBoolean running = new AtomicBoolean(false);

    CompanySyncJob(DartApi dart, CompanyService companyService, IngestCheckpointRepository checkpoints,
            PipelineRunRecorder runRecorder, CompanySyncProperties properties, TransactionTemplate transaction,
            Clock clock) {
        this.dart = dart;
        this.companyService = companyService;
        this.checkpoints = checkpoints;
        this.runRecorder = runRecorder;
        this.properties = properties;
        this.transaction = transaction;
        this.clock = clock;
    }

    public Result run() {
        if (!running.compareAndSet(false, true)) {
            log.info("기업 목록 동기화가 이미 실행 중이라 건너뜁니다");
            return Result.skipped();
        }
        long runId = runRecorder.start(JOB_NAME);
        Progress progress = new Progress();
        try {
            Result result = sync(progress);
            runRecorder.finish(runId, result.status(), progress.processed, progress.failed, result.summary());
            log.info("기업 목록 동기화 완료: {}", result.summary());
            return result;
        } catch (DartApiException e) {
            String message = "중단: " + e.getMessage() + " / " + progress;
            runRecorder.finish(runId, RunStatus.FAILED, progress.processed, progress.failed, message);
            log.error("기업 목록 동기화 {}", message);
            return Result.failed(message);
        } catch (RuntimeException e) {
            String message = "오류: " + e.getClass().getSimpleName() + ": " + e.getMessage() + " / " + progress;
            runRecorder.finish(runId, RunStatus.FAILED, progress.processed, progress.failed, message);
            log.error("기업 목록 동기화 실패", e);
            return Result.failed(message);
        } finally {
            running.set(false);
        }
    }

    private Result sync(Progress progress) {
        progress.calls++;
        List<DartCorpCode> listed = dart.fetchCorpCodes().stream().filter(DartCorpCode::isListed).toList();
        checkListedCount(listed.size());

        Set<String> listedCodes = listed.stream().map(DartCorpCode::corpCode).collect(Collectors.toSet());
        LocalDate today = LocalDate.now(clock.withZone(TimeConfig.SERVICE_ZONE));
        progress.delisted = companyService.markDelistedExcept(listedCodes, today);

        List<DartCorpCode> due = dueForOverview(listed, checkpoints.findAllBySource(CHECKPOINT_SOURCE));
        int budget = Math.max(0, properties.maxCallsPerRun() - progress.calls);
        for (DartCorpCode corp : due) {
            if (progress.calls >= properties.maxCallsPerRun()) {
                break;
            }
            progress.calls++;
            syncOne(corp, progress);
        }
        int remaining = Math.max(0, due.size() - budget);
        return new Result(remaining > 0 ? RunStatus.PARTIAL : RunStatus.SUCCEEDED,
                "상장사 %d, 대상 %d, 처리 %d, 실패 %d, 상장폐지 %d, 남은 대상 %d, 호출 %d"
                        .formatted(listed.size(), due.size(), progress.processed, progress.failed,
                                progress.delisted, remaining, progress.calls));
    }

    private void syncOne(DartCorpCode corp, Progress progress) {
        Instant attemptedAt = clock.instant();
        try {
            Optional<DartCompanyOverview> overview = dart.fetchCompany(corp.corpCode());
            transaction.executeWithoutResult(status -> {
                if (overview.isPresent()) {
                    companyService.upsertListed(toListedCompany(corp, overview.get()));
                    record(corp, IngestCheckpoint.Result.SUCCESS, null, attemptedAt);
                } else {
                    record(corp, IngestCheckpoint.Result.NO_DATA, "기업개황 데이터 없음", attemptedAt);
                }
            });
            progress.processed++;
        } catch (DartApiException e) {
            if (e.stopsRun()) {
                throw e;
            }
            record(corp, IngestCheckpoint.Result.ERROR, e.getMessage(), attemptedAt);
            progress.failed++;
        } catch (RuntimeException e) {
            record(corp, IngestCheckpoint.Result.ERROR, e.getClass().getSimpleName() + ": " + e.getMessage(), attemptedAt);
            progress.failed++;
            log.warn("기업개황 반영 실패: {}", corp.corpCode(), e);
        }
    }

    /**
     * 기업개황을 받아야 하는 기업. 처음 보는 기업 → 고유번호 파일이 바뀐 기업 → 직전 오류 → 오래된 기업 순.
     */
    List<DartCorpCode> dueForOverview(List<DartCorpCode> listed, Map<String, IngestCheckpoint> checkpointByCode) {
        Instant staleBefore = clock.instant().minus(properties.refreshAfter());
        return listed.stream()
                .map(corp -> new Candidate(corp, priority(corp, checkpointByCode.get(corp.corpCode()), staleBefore)))
                .filter(candidate -> candidate.priority > 0)
                .sorted(Comparator.comparingInt(Candidate::priority).reversed())
                .map(Candidate::corp)
                .toList();
    }

    private static int priority(DartCorpCode corp, IngestCheckpoint checkpoint, Instant staleBefore) {
        if (checkpoint == null) {
            return 4;
        }
        if (!Objects.equals(checkpoint.sourceVersion(), corp.modifyDate())) {
            return 3;
        }
        if (checkpoint.result() == IngestCheckpoint.Result.ERROR) {
            return 2;
        }
        if (checkpoint.lastAttemptAt().isBefore(staleBefore)) {
            return 1;
        }
        return 0;
    }

    private void checkListedCount(int listedCount) {
        if (listedCount < properties.minListedCount()) {
            throw new IllegalStateException("고유번호 파일의 상장사 수가 너무 적습니다: " + listedCount);
        }
        long known = companyService.countListed();
        if (known > 0 && listedCount < known * (1 - properties.maxDelistedRatio())) {
            throw new IllegalStateException(
                    "상장사 수가 급감했습니다 (기존 %d → %d). 잘못된 응답일 수 있어 상장폐지 처리를 하지 않습니다"
                            .formatted(known, listedCount));
        }
    }

    private void record(DartCorpCode corp, IngestCheckpoint.Result result, String message, Instant attemptedAt) {
        checkpoints.record(CHECKPOINT_SOURCE, corp.corpCode(), corp.modifyDate(), result, message, attemptedAt);
    }

    private static ListedCompany toListedCompany(DartCorpCode corp, DartCompanyOverview overview) {
        String ticker = firstNonBlank(overview.stockCode(), corp.stockCode());
        return new ListedCompany(
                corp.corpCode(),
                firstNonBlank(overview.stockName(), overview.corpName(), corp.corpName()),
                firstNonBlank(overview.corpName(), corp.corpName()),
                ticker,
                Market.fromDartCorpCls(overview.corpCls()),
                blankToNull(overview.industryCode()),
                parseMonth(overview.fiscalMonth()));
    }

    private static Integer parseMonth(String month) {
        String value = blankToNull(month);
        if (value == null) {
            return null;
        }
        try {
            int parsed = Integer.parseInt(value);
            return parsed >= 1 && parsed <= 12 ? parsed : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            String trimmed = blankToNull(value);
            if (trimmed != null) {
                return trimmed;
            }
        }
        return null;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private record Candidate(DartCorpCode corp, int priority) {
    }

    private static final class Progress {
        int calls;
        int processed;
        int failed;
        int delisted;

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
