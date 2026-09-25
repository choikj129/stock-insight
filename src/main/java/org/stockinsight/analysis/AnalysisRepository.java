package org.stockinsight.analysis;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import tools.jackson.databind.json.JsonMapper;

@Repository
class AnalysisRepository {

    private final JdbcClient jdbc;
    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    AnalysisRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    long insert(NewAnalysis draft, Instant now) {
        return jdbc.sql("""
                        insert into analysis
                            (target_type, target_key, analysis_kind, fingerprint, status, result_json, input_json,
                             value_snapshot, schema_version, prompt_version, model, input_builder_version, rule_version,
                             input_tokens, output_tokens, cache_read_tokens, cost_usd, failure_reasons, attempt_count,
                             created_at, is_current)
                        values (:targetType, :targetKey, :analysisKind, :fingerprint, :status,
                                cast(:resultJson as jsonb), cast(:inputJson as jsonb), cast(:valueSnapshot as jsonb),
                                :schemaVersion, :promptVersion, :model, :inputBuilderVersion, :ruleVersion,
                                :inputTokens, :outputTokens, :cacheReadTokens, :costUsd, cast(:failureReasons as jsonb),
                                :attemptCount, :now, false)
                        returning id
                        """)
                .param("targetType", draft.targetType().name())
                .param("targetKey", draft.targetKey())
                .param("analysisKind", draft.analysisKind().name())
                .param("fingerprint", draft.fingerprint())
                .param("status", draft.status().name())
                .param("resultJson", draft.resultJson() == null ? null : toJson(draft.resultJson()))
                .param("inputJson", toJson(draft.inputJson()))
                .param("valueSnapshot", draft.valueSnapshot() == null ? null : toJson(draft.valueSnapshot()))
                .param("schemaVersion", draft.schemaVersion())
                .param("promptVersion", draft.promptVersion())
                .param("model", draft.model())
                .param("inputBuilderVersion", draft.inputBuilderVersion())
                .param("ruleVersion", draft.ruleVersion())
                .param("inputTokens", draft.inputTokens())
                .param("outputTokens", draft.outputTokens())
                .param("cacheReadTokens", draft.cacheReadTokens())
                .param("costUsd", draft.costUsd())
                .param("failureReasons", draft.failureReasons() == null ? null : toJson(draft.failureReasons()))
                .param("attemptCount", draft.attemptCount())
                .param("now", Timestamp.from(now))
                .query(Long.class)
                .single();
    }

    /** 이 행을 게시본으로 만들고, 같은 대상·종류의 기존 게시본은 내린다. */
    void publish(long id, TargetType targetType, String targetKey, AnalysisKind analysisKind, Instant now) {
        jdbc.sql("""
                        update analysis set is_current = false
                         where target_type = :targetType and target_key = :targetKey and analysis_kind = :analysisKind
                           and is_current = true
                        """)
                .param("targetType", targetType.name())
                .param("targetKey", targetKey)
                .param("analysisKind", analysisKind.name())
                .update();
        jdbc.sql("""
                        update analysis
                           set status = 'PUBLISHED', is_current = true, published_at = :now
                         where id = :id
                        """)
                .param("id", id)
                .param("now", Timestamp.from(now))
                .update();
    }

    Optional<Analysis> findCurrent(TargetType targetType, String targetKey, AnalysisKind analysisKind) {
        return jdbc.sql(SELECT + """
                         where target_type = :targetType and target_key = :targetKey and analysis_kind = :analysisKind
                           and is_current = true
                        """)
                .param("targetType", targetType.name())
                .param("targetKey", targetKey)
                .param("analysisKind", analysisKind.name())
                .query(this::mapRow)
                .optional();
    }

    /** 같은 지문의 가장 최근 시도(상태 무관). 건너뜀·backoff 판단에 쓴다. */
    Optional<Analysis> findLatestByFingerprint(TargetType targetType, String targetKey, AnalysisKind analysisKind, String fingerprint) {
        return jdbc.sql(SELECT + """
                         where target_type = :targetType and target_key = :targetKey and analysis_kind = :analysisKind
                           and fingerprint = :fingerprint
                         order by created_at desc
                         limit 1
                        """)
                .param("targetType", targetType.name())
                .param("targetKey", targetKey)
                .param("analysisKind", analysisKind.name())
                .param("fingerprint", fingerprint)
                .query(this::mapRow)
                .optional();
    }

    /** 같은 지문으로 누적된 REJECTED·FAILED 수(3회 중단 판단). */
    int countFailuresByFingerprint(TargetType targetType, String targetKey, AnalysisKind analysisKind, String fingerprint) {
        return jdbc.sql("""
                        select count(*) from analysis
                         where target_type = :targetType and target_key = :targetKey and analysis_kind = :analysisKind
                           and fingerprint = :fingerprint and status in ('REJECTED', 'FAILED')
                        """)
                .param("targetType", targetType.name())
                .param("targetKey", targetKey)
                .param("analysisKind", analysisKind.name())
                .param("fingerprint", fingerprint)
                .query(Integer.class)
                .single();
    }

    /** 모든 분석 종류를 합쳐 이 시각 이후 발생한 비용(§6.2 예산은 분석 종류 공통). */
    BigDecimal sumCostSince(Instant since) {
        BigDecimal sum = jdbc.sql("select coalesce(sum(cost_usd), 0) from analysis where created_at >= :since")
                .param("since", Timestamp.from(since))
                .query(BigDecimal.class)
                .single();
        return sum;
    }

    List<Analysis> findByTarget(TargetType targetType, String targetKey, AnalysisKind analysisKind) {
        return jdbc.sql(SELECT + """
                         where target_type = :targetType and target_key = :targetKey and analysis_kind = :analysisKind
                         order by created_at desc
                        """)
                .param("targetType", targetType.name())
                .param("targetKey", targetKey)
                .param("analysisKind", analysisKind.name())
                .query(this::mapRow)
                .list();
    }

    private static final String SELECT = """
            select id, target_type, target_key, analysis_kind, fingerprint, status, result_json, input_json,
                   value_snapshot, schema_version, prompt_version, model, input_builder_version, rule_version,
                   input_tokens, output_tokens, cache_read_tokens, cost_usd, failure_reasons, attempt_count,
                   created_at, published_at, is_current
              from analysis
            """;

    @SuppressWarnings("unchecked")
    private Analysis mapRow(ResultSet rs, int rowNum) throws SQLException {
        String resultJson = rs.getString("result_json");
        String failureReasonsJson = rs.getString("failure_reasons");
        Timestamp publishedAt = rs.getTimestamp("published_at");
        return new Analysis(
                rs.getLong("id"),
                TargetType.valueOf(rs.getString("target_type")),
                rs.getString("target_key"),
                AnalysisKind.valueOf(rs.getString("analysis_kind")),
                rs.getString("fingerprint"),
                AnalysisStatus.valueOf(rs.getString("status")),
                resultJson == null ? null : jsonMapper.readValue(resultJson, FinancialExplainOutput.class),
                jsonMapper.readValue(rs.getString("input_json"), FinancialExplainInput.class),
                rs.getString("value_snapshot") == null ? null
                        : jsonMapper.readValue(rs.getString("value_snapshot"), ValueSnapshot.class),
                rs.getString("schema_version"),
                rs.getString("prompt_version"),
                rs.getString("model"),
                rs.getString("input_builder_version"),
                rs.getString("rule_version"),
                (Integer) rs.getObject("input_tokens"),
                (Integer) rs.getObject("output_tokens"),
                (Integer) rs.getObject("cache_read_tokens"),
                rs.getBigDecimal("cost_usd"),
                failureReasonsJson == null ? List.of() : jsonMapper.readValue(failureReasonsJson, List.class),
                rs.getInt("attempt_count"),
                rs.getTimestamp("created_at").toInstant(),
                publishedAt == null ? null : publishedAt.toInstant(),
                rs.getBoolean("is_current"));
    }

    private String toJson(Object value) {
        return jsonMapper.writeValueAsString(value);
    }
}
