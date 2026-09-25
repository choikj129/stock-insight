package org.stockinsight.analysis;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.stockinsight.common.config.TimeConfig;
import org.stockinsight.common.pipeline.PipelineRunRecorder;
import org.stockinsight.common.pipeline.PipelineRunRecorder.RunStatus;
import org.stockinsight.company.CompanyService;
import org.stockinsight.signal.FinancialRuleCatalog;
import org.stockinsight.analysis.llm.LlmClient;
import org.stockinsight.analysis.llm.LlmException;
import org.stockinsight.analysis.llm.LlmOutputRejectedException;
import org.stockinsight.analysis.llm.LlmResult;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * 재무 쉬운 설명 동기화(ai-analysis.md §6.1). 재무 신호 계산 뒤 실행한다. 대상마다 지문을 계산해 건너뜀·재시도를
 * 판단하고, 필요하면 AI를 호출해 검증한 뒤 초안으로 저장한다({@code app.analysis.financial-explain.publish}가
 * true일 때만 게시본으로 승격한다. D-39, §4.4.8).
 */
@Component
public class FinancialExplainJob {

    public static final String JOB_NAME = "analysis-financial-explain";

    private static final Logger log = LoggerFactory.getLogger(FinancialExplainJob.class);
    private static final int MAX_ATTEMPTS = 2;

    private final CompanyService companyService;
    private final FinancialExplainInputBuilder inputBuilder;
    private final FinancialExplainValidator validator = new FinancialExplainValidator();
    private final AnalysisService analysisService;
    private final FinancialExplainProperties properties;
    private final LlmClient llmClient;
    private final PipelineRunRecorder runRecorder;
    private final Clock clock;
    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private final AtomicBoolean running = new AtomicBoolean(false);

    FinancialExplainJob(CompanyService companyService, FinancialExplainInputBuilder inputBuilder,
            AnalysisService analysisService, FinancialExplainProperties properties,
            LlmClient llmClient, PipelineRunRecorder runRecorder, Clock clock) {
        this.companyService = companyService;
        this.inputBuilder = inputBuilder;
        this.analysisService = analysisService;
        this.properties = properties;
        this.llmClient = llmClient;
        this.runRecorder = runRecorder;
        this.clock = clock;
    }

    public Result run() {
        if (!running.compareAndSet(false, true)) {
            log.info("재무 쉬운 설명 동기화가 이미 실행 중이라 건너뜁니다");
            return Result.skipped();
        }
        long runId = runRecorder.start(JOB_NAME);
        Progress progress = new Progress();
        try {
            Result result = sync(progress);
            runRecorder.finish(runId, result.status(), progress.processed(), progress.failed + progress.rejected,
                    result.summary());
            log.info("재무 쉬운 설명 동기화 완료: {}", result.summary());
            return result;
        } catch (RuntimeException e) {
            String message = "오류: " + e.getClass().getSimpleName() + ": " + e.getMessage() + " / " + progress;
            runRecorder.finish(runId, RunStatus.FAILED, progress.processed(), progress.failed + progress.rejected, message);
            log.error("재무 쉬운 설명 동기화 실패", e);
            return Result.failed(message);
        } finally {
            running.set(false);
        }
    }

    private Result sync(Progress progress) {
        List<Long> targets = targets();
        if (targets.isEmpty()) {
            return new Result(RunStatus.SUCCEEDED, "대상 없음");
        }

        Instant now = clock.instant();
        ZonedDateTime nowSeoul = now.atZone(TimeConfig.SERVICE_ZONE);
        Instant dayStart = nowSeoul.toLocalDate().atStartOfDay(TimeConfig.SERVICE_ZONE).toInstant();
        Instant monthStart = LocalDate.from(nowSeoul).withDayOfMonth(1).atStartOfDay(TimeConfig.SERVICE_ZONE).toInstant();

        boolean budgetExceeded = false;
        for (Long companyId : targets) {
            if (isBudgetExceeded(dayStart, monthStart)) {
                budgetExceeded = true;
                break;
            }
            processTarget(companyId, progress, now);
        }

        String summary = "대상 %d, 초안 %d, 게시 %d, 거절 %d, 실패 %d, 건너뜀(최신) %d, 건너뜀(대기) %d, 건너뜀(중단) %d, 데이터없음 %d%s"
                .formatted(targets.size(), progress.drafted, progress.published, progress.rejected, progress.failed,
                        progress.skippedUpToDate, progress.skippedBackoff, progress.skippedMaxFailures,
                        progress.skippedNoData, budgetExceeded ? " [예산 소진으로 중단]" : "");
        RunStatus status = budgetExceeded || progress.failed > 0 || progress.rejected > 0 ? RunStatus.PARTIAL : RunStatus.SUCCEEDED;
        return new Result(status, summary);
    }

    private List<Long> targets() {
        Set<Long> ids = new LinkedHashSet<>(companyService.aiCoveredCompanyIds());
        ids.addAll(properties.goldenSetCompanyIds());
        return List.copyOf(ids);
    }

    private boolean isBudgetExceeded(Instant dayStart, Instant monthStart) {
        if (properties.dailyBudgetUsd() != null
                && analysisService.spentSince(dayStart).compareTo(properties.dailyBudgetUsd()) >= 0) {
            return true;
        }
        return properties.monthlyBudgetUsd() != null
                && analysisService.spentSince(monthStart).compareTo(properties.monthlyBudgetUsd()) >= 0;
    }

    private void processTarget(long companyId, Progress progress, Instant now) {
        FinancialExplainInputBuilder.BuildResult built;
        try {
            var maybe = inputBuilder.build(companyId);
            if (maybe.isEmpty()) {
                progress.skippedNoData++;
                return;
            }
            built = maybe.get();
        } catch (RuntimeException e) {
            log.warn("재무 쉬운 설명 입력 구성 실패: 기업 {}", companyId, e);
            progress.failed++;
            return;
        }

        AnalysisService.RetryDecision decision = analysisService.decide(TargetType.COMPANY, String.valueOf(companyId),
                AnalysisKind.FINANCIAL_EXPLAIN, built.fingerprint(), FinancialExplainPrompt.PROMPT_VERSION, now);
        switch (decision) {
            case SKIP_UP_TO_DATE -> progress.skippedUpToDate++;
            case SKIP_BACKOFF -> progress.skippedBackoff++;
            case SKIP_MAX_FAILURES -> progress.skippedMaxFailures++;
            case PROCEED -> generate(companyId, built, progress, now);
        }
    }

    private void generate(long companyId, FinancialExplainInputBuilder.BuildResult built, Progress progress, Instant now) {
        String inputJson = jsonMapper.writeValueAsString(built.input());

        Attempt outcome = null;
        List<String> priorFailedRules = null;
        String model = null;
        long inputTokens = 0;
        long outputTokens = 0;
        long cacheReadTokens = 0;
        BigDecimal costUsd = BigDecimal.ZERO;
        int attemptCount = 0;

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            attemptCount = attempt;
            LlmResult result;
            try {
                result = llmClient.generate(FinancialExplainPrompt.SYSTEM_PROMPT,
                        FinancialExplainPrompt.userMessage(inputJson, priorFailedRules), FinancialExplainPrompt.SCHEMA_JSON);
            } catch (LlmOutputRejectedException e) {
                if (e.partialUsage() != null) {
                    model = e.partialUsage().model();
                    inputTokens += e.partialUsage().inputTokens();
                    outputTokens += e.partialUsage().outputTokens();
                    cacheReadTokens += e.partialUsage().cacheReadInputTokens();
                    costUsd = costUsd.add(e.partialUsage().costUsd());
                }
                priorFailedRules = List.of("LLM_OUTPUT_REJECTED");
                outcome = Attempt.rejected(priorFailedRules);
                continue;
            } catch (LlmException e) {
                log.warn("재무 쉬운 설명 AI 호출 실패: 기업 {}: {}", companyId, e.getClass().getSimpleName());
                outcome = Attempt.callFailed(e.getClass().getSimpleName());
                break;
            }
            model = result.model();
            inputTokens += result.inputTokens();
            outputTokens += result.outputTokens();
            cacheReadTokens += result.cacheReadInputTokens();
            costUsd = costUsd.add(result.costUsd());

            FinancialExplainOutput output;
            try {
                output = jsonMapper.readValue(result.outputJson(), FinancialExplainOutput.class);
            } catch (JacksonException e) {
                priorFailedRules = List.of("INVALID_JSON");
                outcome = Attempt.rejected(priorFailedRules);
                continue;
            }
            FinancialExplainValidator.ValidationResult vr = validator.validate(output, built.input());
            if (vr.valid()) {
                outcome = Attempt.success(output);
                break;
            }
            priorFailedRules = vr.failedRules();
            outcome = Attempt.rejected(priorFailedRules);
        }

        AnalysisStatus status = switch (outcome.kind()) {
            case SUCCESS -> AnalysisStatus.DRAFT;
            case REJECTED -> AnalysisStatus.REJECTED;
            case CALL_FAILED -> AnalysisStatus.FAILED;
        };

        NewAnalysis draft = new NewAnalysis(
                TargetType.COMPANY, String.valueOf(companyId), AnalysisKind.FINANCIAL_EXPLAIN, built.fingerprint(), status,
                outcome.output(), built.input(), built.snapshot(),
                FinancialExplainPrompt.SCHEMA_VERSION, FinancialExplainPrompt.PROMPT_VERSION, model,
                FinancialExplainInputBuilder.INPUT_BUILDER_VERSION, FinancialRuleCatalog.RULE_VERSION,
                toIntOrNull(inputTokens), toIntOrNull(outputTokens), toIntOrNull(cacheReadTokens), costUsd,
                outcome.failedRules(), attemptCount);
        long id = analysisService.save(draft, now);

        switch (outcome.kind()) {
            case SUCCESS -> {
                progress.drafted++;
                if (properties.publish()) {
                    analysisService.publish(id, TargetType.COMPANY, String.valueOf(companyId), AnalysisKind.FINANCIAL_EXPLAIN, now);
                    progress.published++;
                }
            }
            case REJECTED -> progress.rejected++;
            case CALL_FAILED -> progress.failed++;
        }
    }

    private static Integer toIntOrNull(long value) {
        return value == 0 ? null : (int) value;
    }

    private static final class Progress {
        int drafted;
        int published;
        int rejected;
        int failed;
        int skippedUpToDate;
        int skippedBackoff;
        int skippedMaxFailures;
        int skippedNoData;

        int processed() {
            return drafted + rejected + failed;
        }

        @Override
        public String toString() {
            return "초안 %d, 거절 %d, 실패 %d".formatted(drafted, rejected, failed);
        }
    }

    private enum AttemptKind {
        SUCCESS, REJECTED, CALL_FAILED
    }

    private record Attempt(AttemptKind kind, FinancialExplainOutput output, List<String> failedRules) {
        static Attempt success(FinancialExplainOutput output) {
            return new Attempt(AttemptKind.SUCCESS, output, null);
        }

        static Attempt rejected(List<String> failedRules) {
            return new Attempt(AttemptKind.REJECTED, null, failedRules);
        }

        static Attempt callFailed(String reason) {
            return new Attempt(AttemptKind.CALL_FAILED, null, List.of(reason));
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
