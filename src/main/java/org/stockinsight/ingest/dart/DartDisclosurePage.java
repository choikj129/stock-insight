package org.stockinsight.ingest.dart;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** 공시검색(list.json) 응답 한 페이지. 조회 결과가 없으면(013) 빈 페이지다. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DartDisclosurePage(
        @JsonProperty("status") String status,
        @JsonProperty("message") String message,
        @JsonProperty("page_no") Integer pageNo,
        @JsonProperty("total_count") Integer totalCount,
        @JsonProperty("total_page") Integer totalPage,
        @JsonProperty("list") List<DartDisclosure> list) {

    public DartDisclosurePage {
        list = list == null ? List.of() : List.copyOf(list);
    }

    static DartDisclosurePage empty(int pageNo) {
        return new DartDisclosurePage(DartStatus.NO_DATA.code(), null, pageNo, 0, 0, List.of());
    }

    public boolean hasNextPage() {
        return pageNo != null && totalPage != null && pageNo < totalPage;
    }
}
