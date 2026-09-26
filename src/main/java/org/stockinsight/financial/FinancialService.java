package org.stockinsight.financial;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.stockinsight.financial.FinancialRepository.ExistingReport;

/**
 * 재무 보고서의 저장 규칙을 담당한다. 수집기는 이 서비스로만 재무 데이터를 바꾼다 (D-32, D-33).
 * 계정명 → 지표 키 매핑, 재무제표 형식 판별, 비율·4분기 계산은 하지 않는다(4단계).
 */
@Service
@Transactional
public class FinancialService {

    private static final Pattern PERIOD_RANGE = Pattern.compile("(\\d{4}\\.\\d{2}\\.\\d{2})\\s*~\\s*(\\d{4}\\.\\d{2}\\.\\d{2})");
    private static final DateTimeFormatter PERIOD_DATE = DateTimeFormatter.ofPattern("yyyy.MM.dd");
    private static final String INCOME_STATEMENT = "IS";

    private final FinancialRepository repository;
    private final Clock clock;

    FinancialService(FinancialRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /**
     * 한 기업·조회 키(bsnsYear, reportCode)의 응답(연결·별도 모두)을 한 트랜잭션에서 통째로 반영한다.
     * 응답에서 빠진 fs_div의 기존 보고서는 지운다.
     *
     * @param expectedPeriodEndMonth 계기 공시(정기공시)로 다시 받을 때 그 보고서명이 가리키는 기간 종료월.
     *                                초기 적재는 null(검증하지 않음)
     * @throws FinancialPeriodException 손익 기간을 식별할 수 없거나, 응답 기간이 계기 공시와 다를 때(저장하지 않는다)
     */
    public ReplaceResult replace(long companyId, int bsnsYear, String reportCode, List<RawAccountLine> rows,
            YearMonth expectedPeriodEndMonth) {
        PeriodType periodType = PeriodType.fromReportCode(reportCode);
        LocalDate[] period = identifyPeriod(rows, companyId, bsnsYear, reportCode);
        LocalDate fiscalYearStart = period[0];
        LocalDate periodEnd = period[1];
        if (expectedPeriodEndMonth != null && !YearMonth.from(periodEnd).equals(expectedPeriodEndMonth)) {
            throw new FinancialPeriodException(
                    "기간 불일치: 계기 공시 기간(%s)과 응답 기간 종료월(%s)이 다릅니다 (기업 %d, %d/%s)"
                            .formatted(expectedPeriodEndMonth, YearMonth.from(periodEnd), companyId, bsnsYear, reportCode));
        }

        Instant now = clock.instant();
        Map<String, List<RawAccountLine>> byFsDiv = rows.stream().collect(Collectors.groupingBy(RawAccountLine::fsDiv));

        int removed = 0;
        for (String staleFsDiv : difference(repository.existingFsDivs(companyId, bsnsYear, reportCode), byFsDiv.keySet())) {
            repository.find(companyId, bsnsYear, reportCode, staleFsDiv).ifPresent(existing -> repository.delete(existing.id()));
            removed++;
        }

        int created = 0;
        int updated = 0;
        int unchanged = 0;
        for (Map.Entry<String, List<RawAccountLine>> entry : byFsDiv.entrySet()) {
            String fsDiv = entry.getKey();
            List<RawAccountLine> group = entry.getValue();
            String currency = group.get(0).currency();
            String receiptNo = group.get(0).receiptNo();
            List<StoredFinancialLine> lines = group.stream().map(FinancialService::toLine)
                    .sorted(Comparator.comparingInt(StoredFinancialLine::ord))
                    .toList();

            Optional<ExistingReport> existing = repository.find(companyId, bsnsYear, reportCode, fsDiv);
            if (existing.isPresent() && sameContent(existing.get(), fiscalYearStart, periodEnd, currency, receiptNo, lines)) {
                unchanged++;
                continue;
            }
            existing.ifPresent(report -> repository.delete(report.id()));
            long reportId = repository.insertReport(companyId, bsnsYear, reportCode, fsDiv, periodType,
                    fiscalYearStart, periodEnd, currency, receiptNo, now);
            repository.insertLines(reportId, lines);
            if (existing.isPresent()) {
                updated++;
            } else {
                created++;
            }
        }
        return new ReplaceResult(created, updated, unchanged, removed);
    }

    @Transactional(readOnly = true)
    public Optional<StoredFinancialReport> find(long companyId, int bsnsYear, String reportCode, String fsDiv) {
        return repository.findFull(companyId, bsnsYear, reportCode, fsDiv);
    }

    /** 기업별 재무 마지막 변경 시각. 재무 신호 재계산 대상 판단에 쓴다(architecture.md §4.4). */
    @Transactional(readOnly = true)
    public Map<Long, Instant> lastChangedByCompanyId() {
        return repository.lastChangedByCompanyId();
    }

    /** 그 기간·기준 보고서의 현재 공시번호. 보고서가 없어졌으면 빈 값이다(무효화 판정, D-42). */
    @Transactional(readOnly = true)
    public Optional<String> currentReceiptNo(long companyId, LocalDate periodEnd, String fsDiv) {
        return repository.findReceiptNoByPeriodEnd(companyId, periodEnd, fsDiv);
    }

    private boolean sameContent(ExistingReport existing, LocalDate fiscalYearStart, LocalDate periodEnd,
            String currency, String receiptNo, List<StoredFinancialLine> newLines) {
        if (!existing.fiscalYearStart().equals(fiscalYearStart) || !existing.periodEnd().equals(periodEnd)
                || !existing.currency().equals(currency) || !existing.receiptNo().equals(receiptNo)) {
            return false;
        }
        List<StoredFinancialLine> existingLines = repository.findLines(existing.id());
        if (existingLines.size() != newLines.size()) {
            return false;
        }
        for (int i = 0; i < existingLines.size(); i++) {
            if (!existingLines.get(i).sameContentAs(newLines.get(i))) {
                return false;
            }
        }
        return true;
    }

    /** 손익(IS) 행의 thstrm_dt("YYYY.MM.DD ~ YYYY.MM.DD")에서 회계연도 시작일·기간 종료일을 읽는다 (D-33). */
    private static LocalDate[] identifyPeriod(List<RawAccountLine> rows, long companyId, int bsnsYear, String reportCode) {
        for (RawAccountLine row : rows) {
            if (!INCOME_STATEMENT.equals(row.statement()) || row.currentPeriod() == null) {
                continue;
            }
            Matcher matcher = PERIOD_RANGE.matcher(row.currentPeriod().strip());
            if (matcher.matches()) {
                return new LocalDate[] {
                        LocalDate.parse(matcher.group(1), PERIOD_DATE),
                        LocalDate.parse(matcher.group(2), PERIOD_DATE)
                };
            }
        }
        throw new FinancialPeriodException(
                "기간 식별 실패: 손익 행이 없거나 기간 형식이 다릅니다 (기업 %d, %d/%s)".formatted(companyId, bsnsYear, reportCode));
    }

    private static StoredFinancialLine toLine(RawAccountLine row) {
        return new StoredFinancialLine(
                Integer.parseInt(row.ord().strip()),
                row.statement(),
                row.accountName(),
                FinancialAmounts.parse(row.currentAmount()),
                FinancialAmounts.parse(row.currentCumulativeAmount()),
                FinancialAmounts.parse(row.priorAmount()),
                FinancialAmounts.parse(row.priorCumulativeAmount()),
                FinancialAmounts.parse(row.prior2Amount()));
    }

    private static Set<String> difference(Set<String> a, Set<String> b) {
        return a.stream().filter(item -> !b.contains(item)).collect(Collectors.toSet());
    }

    public record ReplaceResult(int created, int updated, int unchanged, int removed) {
    }
}
