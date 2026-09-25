package org.stockinsight.ingest.dart;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 다중회사 주요계정(fnlttMultiAcnt.json)의 계정 한 줄. 값은 원본 그대로 둔다(해석은 financial 패키지가 한다).
 * <ul>
 *     <li>{@code thstrmDt}는 3개월 단독값 행에도 회계연도 누적 기간을 보여 준다 (예: "2025.01.01 ~ 2025.06.30").</li>
 *     <li>금액은 쉼표가 든 정수 문자열이고, 음수는 {@code -} 접두, 값이 없으면 {@code "-"}이다.</li>
 *     <li>{@code rceptNo}는 정정되었으면 최신 정정 공시번호다. 이전 값은 API가 주지 않는다.</li>
 * </ul>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DartKeyAccount(
        @JsonProperty("rcept_no") String receiptNo,
        @JsonProperty("reprt_code") String reportCode,
        @JsonProperty("bsns_year") String bsnsYear,
        @JsonProperty("corp_code") String corpCode,
        @JsonProperty("fs_div") String fsDiv,
        @JsonProperty("sj_div") String statement,
        @JsonProperty("account_nm") String accountName,
        @JsonProperty("ord") String ord,
        @JsonProperty("thstrm_dt") String currentPeriod,
        @JsonProperty("thstrm_amount") String currentAmount,
        @JsonProperty("thstrm_add_amount") String currentCumulativeAmount,
        @JsonProperty("frmtrm_amount") String priorAmount,
        @JsonProperty("frmtrm_add_amount") String priorCumulativeAmount,
        @JsonProperty("bfefrmtrm_amount") String prior2Amount,
        @JsonProperty("currency") String currency) {
}
