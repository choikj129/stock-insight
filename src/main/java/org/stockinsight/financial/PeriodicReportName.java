package org.stockinsight.financial;

import java.time.YearMonth;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 정기공시 보고서명(disclosure.base_report_name)에서 재무 수집 조회 키를 계산한다 (docs/implementation-plan.md §4.3).
 * <ul>
 *     <li>"사업보고서 (YYYY.MM)" → (YYYY, 사업보고서)</li>
 *     <li>"반기보고서 (YYYY.MM)" → (YYYY, 반기보고서)</li>
 *     <li>"분기보고서 (YYYY.MM)" → 결산월 기준으로 1분기·3분기 판별. 결산월이 없거나 어느 쪽도 아니면 오류</li>
 *     <li>그 밖의 정기공시(등록법인결산서류 등)는 대상이 아니다(빈 값)</li>
 * </ul>
 * bsns_year는 항상 보고서명의 연도(YYYY)를 그대로 쓴다. OpenDART의 bsns_year가 보고서 기간 종료 연도이기 때문이다(D-33).
 */
public final class PeriodicReportName {

    private static final Pattern PATTERN = Pattern.compile("^(사업보고서|반기보고서|분기보고서)\\s*\\((\\d{4})\\.(\\d{2})\\)$");

    private PeriodicReportName() {
    }

    public record QueryKey(int bsnsYear, PeriodType periodType, YearMonth periodEndMonth) {
    }

    /**
     * @param fiscalMonth 결산월(1~12). 분기보고서 해석에만 쓴다
     * @throws PeriodicReportNameException 분기보고서인데 결산월이 없거나 어느 분기 기간과도 맞지 않을 때
     */
    public static Optional<QueryKey> resolve(String baseReportName, Integer fiscalMonth) {
        Matcher matcher = PATTERN.matcher(baseReportName.strip());
        if (!matcher.matches()) {
            return Optional.empty();
        }
        String kind = matcher.group(1);
        int year = Integer.parseInt(matcher.group(2));
        int month = Integer.parseInt(matcher.group(3));
        YearMonth periodEndMonth = YearMonth.of(year, month);
        return switch (kind) {
            case "사업보고서" -> Optional.of(new QueryKey(year, PeriodType.FY, periodEndMonth));
            case "반기보고서" -> Optional.of(new QueryKey(year, PeriodType.H1, periodEndMonth));
            case "분기보고서" -> Optional.of(new QueryKey(year, quarterType(month, fiscalMonth, baseReportName), periodEndMonth));
            default -> Optional.empty();
        };
    }

    private static PeriodType quarterType(int periodEndMonth, Integer fiscalMonth, String baseReportName) {
        if (fiscalMonth == null) {
            throw new PeriodicReportNameException("결산월을 알 수 없어 분기를 판별할 수 없습니다: " + baseReportName);
        }
        if (periodEndMonth == monthsAfter(fiscalMonth, 3)) {
            return PeriodType.Q1;
        }
        if (periodEndMonth == monthsAfter(fiscalMonth, 9)) {
            return PeriodType.Q3;
        }
        throw new PeriodicReportNameException(
                "분기 보고서 기간이 결산월(%d월)과 맞지 않습니다: %s".formatted(fiscalMonth, baseReportName));
    }

    /** 결산월로부터 n개월 뒤의 월 (1~12). */
    private static int monthsAfter(int fiscalMonth, int n) {
        return ((fiscalMonth - 1 + n) % 12) + 1;
    }
}
