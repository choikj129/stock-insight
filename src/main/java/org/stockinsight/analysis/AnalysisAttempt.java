package org.stockinsight.analysis;

import java.util.List;

/** AI 호출 한 번의 기록(analysis.attempts, D-53). 게시·렌더링은 읽지 않는다. rawOutput은 JSON 파싱에 실패했을 때만 채운다. */
public record AnalysisAttempt(
        int attempt,
        Outcome outcome,
        List<String> failedRules,
        FinancialExplainOutput output,
        String rawOutput,
        String detail,
        String validatorVersion) {

    public enum Outcome {
        SUCCESS, REJECTED, INVALID_JSON, LLM_OUTPUT_REJECTED, CALL_FAILED
    }
}
