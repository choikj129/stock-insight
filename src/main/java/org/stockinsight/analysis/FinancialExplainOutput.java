package org.stockinsight.analysis;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 재무 쉬운 설명의 AI 출력 계약(ai-analysis.md §4.4.4). {@code sections}에 없는 섹션은 null이다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FinancialExplainOutput(
        @JsonProperty("overview") String overview,
        @JsonProperty("sales_profit") String salesProfit,
        @JsonProperty("structure") String structure,
        @JsonProperty("history") String history) {
}
