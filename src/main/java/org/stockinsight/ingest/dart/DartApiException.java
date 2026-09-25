package org.stockinsight.ingest.dart;

/**
 * OpenDART 호출 실패. 메시지에 요청 URL을 넣지 않는다 (URL에 인증키가 들어 있다).
 */
public class DartApiException extends RuntimeException {

    private final DartStatus status;

    public DartApiException(DartStatus status, String message) {
        super(message);
        this.status = status;
    }

    public DartStatus status() {
        return status;
    }

    public boolean stopsRun() {
        return status.stopsRun();
    }
}
