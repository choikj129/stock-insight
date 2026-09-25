package org.stockinsight.ingest.dart;

import java.util.Arrays;

/**
 * OpenDART 응답 상태 코드. {@link #stopsRun()}이 참이면 같은 실행에서 다른 요청도 실패할 것이므로 실행을 멈춘다.
 * 코드 정의: https://opendart.fss.or.kr/guide/detail.do?apiGrpCd=DS001&apiId=2019018
 */
public enum DartStatus {
    SUCCESS("000", false),
    UNREGISTERED_KEY("010", true),
    DISABLED_KEY("011", true),
    RESTRICTED_IP("012", true),
    NO_DATA("013", false),
    FILE_NOT_FOUND("014", false),
    REQUEST_LIMIT_EXCEEDED("020", true),
    COMPANY_LIMIT_EXCEEDED("021", false),
    INVALID_FIELD("100", false),
    IMPROPER_ACCESS("101", false),
    MAINTENANCE("800", true),
    UNDEFINED_ERROR("900", false),
    EXPIRED_ACCOUNT("901", true),

    /** 인증키가 설정되지 않았다 (요청 전 판단). */
    MISSING_KEY(null, true),
    /** 재시도 후에도 통신에 실패했다. */
    TRANSPORT_ERROR(null, true),
    /** 응답 형식이 예상과 다르다. */
    UNEXPECTED_RESPONSE(null, false),
    /** 문서에 없는 상태 코드. */
    UNKNOWN(null, false);

    private final String code;
    private final boolean stopsRun;

    DartStatus(String code, boolean stopsRun) {
        this.code = code;
        this.stopsRun = stopsRun;
    }

    public static DartStatus fromCode(String code) {
        return Arrays.stream(values())
                .filter(status -> status.code != null && status.code.equals(code))
                .findFirst()
                .orElse(UNKNOWN);
    }

    public String code() {
        return code;
    }

    public boolean stopsRun() {
        return stopsRun;
    }
}
