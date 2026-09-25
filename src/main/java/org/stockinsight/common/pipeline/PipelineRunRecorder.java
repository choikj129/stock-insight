package org.stockinsight.common.pipeline;

import java.sql.Timestamp;
import java.time.Clock;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** 스케줄 작업의 실행 기록(pipeline_run)을 남긴다. */
@Component
public class PipelineRunRecorder {

    private static final int MAX_MESSAGE_LENGTH = 1000;

    private final JdbcClient jdbc;
    private final Clock clock;

    PipelineRunRecorder(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** 실행을 시작한다. 같은 작업의 이전 실행이 RUNNING으로 남아 있으면 비정상 종료로 보고 FAILED로 바꾼다. */
    public long start(String jobName) {
        Timestamp now = now();
        jdbc.sql("""
                        update pipeline_run
                           set status = 'FAILED', finished_at = :now, message = '다음 실행 시작 시 RUNNING으로 남아 있어 중단된 것으로 처리'
                         where job_name = :job and status = 'RUNNING'
                        """)
                .param("now", now)
                .param("job", jobName)
                .update();
        return jdbc.sql("""
                        insert into pipeline_run (job_name, status, started_at)
                        values (:job, 'RUNNING', :now)
                        returning id
                        """)
                .param("job", jobName)
                .param("now", now)
                .query(Long.class)
                .single();
    }

    public void finish(long runId, RunStatus status, int processed, int failed, String message) {
        jdbc.sql("""
                        update pipeline_run
                           set status = :status, finished_at = :now,
                               processed_count = :processed, failed_count = :failed, message = :message
                         where id = :id
                        """)
                .param("status", status.name())
                .param("now", now())
                .param("processed", processed)
                .param("failed", failed)
                .param("message", truncate(message))
                .param("id", runId)
                .update();
    }

    private Timestamp now() {
        return Timestamp.from(clock.instant());
    }

    private static String truncate(String message) {
        if (message == null || message.length() <= MAX_MESSAGE_LENGTH) {
            return message;
        }
        return message.substring(0, MAX_MESSAGE_LENGTH);
    }

    public enum RunStatus {
        RUNNING,
        /** 처리할 대상을 모두 처리했다. */
        SUCCEEDED,
        /** 호출 한도 등으로 일부만 처리했다. 다음 실행이 이어서 처리한다. */
        PARTIAL,
        FAILED
    }
}
