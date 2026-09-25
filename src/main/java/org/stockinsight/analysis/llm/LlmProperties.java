package org.stockinsight.analysis.llm;

import java.math.BigDecimal;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Anthropic LLM 설정. 인증키는 ANTHROPIC_API_KEY로만 주입한다. 저장소 밖 비밀값 파일이나 환경 변수에서 온다
 * (docs/architecture.md §8, D-30). 단가는 100만 토큰당 USD(ai-analysis.md §9, 2026-06 기준 가격).
 */
@ConfigurationProperties("app.llm")
public record LlmProperties(
        String apiKey,
        String model,
        long maxTokens,
        Duration timeout,
        int maxRetries,
        BigDecimal inputCostPerMillion,
        BigDecimal cacheReadCostPerMillion,
        BigDecimal outputCostPerMillion) {

    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }

    @Override
    public String toString() {
        return "LlmProperties[apiKey=" + (hasApiKey() ? "****" : "<none>") + ", model=" + model
                + ", maxTokens=" + maxTokens + ", timeout=" + timeout + "]";
    }
}
