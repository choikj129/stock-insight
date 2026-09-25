package org.stockinsight.analysis.llm;

import java.math.BigDecimal;

/**
 * LLM 호출 결과. outputJson은 모델이 출력한 원문(파싱·검증은 호출한 쪽이 한다).
 */
public record LlmResult(
        String outputJson,
        String model,
        long inputTokens,
        long outputTokens,
        long cacheReadInputTokens,
        BigDecimal costUsd) {
}
