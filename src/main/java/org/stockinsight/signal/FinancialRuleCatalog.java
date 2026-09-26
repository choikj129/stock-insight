package org.stockinsight.signal;

import java.math.BigDecimal;

import org.stockinsight.financial.FinancialRatios;

/**
 * 재무 신호 판정 문턱값·심각도 구간 (D-35, ai-analysis.md §3.7). 초기값의 근거는 2026-09-25 로컬 데이터 분포다
 * (implementation-plan.md §6.1·§6.3). 값을 바꾸면 {@link #RULE_VERSION}도 올린다.
 * 매출 기준값은 재무 쉬운 설명(`analysis`)의 사실표 계산과 같은 값을 쓰도록 {@link FinancialRatios}를 참조한다(한 곳에서 정의, implementation-plan.md §7.2).
 */
public final class FinancialRuleCatalog {

    /** 규칙 버전. 바뀌면 모든 기업을 다시 판정한다. {@code analysis} 패키지가 입력 신호의 규칙 버전으로 참조한다. */
    public static final String RULE_VERSION = "fin-2";

    /** 매출 비교 기준값: 분기 10억, 연간 40억 (미만이면 증가율을 계산하지 않는다). */
    static final BigDecimal REVENUE_BASE_QUARTER = FinancialRatios.REVENUE_BASE_QUARTER;
    static final BigDecimal REVENUE_BASE_ANNUAL = FinancialRatios.REVENUE_BASE_ANNUAL;

    /** 매출 증가율 문턱값(%). */
    static final BigDecimal REVENUE_UP_THRESHOLD = new BigDecimal("30");
    static final BigDecimal REVENUE_UP_MEDIUM = new BigDecimal("50");
    static final BigDecimal REVENUE_UP_HIGH = new BigDecimal("100");
    static final BigDecimal REVENUE_DOWN_THRESHOLD = new BigDecimal("-20");
    static final BigDecimal REVENUE_DOWN_MEDIUM = new BigDecimal("-30");
    static final BigDecimal REVENUE_DOWN_HIGH = new BigDecimal("-50");

    /** 영업이익률 전년 동기 대비 차이(%p) 문턱값. */
    static final BigDecimal MARGIN_DIFF_THRESHOLD = new BigDecimal("10");
    static final BigDecimal MARGIN_DIFF_MEDIUM = new BigDecimal("20");
    static final BigDecimal MARGIN_DIFF_HIGH = new BigDecimal("30");

    /** 흑자·적자 전환 판정에 쓰는 영업이익률 절댓값 기준(%). */
    static final BigDecimal TURN_MARGIN_THRESHOLD = new BigDecimal("2");
    /** 적자 전환 뒤 이 이하(%)면 심각도 높음. */
    static final BigDecimal TURN_SEVERE_LOSS_MARGIN = new BigDecimal("-10");

    /** 부채비율 급등: 기간 말 이 값(%) 이상. */
    static final BigDecimal DEBT_RATIO_LEVEL = new BigDecimal("200");
    /** 부채비율 급등: 전기말 대비 증가(%p) 이 값 이상. */
    static final BigDecimal DEBT_RATIO_JUMP = new BigDecimal("50");
    static final BigDecimal DEBT_RATIO_LEVEL_HIGH = new BigDecimal("400");
    static final BigDecimal DEBT_RATIO_JUMP_HIGH = new BigDecimal("100");

    /** 영업적자 지속: 이 분기 수 이상이면 신호, 이 값 이상이면 심각도 높음. */
    static final int LOSS_STREAK_MIN_QUARTERS = 4;
    static final int LOSS_STREAK_HIGH_QUARTERS = 8;

    /** 자본잠식률(부분) 이 값(%) 이상이면 심각도 높음. */
    static final BigDecimal IMPAIRMENT_RATIO_HIGH = new BigDecimal("50");

    /** 데이터 이력이 이 분기 수 미만이면 "재무 이력 부족". */
    static final int MIN_HISTORY_QUARTERS = 4;

    /**
     * "최신 재무 미확인"(FIN_DATA_STALE) 판정 기한(D-43). 다음 기간 종료월의 말일에서 센다(달력일). 상장사에
     * 적용되는 가장 긴 법정 기한을 모든 기업에 쓴다. 서비스 내부 데이터 품질 판정 기준이며 실제 법정 제출기한
     * 자체가 아니다(60일·120일은 여유를 둔 근사치, 유예 7일은 휴일 순연과 수집 시차 흡수용).
     */
    static final int QUARTERLY_DEADLINE_DAYS = 60;
    static final int ANNUAL_DEADLINE_DAYS = 120;
    static final int STALE_GRACE_DAYS = 7;

    private FinancialRuleCatalog() {
    }
}
