package org.stockinsight.disclosure;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.stockinsight.disclosure.DisclosureService.NewDisclosure;

@Repository
class DisclosureRepository {

    private final JdbcClient jdbc;

    DisclosureRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** 공시번호로 넣거나 갱신한다. 값이 같으면 건드리지 않는다. 기업과 공시 유형은 처음 저장한 값을 유지한다. */
    UpsertOutcome upsert(NewDisclosure disclosure, ReportName reportName, Instant now) {
        return jdbc.sql("""
                        insert into disclosure
                            (receipt_no, company_id, disclosure_type, report_name, base_report_name, amendment_label,
                             received_on, filer_name, remark, created_at, updated_at)
                        values (:receiptNo, :companyId, :type, :reportName, :baseName, :label,
                                :receivedOn, :filerName, :remark, :now, :now)
                        on conflict (receipt_no) do update
                           set report_name = excluded.report_name,
                               base_report_name = excluded.base_report_name,
                               amendment_label = excluded.amendment_label,
                               received_on = excluded.received_on,
                               filer_name = excluded.filer_name,
                               remark = excluded.remark,
                               updated_at = excluded.updated_at
                         where (disclosure.report_name, disclosure.received_on, disclosure.filer_name, disclosure.remark)
                               is distinct from
                               (excluded.report_name, excluded.received_on, excluded.filer_name, excluded.remark)
                        returning (xmax = 0) as inserted
                        """)
                .param("receiptNo", disclosure.receiptNo())
                .param("companyId", disclosure.companyId())
                .param("type", disclosure.type().name())
                .param("reportName", reportName.name())
                .param("baseName", reportName.baseName())
                .param("label", reportName.amendmentLabel())
                .param("receivedOn", disclosure.receivedOn())
                .param("filerName", disclosure.filerName())
                .param("remark", disclosure.remark())
                .param("now", Timestamp.from(now))
                .query(Boolean.class)
                .optional()
                .map(inserted -> inserted ? UpsertOutcome.INSERTED : UpsertOutcome.UPDATED)
                .orElse(UpsertOutcome.UNCHANGED);
    }

    /**
     * 정정 공시를 원 공시에 연결한다. 원 공시 = 같은 기업, 정정 표시와 기타정보를 뗀 보고서명이 같은 최초 제출 공시 중
     * 공시번호가 앞서는 가장 최근 것. 연결이 바뀐 행 수를 돌려준다.
     */
    int relinkAmendments(Collection<Long> companyIds, Instant now) {
        if (companyIds.isEmpty()) {
            return 0;
        }
        return jdbc.sql("""
                        with target as (
                            select a.id,
                                   (select o.id
                                      from disclosure o
                                     where o.company_id = a.company_id
                                       and o.base_report_name = a.base_report_name
                                       and o.amendment_label is null
                                       and o.receipt_no < a.receipt_no
                                     order by o.receipt_no desc
                                     limit 1) as original_id
                              from disclosure a
                             where a.amendment_label is not null
                               and a.company_id in (:companyIds)
                        )
                        update disclosure d
                           set original_id = target.original_id, updated_at = :now
                          from target
                         where d.id = target.id
                           and d.original_id is distinct from target.original_id
                        """)
                .param("companyIds", companyIds)
                .param("now", Timestamp.from(now))
                .update();
    }

    /**
     * 기업·기본 보고서명별 가장 큰 공시번호(정기공시만). 재무 수집이 다시 받을 대상을 정하는 계기다.
     * 같은 기간의 원 공시와 정정을 모두 포함해서 고른다.
     */
    List<PeriodicTrigger> latestPeriodicByCompanyAndBaseName() {
        return jdbc.sql("""
                        select company_id, base_report_name, max(receipt_no) as receipt_no
                          from disclosure
                         where disclosure_type = 'PERIODIC'
                         group by company_id, base_report_name
                        """)
                .query((rs, rowNum) -> new PeriodicTrigger(
                        rs.getLong("company_id"), rs.getString("base_report_name"), rs.getString("receipt_no")))
                .list();
    }

    Optional<Disclosure> findByReceiptNo(String receiptNo) {
        return jdbc.sql("""
                        select d.receipt_no, d.company_id, d.disclosure_type, d.report_name, d.base_report_name,
                               d.amendment_label, d.received_on, d.filer_name, d.remark, o.receipt_no as original_receipt_no
                          from disclosure d
                          left join disclosure o on o.id = d.original_id
                         where d.receipt_no = :receiptNo
                        """)
                .param("receiptNo", receiptNo)
                .query((rs, rowNum) -> new Disclosure(
                        rs.getString("receipt_no"),
                        rs.getLong("company_id"),
                        DisclosureType.valueOf(rs.getString("disclosure_type")),
                        rs.getString("report_name"),
                        rs.getString("base_report_name"),
                        rs.getString("amendment_label"),
                        rs.getObject("received_on", LocalDate.class),
                        rs.getString("filer_name"),
                        rs.getString("remark"),
                        rs.getString("original_receipt_no")))
                .optional();
    }

    enum UpsertOutcome {
        INSERTED, UPDATED, UNCHANGED
    }
}
