package org.stockinsight.ingest.checkpoint;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 소스·대상별 마지막 수집 결과. 증분 수집과 초기 적재 재개의 기준이다 (docs/architecture.md §4.4).
 */
@Repository
public class IngestCheckpointRepository {

    private static final int MAX_MESSAGE_LENGTH = 500;

    private final JdbcClient jdbc;

    IngestCheckpointRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Map<String, IngestCheckpoint> findAllBySource(String source) {
        return jdbc.sql("""
                        select source, target_key, source_version, result, message,
                               attempt_count, last_attempt_at, last_success_at, next_check_at
                          from ingest_checkpoint
                         where source = :source
                        """)
                .param("source", source)
                .query((rs, rowNum) -> new IngestCheckpoint(
                        rs.getString("source"),
                        rs.getString("target_key"),
                        rs.getString("source_version"),
                        IngestCheckpoint.Result.valueOf(rs.getString("result")),
                        rs.getString("message"),
                        rs.getInt("attempt_count"),
                        rs.getTimestamp("last_attempt_at").toInstant(),
                        rs.getTimestamp("last_success_at") == null ? null : rs.getTimestamp("last_success_at").toInstant(),
                        rs.getObject("next_check_at", LocalDate.class)))
                .list()
                .stream()
                .collect(Collectors.toMap(IngestCheckpoint::targetKey, Function.identity()));
    }

    /** 시도 결과를 기록한다. 오류가 이어지면 시도 횟수가 늘고, 성공이나 데이터 없음이면 0으로 돌아간다. */
    public void record(String source, String targetKey, String sourceVersion, IngestCheckpoint.Result result,
            String message, Instant attemptedAt) {
        record(source, targetKey, sourceVersion, result, message, attemptedAt, null);
    }

    /**
     * {@code nextCheckAt}이 있으면 소스 버전이 그대로여도 그 날짜가 지나면 다시 대상이 된다(D-43, 시간 기반 재판정).
     */
    public void record(String source, String targetKey, String sourceVersion, IngestCheckpoint.Result result,
            String message, Instant attemptedAt, LocalDate nextCheckAt) {
        jdbc.sql("""
                        insert into ingest_checkpoint
                            (source, target_key, source_version, result, message, attempt_count, last_attempt_at,
                             last_success_at, next_check_at)
                        values (:source, :key, :version, :result, :message, :attempts,
                                :at, cast(:successAt as timestamptz), :nextCheckAt)
                        on conflict (source, target_key) do update
                           set source_version = excluded.source_version,
                               result = excluded.result,
                               message = excluded.message,
                               attempt_count = case when excluded.result = 'ERROR'
                                                    then ingest_checkpoint.attempt_count + 1 else 0 end,
                               last_attempt_at = excluded.last_attempt_at,
                               last_success_at = coalesce(excluded.last_success_at, ingest_checkpoint.last_success_at),
                               next_check_at = excluded.next_check_at
                        """)
                .param("source", source)
                .param("key", targetKey)
                .param("version", sourceVersion)
                .param("result", result.name())
                .param("message", truncate(message))
                .param("attempts", result == IngestCheckpoint.Result.ERROR ? 1 : 0)
                .param("at", Timestamp.from(attemptedAt))
                .param("successAt", result == IngestCheckpoint.Result.SUCCESS ? Timestamp.from(attemptedAt) : null)
                .param("nextCheckAt", nextCheckAt)
                .update();
    }

    private static String truncate(String message) {
        if (message == null || message.length() <= MAX_MESSAGE_LENGTH) {
            return message;
        }
        return message.substring(0, MAX_MESSAGE_LENGTH);
    }
}
