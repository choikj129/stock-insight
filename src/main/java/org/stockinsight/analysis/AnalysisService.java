package org.stockinsight.analysis;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 분석 결과의 저장 규칙을 담당한다(ai-analysis.md §6). 동기화 판단(§6.1)과 상태 전이만 하고, 생성·검증은 하지 않는다. */
@Service
@Transactional
public class AnalysisService {

    /** 같은 지문으로 실패한 뒤 다시 시도하기 전 대기 시간. ai-analysis.md §6.1의 5번 규칙. */
    static final Duration RETRY_BACKOFF = Duration.ofHours(24);
    /** 같은 지문으로 이 횟수만큼 실패하면 프롬프트 버전이 바뀌기 전까지 자동 시도를 멈춘다. */
    static final int MAX_FAILURES = 3;

    private final AnalysisRepository repository;

    AnalysisService(AnalysisRepository repository) {
        this.repository = repository;
    }

    public long save(NewAnalysis draft, Instant now) {
        return repository.insert(draft, now);
    }

    public void publish(long id, TargetType targetType, String targetKey, AnalysisKind analysisKind, Instant now) {
        repository.publish(id, targetType, targetKey, analysisKind, now);
    }

    @Transactional(readOnly = true)
    public Optional<Analysis> findCurrent(TargetType targetType, String targetKey, AnalysisKind analysisKind) {
        return repository.findCurrent(targetType, targetKey, analysisKind);
    }

    @Transactional(readOnly = true)
    public List<Analysis> findByTarget(TargetType targetType, String targetKey, AnalysisKind analysisKind) {
        return repository.findByTarget(targetType, targetKey, analysisKind);
    }

    /** 이 대상을 다시 만들지 판단한다(ai-analysis.md §6.1의 3~5번 규칙). 최소 재생성 간격은 재무 쉬운 설명에 없다(§4.4.6). */
    @Transactional(readOnly = true)
    public RetryDecision decide(TargetType targetType, String targetKey, AnalysisKind analysisKind, String fingerprint,
            String currentPromptVersion, Instant now) {
        Optional<Analysis> latest = repository.findLatestByFingerprint(targetType, targetKey, analysisKind, fingerprint);
        if (latest.isEmpty()) {
            return RetryDecision.PROCEED;
        }
        Analysis last = latest.get();
        if (last.status() == AnalysisStatus.PUBLISHED || last.status() == AnalysisStatus.HIDDEN
                || last.status() == AnalysisStatus.DRAFT) {
            // 게시·숨김은 같은 지문이면 이미 최신이다. 초안도 같은 입력으로 또 만들 필요가 없다(불필요한 AI 호출 방지).
            return RetryDecision.SKIP_UP_TO_DATE;
        }
        // REJECTED 또는 FAILED
        if (Duration.between(last.createdAt(), now).compareTo(RETRY_BACKOFF) < 0) {
            return RetryDecision.SKIP_BACKOFF;
        }
        int failures = repository.countFailuresByFingerprint(targetType, targetKey, analysisKind, fingerprint);
        if (failures >= MAX_FAILURES && last.promptVersion().equals(currentPromptVersion)) {
            return RetryDecision.SKIP_MAX_FAILURES;
        }
        return RetryDecision.PROCEED;
    }

    /** 이 시각 이후 모든 분석 종류를 합친 비용(§6.2, 예산은 분석 종류 공통). */
    @Transactional(readOnly = true)
    public BigDecimal spentSince(Instant since) {
        return repository.sumCostSince(since);
    }

    public enum RetryDecision {
        PROCEED, SKIP_UP_TO_DATE, SKIP_BACKOFF, SKIP_MAX_FAILURES
    }
}
