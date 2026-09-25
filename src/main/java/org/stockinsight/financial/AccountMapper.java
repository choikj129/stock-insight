package org.stockinsight.financial;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 계정명 → 지표 키 매핑과 재무제표 형식 판별 (ai-analysis.md §3.5).
 * 값은 원천 그대로 두고(D-32) 여기서 읽을 때만 해석한다.
 */
final class AccountMapper {

    private static final Logger log = LoggerFactory.getLogger(AccountMapper.class);

    private static final String BALANCE_SHEET = "BS";
    private static final String CURRENT_ASSETS = "유동자산";
    private static final String REVENUE = "매출액";
    private static final String NET_INCOME = "당기순이익(손실)";
    private static final String TOTAL_ASSETS = "자산총계";
    private static final String TOTAL_LIABILITIES = "부채총계";
    private static final String TOTAL_EQUITY = "자본총계";
    private static final String CAPITAL_STOCK = "자본금";

    private AccountMapper() {
    }

    static MappedAccounts map(List<StoredFinancialLine> lines, String context) {
        FinancialFormat format = lines.stream()
                .anyMatch(line -> BALANCE_SHEET.equals(line.statement()) && CURRENT_ASSETS.equals(line.accountName()))
                ? FinancialFormat.GENERAL : FinancialFormat.FINANCIAL;

        StoredFinancialLine netIncomeLine = netIncomeLine(lines, context);
        return new MappedAccounts(
                format,
                toMetric(revenueLine(lines)),
                toMetric(operatingIncomeLine(lines)),
                toMetric(Optional.ofNullable(netIncomeLine)),
                toMetric(findLine(lines, TOTAL_ASSETS)),
                toMetric(findLine(lines, TOTAL_LIABILITIES)),
                toMetric(findLine(lines, TOTAL_EQUITY)),
                toMetric(findLine(lines, CAPITAL_STOCK)));
    }

    /**
     * 4분기 파생(연간 − 3분기 누적)에 쓸 3분기 보고서의 당기누적값(thstrm_add_amount). 3개월 단독값이 아니다.
     */
    static CumulativeAccounts mapCumulative(List<StoredFinancialLine> lines, String context) {
        StoredFinancialLine netIncomeLine = netIncomeLine(lines, context);
        return new CumulativeAccounts(
                cumulative(revenueLine(lines)),
                cumulative(operatingIncomeLine(lines)),
                cumulative(Optional.ofNullable(netIncomeLine)));
    }

    private static Optional<StoredFinancialLine> revenueLine(List<StoredFinancialLine> lines) {
        return findLine(lines, REVENUE);
    }

    /** "영업이익" 또는 "영업이익(손실)" — 같은 계정의 두 이름이다. */
    private static Optional<StoredFinancialLine> operatingIncomeLine(List<StoredFinancialLine> lines) {
        return lines.stream()
                .filter(line -> "영업이익".equals(line.accountName()) || "영업이익(손실)".equals(line.accountName()))
                .findFirst();
    }

    /** 당기순이익은 한 보고서에 두 번 나온다. 순서(ord)가 작은 행을 쓰고, 값이 다르면 경고를 남긴다. */
    private static StoredFinancialLine netIncomeLine(List<StoredFinancialLine> lines, String context) {
        List<StoredFinancialLine> matches = lines.stream()
                .filter(line -> NET_INCOME.equals(line.accountName()))
                .sorted(Comparator.comparingInt(StoredFinancialLine::ord))
                .toList();
        if (matches.isEmpty()) {
            return null;
        }
        StoredFinancialLine chosen = matches.get(0);
        if (matches.size() > 1) {
            boolean mismatch = matches.stream().skip(1)
                    .anyMatch(other -> !amountsEqual(other.currentAmount(), chosen.currentAmount())
                            || !amountsEqual(other.priorAmount(), chosen.priorAmount()));
            if (mismatch) {
                log.warn("당기순이익 중복 행의 값이 서로 다릅니다: {}", context);
            }
        }
        return chosen;
    }

    private static Optional<StoredFinancialLine> findLine(List<StoredFinancialLine> lines, String accountName) {
        return lines.stream().filter(line -> accountName.equals(line.accountName())).findFirst();
    }

    private static MetricValue toMetric(Optional<StoredFinancialLine> line) {
        return line.map(l -> new MetricValue(l.currentAmount(), l.priorAmount())).orElse(MetricValue.EMPTY);
    }

    private static BigDecimal cumulative(Optional<StoredFinancialLine> line) {
        return line.map(StoredFinancialLine::currentCumulativeAmount).orElse(null);
    }

    private static boolean amountsEqual(BigDecimal a, BigDecimal b) {
        if (a == null || b == null) {
            return a == b;
        }
        return a.compareTo(b) == 0;
    }

    record MappedAccounts(
            FinancialFormat format,
            MetricValue revenue,
            MetricValue operatingIncome,
            MetricValue netIncome,
            MetricValue totalAssets,
            MetricValue totalLiabilities,
            MetricValue totalEquity,
            MetricValue capitalStock) {
    }

    /** 3분기 보고서의 당기누적값(4분기 파생용). */
    record CumulativeAccounts(BigDecimal revenue, BigDecimal operatingIncome, BigDecimal netIncome) {
    }
}
