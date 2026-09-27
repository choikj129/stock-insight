package org.stockinsight.financial;

import java.math.BigDecimal;
import java.util.List;
import java.util.function.Function;

/**
 * 같은 방향이 이어진 기간 수(ai-analysis.md §3.7 "지속", D-61). 신호의 지속 값과 재무 쉬운 설명의 흐름 사실이 이 한
 * 정의를 함께 쓴다 — 셈이 둘이면 화면·신호·AI 설명의 "몇 분기째"가 달라진다.
 * 부호는 전년 동기 대비 변화의 부호이고 문턱값과 무관하다. 부호가 없는 기간(비교할 수 없는 기간)이나 빈 기간에서 멈춘다.
 */
public final class FlowRuns {

    private FlowRuns() {
    }

    /** 분기 매출의 전년 동기 대비 변화 부호. 4분기 파생값처럼 비교하지 않는 기간은 null. */
    public static Integer revenueYoySign(QuarterEntry q) {
        return q.flowSignalEligible() ? revenueYoySign(q.revenue()) : null;
    }

    /** 분기 영업이익률의 전년 동기 대비 차이 부호. 어느 쪽 이익률이든 계산할 수 없으면 null. */
    public static Integer marginYoySign(QuarterEntry q) {
        return q.flowSignalEligible() ? marginYoySign(q.revenue(), q.operatingIncome()) : null;
    }

    public static Integer revenueYoySign(AnnualEntry a) {
        return revenueYoySign(a.revenue());
    }

    public static Integer marginYoySign(AnnualEntry a) {
        return marginYoySign(a.revenue(), a.operatingIncome());
    }

    /**
     * {@code quartersDesc[start]}부터 과거로 거슬러 올라가며 부호가 같은(0 제외) 연속 분기 수. 부호가 음수면 음수로
     * 돌려준다. 시작 분기에 부호가 없으면 0이다.
     */
    public static int signedQuarterRun(List<QuarterEntry> quartersDesc, int start, Function<QuarterEntry, Integer> signFn) {
        int count = 0;
        Integer sign = null;
        QuarterEntry later = null;
        for (int i = start; i < quartersDesc.size(); i++) {
            QuarterEntry q = quartersDesc.get(i);
            Integer s = signFn.apply(q);
            if (s == null || s == 0) {
                break;
            }
            if (later != null && !FinancialRatios.isConsecutiveQuarter(q.periodEnd(), later.periodEnd())) {
                break;
            }
            if (sign == null) {
                sign = s;
            } else if (!sign.equals(s)) {
                break;
            }
            count++;
            later = q;
        }
        return sign != null && sign < 0 ? -count : count;
    }

    /** {@link #signedQuarterRun}의 연간판. 앞 사업연도가 끝난 다음 날에 다음 사업연도가 시작해야 이어진 것으로 본다. */
    public static int signedAnnualRun(List<AnnualEntry> annualDesc, int start, Function<AnnualEntry, Integer> signFn) {
        int count = 0;
        Integer sign = null;
        AnnualEntry later = null;
        for (int i = start; i < annualDesc.size(); i++) {
            AnnualEntry a = annualDesc.get(i);
            Integer s = signFn.apply(a);
            if (s == null || s == 0) {
                break;
            }
            if (later != null && !a.periodEnd().plusDays(1).equals(later.fiscalYearStart())) {
                break;
            }
            if (sign == null) {
                sign = s;
            } else if (!sign.equals(s)) {
                break;
            }
            count++;
            later = a;
        }
        return sign != null && sign < 0 ? -count : count;
    }

    private static Integer revenueYoySign(MetricValue revenue) {
        return revenue.hasCurrent() && revenue.hasPrior() ? revenue.current().subtract(revenue.prior()).signum() : null;
    }

    private static Integer marginYoySign(MetricValue revenue, MetricValue operatingIncome) {
        BigDecimal current = FinancialRatios.margin(revenue.current(), operatingIncome.current());
        BigDecimal prior = FinancialRatios.margin(revenue.prior(), operatingIncome.prior());
        return current == null || prior == null ? null : current.subtract(prior).signum();
    }
}
