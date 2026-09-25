package org.stockinsight.ingest.dart;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** 기업개황(company.json) 응답. 수집에 쓰는 필드만 받는다. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DartCompanyOverview(
        @JsonProperty("status") String status,
        @JsonProperty("message") String message,
        @JsonProperty("corp_code") String corpCode,
        @JsonProperty("corp_name") String corpName,
        @JsonProperty("stock_name") String stockName,
        @JsonProperty("stock_code") String stockCode,
        @JsonProperty("corp_cls") String corpCls,
        @JsonProperty("induty_code") String industryCode,
        @JsonProperty("acc_mt") String fiscalMonth) {
}
