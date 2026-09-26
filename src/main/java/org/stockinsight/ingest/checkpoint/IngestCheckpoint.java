package org.stockinsight.ingest.checkpoint;

import java.time.Instant;
import java.time.LocalDate;

public record IngestCheckpoint(
        String source,
        String targetKey,
        String sourceVersion,
        Result result,
        String message,
        int attemptCount,
        Instant lastAttemptAt,
        Instant lastSuccessAt,
        /** 소스 버전이 그대로여도 이 날짜가 지나면 다시 확인해야 한다. 시간만으로 판정이 바뀌는 작업에만 쓴다(D-43). */
        LocalDate nextCheckAt) {

    public enum Result {
        SUCCESS,
        /** 소스가 "데이터 없음"을 돌려줬다. 반복 요청하지 않는다. */
        NO_DATA,
        ERROR
    }
}
