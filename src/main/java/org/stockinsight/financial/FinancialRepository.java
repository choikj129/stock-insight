package org.stockinsight.financial;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;

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
