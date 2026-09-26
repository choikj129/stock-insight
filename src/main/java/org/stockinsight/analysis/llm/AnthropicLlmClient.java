package org.stockinsight.analysis.llm;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.JsonValue;
import com.anthropic.errors.AnthropicException;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.JsonOutputFormat;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.TextBlock;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.ThinkingConfigDisabled;
import com.anthropic.models.messages.Usage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Anthropic Java SDK로 {@link LlmClient}를 구현한다. 시스템 프롬프트는 프롬프트 캐싱을 걸고, 출력은
 * jsonSchema로 만든 구조화 출력(JsonOutputFormat)으로 강제한다. 인증키·프롬프트 전문은 로그에 남기지 않는다
 * (문서 보안 요구사항).
 */
public class AnthropicLlmClient implements LlmClient {

    private static final Set<StopReason> UNACCEPTABLE_STOP_REASONS = Set.of(StopReason.MAX_TOKENS, StopReason.REFUSAL);
    private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000);

    private final AnthropicClient client;
    private final LlmProperties properties;
    private final ObjectMapper schemaMapper = new ObjectMapper();

    public AnthropicLlmClient(AnthropicClient client, LlmProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    @Override
    public LlmResult generate(String systemPrompt, String userInput, String jsonSchema) {
        if (!properties.hasApiKey()) {
            throw new LlmException("Anthropic 인증키(ANTHROPIC_API_KEY)가 설정되지 않았습니다");
        }

        OutputConfig outputConfig = OutputConfig.builder()
                .format(JsonOutputFormat.builder()
                        .type(JsonValue.from("json_schema"))
                        .schema(parseSchema(jsonSchema))
                        .build())
                .build();

        MessageCreateParams params = MessageCreateParams.builder()
                .model(properties.model())
                .maxTokens(properties.maxTokens())
                // 이 분석은 코드가 판정을 끝낸 사실·신호를 문장으로 옮기기만 한다(D-06). 확장 사고는 이 역할에 필요
                // 없고, 켜 두면(기본값) max_tokens를 사고 토큰이 먼저 소비해 구조화 출력이 잘린다(2026-09-26 실측:
                // 사고 토큰 약 1,000+개, 응답 텍스트는 400개 안팎). Sonnet 5는 disabled를 그대로 받아들인다.
                .thinking(ThinkingConfigDisabled.builder().build())
                .systemOfTextBlockParams(List.of(TextBlockParam.builder()
                        .text(systemPrompt)
                        .cacheControl(CacheControlEphemeral.builder().build())
                        .build()))
                .addUserMessage(userInput)
                .outputConfig(outputConfig)
                .build();

        Message response;
        try {
            response = client.messages().create(params);
        } catch (AnthropicException e) {
            throw new LlmException("Anthropic 호출 실패: " + e.getClass().getSimpleName(), e);
        }

        Usage usage = response.usage();
        long inputTokens = usage.inputTokens();
        long outputTokens = usage.outputTokens();
        long cacheReadTokens = usage.cacheReadInputTokens().orElse(0L);
        BigDecimal costUsd = estimateCost(inputTokens, outputTokens, cacheReadTokens);

        Optional<StopReason> stopReason = response.stopReason();
        if (stopReason.isPresent() && UNACCEPTABLE_STOP_REASONS.contains(stopReason.get())) {
            throw new LlmOutputRejectedException("모델 출력이 " + stopReason.get() + "(으)로 끝났습니다",
                    new LlmResult(null, properties.model(), inputTokens, outputTokens, cacheReadTokens, costUsd));
        }

        String outputJson = response.content().stream()
                .flatMap(block -> block.text().stream())
                .map(TextBlock::text)
                .findFirst()
                .orElseThrow(() -> new LlmOutputRejectedException("모델 응답에 텍스트 블록이 없습니다",
                        new LlmResult(null, properties.model(), inputTokens, outputTokens, cacheReadTokens, costUsd)));

        return new LlmResult(outputJson, properties.model(), inputTokens, outputTokens, cacheReadTokens, costUsd);
    }

    private JsonOutputFormat.Schema parseSchema(String jsonSchema) {
        JsonNode root;
        try {
            root = schemaMapper.readTree(jsonSchema);
        } catch (Exception e) {
            throw new LlmException("출력 스키마를 읽지 못했습니다: " + e.getClass().getSimpleName());
        }
        JsonOutputFormat.Schema.Builder builder = JsonOutputFormat.Schema.builder();
        root.fields().forEachRemaining(entry -> builder.putAdditionalProperty(entry.getKey(), JsonValue.fromJsonNode(entry.getValue())));
        return builder.build();
    }

    private BigDecimal estimateCost(long inputTokens, long outputTokens, long cacheReadTokens) {
        BigDecimal inputCost = perMillion(inputTokens, properties.inputCostPerMillion());
        BigDecimal cacheCost = perMillion(cacheReadTokens, properties.cacheReadCostPerMillion());
        BigDecimal outputCost = perMillion(outputTokens, properties.outputCostPerMillion());
        return inputCost.add(cacheCost).add(outputCost);
    }

    private static BigDecimal perMillion(long tokens, BigDecimal pricePerMillion) {
        return BigDecimal.valueOf(tokens).multiply(pricePerMillion).divide(MILLION, 6, RoundingMode.HALF_UP);
    }
}
