package org.stockinsight.ingest.checkpoint;

import java.time.Instant;

public record IngestCheckpoint(
        String source,
        String targetKey,
        String sourceVersion,
        Result result,
        String message,
        int attemptCount,
        Instant lastAttemptAt,
        Instant lastSuccessAt) {

    public enum Result {
        SUCCESS,
        /** 소스가 "데이터 없음"을 돌려줬다. 반복 요청하지 않는다. */
        NO_DATA,
        ERROR
    }
}
