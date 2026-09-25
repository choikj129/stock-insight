package org.stockinsight.signal;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import tools.jackson.databind.json.JsonMapper;

@Repository
class CompanySignalRepository {

    private final JdbcClient jdbc;
    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    CompanySignalRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** 이 기업의 철회되지 않은 신호 자연키(유형:근거 키) 전체. 이번 판정에 없는 것을 철회할 때 쓴다. */
    Set<String> activeOrPastKeys(long companyId) {
        return Set.copyOf(jdbc.sql("""
                        select signal_type, basis_key from company_signal
                         where company_id = :companyId and status <> 'WITHDRAWN'
                        """)
                .param("companyId", companyId)
                .query((rs, rowNum) -> rs.getString("signal_type") + ":" + rs.getString("basis_key"))
                .list());
    }

    /** 자연키(company_id, signal_type, basis_key) upsert. 상태가 실제로 바뀔 때만 status_changed_at을 갱신한다. */
    void upsert(long companyId, SignalDraft draft, SignalStatus status, String ruleVersion, Instant now) {
        jdbc.sql("""
                        insert into company_signal
                            (company_id, signal_type, basis_key, nature, direction, severity, occurred_on,
                             persistence, calc_values, watch_metrics, source_receipt_no, status, rule_version,
                             first_detected_at, last_evaluated_at, status_changed_at)
                        values (:companyId, :signalType, :basisKey, :nature, :direction, :severity, :occurredOn,
                                :persistence, cast(:calcValues as jsonb), cast(:watchMetrics as jsonb), :sourceReceiptNo,
                                :status, :ruleVersion, :now, :now, :now)
                        on conflict (company_id, signal_type, basis_key) do update
                           set nature = excluded.nature,
                               direction = excluded.direction,
                               severity = excluded.severity,
                               occurred_on = excluded.occurred_on,
                               persistence = excluded.persistence,
                               calc_values = excluded.calc_values,
                               watch_metrics = excluded.watch_metrics,
                               source_receipt_no = excluded.source_receipt_no,
                               status = excluded.status,
                               rule_version = excluded.rule_version,
                               last_evaluated_at = excluded.last_evaluated_at,
                               status_changed_at = case when company_signal.status is distinct from excluded.status
                                                         then excluded.last_evaluated_at
                                                         else company_signal.status_changed_at end
                        """)
                .param("companyId", companyId)
                .param("signalType", draft.type().name())
                .param("basisKey", draft.basisKey())
                .param("nature", draft.nature().name())
                .param("direction", draft.direction().name())
                .param("severity", draft.severity().name())
                .param("occurredOn", draft.occurredOn())
                .param("persistence", draft.persistence())
                .param("calcValues", toJson(draft.calcValues()))
                .param("watchMetrics", toJson(draft.watchMetrics()))
                .param("sourceReceiptNo", draft.sourceReceiptNo())
                .param("status", status.name())
                .param("ruleVersion", ruleVersion)
                .param("now", Timestamp.from(now))
                .update();
    }

    /** 이번 판정에 없는 자연키를 철회한다. */
    int withdraw(long companyId, String signalType, String basisKey, Instant now) {
        return jdbc.sql("""
                        update company_signal
                           set status = 'WITHDRAWN', last_evaluated_at = :now, status_changed_at = :now
                         where company_id = :companyId and signal_type = :signalType and basis_key = :basisKey
                           and status <> 'WITHDRAWN'
                        """)
                .param("companyId", companyId)
                .param("signalType", signalType)
                .param("basisKey", basisKey)
                .param("now", Timestamp.from(now))
                .update();
    }

    List<CompanySignal> findByCompany(long companyId) {
        return jdbc.sql("""
                        select signal_type, basis_key, nature, direction, severity, occurred_on, persistence,
                               source_receipt_no, status, rule_version
                          from company_signal
                         where company_id = :companyId
                         order by signal_type, basis_key
                        """)
                .param("companyId", companyId)
                .query((rs, rowNum) -> new CompanySignal(
                        companyId,
                        SignalType.valueOf(rs.getString("signal_type")),
                        rs.getString("basis_key"),
                        SignalNature.valueOf(rs.getString("nature")),
                        SignalDirection.valueOf(rs.getString("direction")),
                        SignalSeverity.valueOf(rs.getString("severity")),
                        rs.getObject("occurred_on", java.time.LocalDate.class),
                        (Integer) rs.getObject("persistence"),
                        rs.getString("source_receipt_no"),
                        SignalStatus.valueOf(rs.getString("status")),
                        rs.getString("rule_version")))
                .list();
    }

    private String toJson(Object value) {
        return jsonMapper.writeValueAsString(value);
    }
}
