package org.stockinsight.ingest.disclosure;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
import org.stockinsight.company.CompanyService;
import org.stockinsight.disclosure.DisclosureService;
import org.stockinsight.disclosure.DisclosureService.NewDisclosure;
import org.stockinsight.disclosure.DisclosureService.SaveResult;
import org.stockinsight.disclosure.DisclosureType;
import org.stockinsight.ingest.checkpoint.IngestCheckpoint;
import org.stockinsight.ingest.checkpoint.IngestCheckpointRepository;
import org.stockinsight.ingest.dart.DartApi;
import org.stockinsight.ingest.dart.DartApiException;
import org.stockinsight.ingest.dart.DartDisclosure;
import org.stockinsight.ingest.dart.DartDisclosurePage;

/**
 * 공시 목록 수집 (docs/architecture.md §4.4).
 * <ol>
 *     <li>기업을 지정하지 않고 접수일 하루 × 공시 유형(정기·주요사항·거래소) 단위로 모든 페이지를 읽는다.</li>
 *     <li>ACTIVE 기업의 공시만 공시번호 기준으로 저장하고, 정정 공시를 원 공시에 연결한다.</li>
 *     <li>체크포인트는 날짜·유형 단위다. 그 날짜가 끝난 뒤에 읽어야 확정되고, 확정된 날짜는 다시 읽지 않는다.
 *         호출 한도에 닿으면 멈추고 다음 실행이 이어서 처리한다.</li>
 * </ol>
 */
@Component
public class DisclosureSyncJob {

    public static final String JOB_NAME = "disclosure-sync";
    public static final String CHECKPOINT_SOURCE = "DART_DISCLOSURE";

    private static final Logger log = LoggerFactory.getLogger(DisclosureSyncJob.class);

    private final DartApi dart;
    private final CompanyService companyService;
    private final DisclosureService disclosureService;
    private final IngestCheckpointRepository checkpoints;
    private final PipelineRunRecorder runRecorder;
    private final DisclosureSyncProperties properties;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final AtomicBoolean running = new AtomicBoolean(false);

    DisclosureSyncJob(DartApi dart, CompanyService companyService, DisclosureService disclosureService,
            IngestCheckpointRepository checkpoints, PipelineRunRecorder runRecorder,
            DisclosureSyncProperties properties, TransactionTemplate transaction, Clock clock) {
        this.dart = dart;
        this.companyService = companyService;
        this.disclosureService = disclosureService;
        this.checkpoints = checkpoints;
        this.runRecorder = runRecorder;
        this.properties = properties;
        this.transaction = transaction;
        this.clock = clock;
    }

    /** 전체 실행: 당일 → 확정되지 않았거나 오류가 난 날짜 → 초기 적재 남은 날짜 순. */
    public Result run() {
        return execute(false);
    }

    /** 장중 실행: 당일분만 읽는다. */
    public Result runToday() {
        return execute(true);
    }

    private Result execute(boolean todayOnly) {
        if (!running.compareAndSet(false, true)) {
            log.info("공시 목록 수집이 이미 실행 중이라 건너뜁니다");
            return Result.skipped();
        }
        long runId = runRecorder.start(JOB_NAME);
        Progress progress = new Progress();
        try {
            Result result = sync(todayOnly, progress);
            runRecorder.finish(runId, result.status(), progress.processed, progress.failed, result.summary());
            log.info("공시 목록 수집 완료: {}", result.summary());
            return result;
        } catch (DartApiException e) {
            String message = "중단: " + e.getMessage() + " / " + progress;
            runRecorder.finish(runId, RunStatus.FAILED, progress.processed, progress.failed, message);
            log.error("공시 목록 수집 {}", message);
            return Result.failed(message);
        } catch (RuntimeException e) {
            String message = "오류: " + e.getClass().getSimpleName() + ": " + e.getMessage() + " / " + progress;
            runRecorder.finish(runId, RunStatus.FAILED, progress.processed, progress.failed, message);
            log.error("공시 목록 수집 실패", e);
            return Result.failed(message);
        } finally {
            running.set(false);
        }
    }

    private Result sync(boolean todayOnly, Progress progress) {
        Map<String, Long> activeCompanyIds = companyService.activeCompanyIdsByDartCorpCode();
        if (activeCompanyIds.isEmpty()) {
            // 이대로 진행하면 아무것도 저장하지 않은 날짜가 확정으로 기록된다.
            throw new IllegalStateException("ACTIVE 기업이 없습니다. 기업 목록 동기화(company-sync)를 먼저 실행해야 합니다");
        }
        LocalDate today = LocalDate.now(clock.withZone(TimeConfig.SERVICE_ZONE));
        List<Unit> due = dueUnits(today, todayOnly, checkpoints.findAllBySource(CHECKPOINT_SOURCE));

        int attempted = 0;
        for (Unit unit : due) {
            if (progress.calls >= properties.maxCallsPerRun() || !syncUnit(unit, activeCompanyIds, progress)) {
                break;
            }
            attempted++;
        }
        int remaining = due.size() - attempted;
        return new Result(remaining > 0 ? RunStatus.PARTIAL : RunStatus.SUCCEEDED,
                "대상 %d(당일 %d, 미확정·오류 %d, 초기 적재 %d), 처리 %d, 실패 %d, 공시 신규 %d·갱신 %d·정정 연결 %d, ACTIVE 아님 %d, 남은 대상 %d, 호출 %d"
                        .formatted(due.size(), count(due, Kind.TODAY), count(due, Kind.CATCH_UP),
                                count(due, Kind.BACKFILL), progress.processed, progress.failed, progress.inserted,
                                progress.updated, progress.linked, progress.notActive, remaining, progress.calls));
    }

    /** 한 날짜·유형의 모든 페이지를 읽어 저장한다. 호출 한도에 닿아 끝까지 읽지 못하면 기록 없이 false. */
    private boolean syncUnit(Unit unit, Map<String, Long> activeCompanyIds, Progress progress) {
        Instant attemptedAt = clock.instant();
        try {
            // 읽는 도중 새 공시가 들어오면 페이지가 밀려 같은 공시가 두 번 나올 수 있다.
            Map<String, DartDisclosure> received = new LinkedHashMap<>();
            DartDisclosurePage page;
            int pageNo = 1;
            do {
                if (progress.calls >= properties.maxCallsPerRun()) {
                    return false;
                }
                progress.calls++;
                page = dart.fetchDisclosures(unit.date(), unit.type().dartCode(), pageNo++);
                page.list().forEach(item -> received.putIfAbsent(item.receiptNo(), item));
            } while (page.hasNextPage());

            List<NewDisclosure> targets = received.values().stream()
                    .filter(item -> activeCompanyIds.containsKey(item.corpCode()))
                    .map(item -> toNewDisclosure(item, unit.type(), activeCompanyIds.get(item.corpCode())))
                    .toList();
            SaveResult saved = transaction.execute(status -> {
                SaveResult result = disclosureService.saveAll(targets);
                record(unit, received.isEmpty() ? IngestCheckpoint.Result.NO_DATA : IngestCheckpoint.Result.SUCCESS,
                        "공시 %d건 중 ACTIVE 기업 %d건".formatted(received.size(), targets.size()), attemptedAt);
                return result;
            });
            progress.inserted += saved.inserted();
            progress.updated += saved.updated();
            progress.linked += saved.linked();
            progress.notActive += received.size() - targets.size();
            progress.processed++;
        } catch (DartApiException e) {
            if (e.stopsRun()) {
                throw e;
            }
            record(unit, IngestCheckpoint.Result.ERROR, e.getMessage(), attemptedAt);
            progress.failed++;
        } catch (RuntimeException e) {
            record(unit, IngestCheckpoint.Result.ERROR, e.getClass().getSimpleName() + ": " + e.getMessage(), attemptedAt);
            progress.failed++;
            log.warn("공시 목록 반영 실패: {}", key(unit.date(), unit.type()), e);
        }
        return true;
    }

    /** 읽을 날짜·유형. 당일 → 미확정·오류 → 초기 적재 순이고, 각각 최근 날짜부터다. */
    List<Unit> dueUnits(LocalDate today, boolean todayOnly, Map<String, IngestCheckpoint> checkpointByKey) {
        List<Unit> units = new ArrayList<>();
        for (DisclosureType type : DisclosureType.values()) {
            units.add(new Unit(today, type, Kind.TODAY));
        }
        if (todayOnly) {
            return units;
        }
        List<Unit> catchUp = new ArrayList<>();
        List<Unit> backfill = new ArrayList<>();
        LocalDate from = today.minus(properties.initialLoadPeriod());
        for (LocalDate date = today.minusDays(1); !date.isBefore(from); date = date.minusDays(1)) {
            for (DisclosureType type : DisclosureType.values()) {
                IngestCheckpoint checkpoint = checkpointByKey.get(key(date, type));
                if (checkpoint == null) {
                    backfill.add(new Unit(date, type, Kind.BACKFILL));
                } else if (!isSettled(checkpoint, date)) {
                    catchUp.add(new Unit(date, type, Kind.CATCH_UP));
                }
            }
        }
        units.addAll(catchUp);
        units.addAll(backfill);
        return units;
    }

    /** 그 날짜가 끝난 뒤(KST)에 오류 없이 읽었으면 확정이다. 당일에 읽은 결과는 이후 공시가 더 들어올 수 있다. */
    private static boolean isSettled(IngestCheckpoint checkpoint, LocalDate date) {
        return checkpoint.result() != IngestCheckpoint.Result.ERROR
                && checkpoint.lastAttemptAt().atZone(TimeConfig.SERVICE_ZONE).toLocalDate().isAfter(date);
    }

    /** 체크포인트 대상 키. 예: 2026-08-14:A */
    static String key(LocalDate date, DisclosureType type) {
        return date + ":" + type.dartCode();
    }

    private void record(Unit unit, IngestCheckpoint.Result result, String message, Instant attemptedAt) {
        checkpoints.record(CHECKPOINT_SOURCE, key(unit.date(), unit.type()), null, result, message, attemptedAt);
    }

    private static NewDisclosure toNewDisclosure(DartDisclosure item, DisclosureType type, long companyId) {
        return new NewDisclosure(
                item.receiptNo(),
                companyId,
                type,
                item.reportName(),
                LocalDate.parse(item.receivedDate(), DateTimeFormatter.BASIC_ISO_DATE),
                item.filerName(),
                item.remark());
    }

    private static long count(List<Unit> units, Kind kind) {
        return units.stream().filter(unit -> unit.kind() == kind).count();
    }

    /** 수집 단위의 성격. 초기 적재(처음 읽는 날짜)와 증분(당일, 확정 전 날짜)을 실행 기록에서 구분하기 위함이다. */
    enum Kind {
        /** 오늘. 매 실행마다 다시 읽는다. */
        TODAY,
        /** 읽은 적은 있지만 확정되지 않았거나 오류가 난 날짜. */
        CATCH_UP,
        /** 아직 읽은 적이 없는 날짜 (초기 적재, 적재 기간 확장). */
        BACKFILL
    }

    record Unit(LocalDate date, DisclosureType type, Kind kind) {
    }

    private static final class Progress {
        int calls;
        int processed;
        int failed;
        int inserted;
        int updated;
        int linked;
        int notActive;

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
