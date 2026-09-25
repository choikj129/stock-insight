package org.stockinsight.ingest.dart;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** 다중회사 주요계정(fnlttMultiAcnt.json) 응답. 데이터가 없으면(013) 빈 목록이다. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DartKeyAccountResponse(
        @JsonProperty("status") String status,
        @JsonProperty("message") String message,
        @JsonProperty("list") List<DartKeyAccount> list) {

    public DartKeyAccountResponse {
        list = list == null ? List.of() : List.copyOf(list);
    }
}
