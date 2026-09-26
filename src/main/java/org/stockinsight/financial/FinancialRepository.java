package org.stockinsight.financial;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class FinancialRepository {

    private final JdbcClient jdbc;

    FinancialRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    Set<String> existingFsDivs(long companyId, int bsnsYear, String reportCode) {
        return Set.copyOf(jdbc.sql("""
                        select fs_div from financial_report
                         where company_id = :companyId and bsns_year = :bsnsYear and report_code = :reportCode
                        """)
                .param("companyId", companyId)
                .param("bsnsYear", bsnsYear)
                .param("reportCode", reportCode)
                .query(String.class)
                .list());
    }

    /**
     * 기간 말·기준으로 현재 보고서의 공시번호만 찾는다(계정 행을 읽지 않는다). 무효화 판정(D-42)이
     * 재무 요약 전체를 다시 만들지 않고 참조한 보고서 하나의 현재 상태만 확인할 때 쓴다.
     */
    Optional<String> findReceiptNoByPeriodEnd(long companyId, LocalDate periodEnd, String fsDiv) {
        return jdbc.sql("""
                        select receipt_no from financial_report
                         where company_id = :companyId and period_end = :periodEnd and fs_div = :fsDiv
                        """)
                .param("companyId", companyId)
                .param("periodEnd", periodEnd)
                .param("fsDiv", fsDiv)
                .query(String.class)
                .optional();
    }

    Optional<ExistingReport> find(long companyId, int bsnsYear, String reportCode, String fsDiv) {
        return jdbc.sql("""
                        select id, fiscal_year_start, period_end, currency, receipt_no
                          from financial_report
                         where company_id = :companyId and bsns_year = :bsnsYear
                           and report_code = :reportCode and fs_div = :fsDiv
                        """)
                .param("companyId", companyId)
                .param("bsnsYear", bsnsYear)
                .param("reportCode", reportCode)
                .param("fsDiv", fsDiv)
                .query((rs, rowNum) -> new ExistingReport(
                        rs.getLong("id"),
                        rs.getObject("fiscal_year_start", LocalDate.class),
                        rs.getObject("period_end", LocalDate.class),
                        rs.getString("currency"),
                        rs.getString("receipt_no")))
                .optional();
    }

    Optional<StoredFinancialReport> findFull(long companyId, int bsnsYear, String reportCode, String fsDiv) {
        return find(companyId, bsnsYear, reportCode, fsDiv)
                .map(existing -> new StoredFinancialReport(
                        companyId, bsnsYear, reportCode, fsDiv, PeriodType.fromReportCode(reportCode),
                        existing.fiscalYearStart(), existing.periodEnd(), existing.currency(), existing.receiptNo(),
                        findLines(existing.id())));
    }

    List<StoredFinancialLine> findLines(long reportId) {
        return jdbc.sql("""
                        select ord, statement, account_name, current_amount, current_cumulative_amount,
                               prior_amount, prior_cumulative_amount, prior2_amount
                          from financial_line
                         where report_id = :reportId
                         order by ord
                        """)
                .param("reportId", reportId)
                .query((rs, rowNum) -> new StoredFinancialLine(
                        rs.getInt("ord"),
                        rs.getString("statement"),
                        rs.getString("account_name"),
                        rs.getObject("current_amount", BigDecimal.class),
                        rs.getObject("current_cumulative_amount", BigDecimal.class),
                        rs.getObject("prior_amount", BigDecimal.class),
                        rs.getObject("prior_cumulative_amount", BigDecimal.class),
                        rs.getObject("prior2_amount", BigDecimal.class)))
                .list();
    }

    /** 한 기업의 모든 보고서와 계정 행. 재무 요약을 만들 때 한 번에 읽는다. */
    List<StoredFinancialReport> findAllByCompany(long companyId) {
        record Row(long reportId, int bsnsYear, String reportCode, String fsDiv, LocalDate fiscalYearStart,
                LocalDate periodEnd, String currency, String receiptNo, StoredFinancialLine line) {
        }
        List<Row> rows = jdbc.sql("""
                        select r.id as report_id, r.bsns_year, r.report_code, r.fs_div, r.fiscal_year_start,
                               r.period_end, r.currency, r.receipt_no,
                               l.ord, l.statement, l.account_name, l.current_amount, l.current_cumulative_amount,
                               l.prior_amount, l.prior_cumulative_amount, l.prior2_amount
                          from financial_report r
                          join financial_line l on l.report_id = r.id
                         where r.company_id = :companyId
                         order by r.period_end, r.fs_div, l.ord
                        """)
                .param("companyId", companyId)
                .query((rs, rowNum) -> new Row(
                        rs.getLong("report_id"), rs.getInt("bsns_year"), rs.getString("report_code"),
                        rs.getString("fs_div"), rs.getObject("fiscal_year_start", LocalDate.class),
                        rs.getObject("period_end", LocalDate.class), rs.getString("currency"), rs.getString("receipt_no"),
                        new StoredFinancialLine(
                                rs.getInt("ord"), rs.getString("statement"), rs.getString("account_name"),
                                rs.getObject("current_amount", BigDecimal.class),
                                rs.getObject("current_cumulative_amount", BigDecimal.class),
                                rs.getObject("prior_amount", BigDecimal.class),
                                rs.getObject("prior_cumulative_amount", BigDecimal.class),
                                rs.getObject("prior2_amount", BigDecimal.class))))
                .list();

        Map<Long, List<Row>> byReport = rows.stream()
                .collect(Collectors.groupingBy(Row::reportId, LinkedHashMap::new, Collectors.toList()));
        List<StoredFinancialReport> reports = new ArrayList<>();
        for (List<Row> group : byReport.values()) {
            Row first = group.get(0);
            reports.add(new StoredFinancialReport(
                    companyId, first.bsnsYear(), first.reportCode(), first.fsDiv(),
                    PeriodType.fromReportCode(first.reportCode()), first.fiscalYearStart(), first.periodEnd(),
                    first.currency(), first.receiptNo(), group.stream().map(Row::line).toList()));
        }
        return reports;
    }

    /** 기업별 재무 마지막 변경 시각(가장 늦은 financial_report.updated_at). 신호 재계산 대상 판단에 쓴다. */
    Map<Long, Instant> lastChangedByCompanyId() {
        Map<Long, Instant> result = new HashMap<>();
        jdbc.sql("select company_id, max(updated_at) as last_changed from financial_report group by company_id")
                .query((rs, rowNum) -> {
                    result.put(rs.getLong("company_id"), rs.getTimestamp("last_changed").toInstant());
                    return null;
                })
                .list();
        return result;
    }

    void delete(long reportId) {
        jdbc.sql("delete from financial_report where id = :id").param("id", reportId).update();
    }

    long insertReport(long companyId, int bsnsYear, String reportCode, String fsDiv, PeriodType periodType,
            LocalDate fiscalYearStart, LocalDate periodEnd, String currency, String receiptNo, Instant now) {
        return jdbc.sql("""
                        insert into financial_report
                            (company_id, bsns_year, report_code, fs_div, period_type, fiscal_year_start,
                             period_end, currency, receipt_no, created_at, updated_at)
                        values (:companyId, :bsnsYear, :reportCode, :fsDiv, :periodType, :fiscalYearStart,
                                :periodEnd, :currency, :receiptNo, :now, :now)
                        returning id
                        """)
                .param("companyId", companyId)
                .param("bsnsYear", bsnsYear)
                .param("reportCode", reportCode)
                .param("fsDiv", fsDiv)
                .param("periodType", periodType.name())
                .param("fiscalYearStart", fiscalYearStart)
                .param("periodEnd", periodEnd)
                .param("currency", currency)
                .param("receiptNo", receiptNo)
                .param("now", Timestamp.from(now))
                .query(Long.class)
                .single();
    }

    void insertLines(long reportId, List<StoredFinancialLine> lines) {
        for (StoredFinancialLine line : lines) {
            jdbc.sql("""
                            insert into financial_line
                                (report_id, ord, statement, account_name, current_amount, current_cumulative_amount,
                                 prior_amount, prior_cumulative_amount, prior2_amount)
                            values (:reportId, :ord, :statement, :accountName, :currentAmount, :currentCumulativeAmount,
                                    :priorAmount, :priorCumulativeAmount, :prior2Amount)
                            """)
                    .param("reportId", reportId)
                    .param("ord", line.ord())
                    .param("statement", line.statement())
                    .param("accountName", line.accountName())
                    .param("currentAmount", line.currentAmount())
                    .param("currentCumulativeAmount", line.currentCumulativeAmount())
                    .param("priorAmount", line.priorAmount())
                    .param("priorCumulativeAmount", line.priorCumulativeAmount())
                    .param("prior2Amount", line.prior2Amount())
                    .update();
        }
    }

    record ExistingReport(long id, LocalDate fiscalYearStart, LocalDate periodEnd, String currency, String receiptNo) {
    }
}
