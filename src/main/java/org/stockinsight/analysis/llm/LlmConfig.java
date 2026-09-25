package org.stockinsight.analysis.llm;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class LlmConfig {

    @Bean
    LlmClient llmClient(LlmProperties properties) {
        AnthropicClient client = AnthropicOkHttpClient.builder()
                .apiKey(properties.hasApiKey() ? properties.apiKey() : "missing")
                .timeout(properties.timeout())
                .maxRetries(properties.maxRetries())
                .build();
        return new AnthropicLlmClient(client, properties);
    }
}
