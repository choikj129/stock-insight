package org.stockinsight.ingest.dart;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 공시검색(list.json)의 공시 한 건. 값은 원본 그대로 둔다.
 * <ul>
 *     <li>{@code corpCls}는 제출 당시가 아니라 조회 시점의 법인구분이다 (상장폐지 기업은 과거 공시도 E로 나온다).</li>
 *     <li>{@code reportName}에는 끝 공백과 연속 공백이 섞여 있고, 정정 제출이면 앞에 [기재정정] 같은 표시가 붙는다.</li>
 *     <li>{@code remark}는 한 글자 표시의 조합이다 (예: "코정"). 정: 이후 정정 제출 있음, 철: 철회, 연: 연결 포함,
 *         유·코·채·넥·공: 소관 기관.</li>
 * </ul>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DartDisclosure(
        @JsonProperty("corp_code") String corpCode,
        @JsonProperty("corp_name") String corpName,
        @JsonProperty("stock_code") String stockCode,
        @JsonProperty("corp_cls") String corpCls,
        @JsonProperty("report_nm") String reportName,
        @JsonProperty("rcept_no") String receiptNo,
        @JsonProperty("flr_nm") String filerName,
        @JsonProperty("rcept_dt") String receivedDate,
        @JsonProperty("rm") String remark) {
}
