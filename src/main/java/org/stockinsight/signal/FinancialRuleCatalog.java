package org.stockinsight.signal;

import java.math.BigDecimal;

/**
 * 재무 신호 판정 문턱값·심각도 구간 (D-35, ai-analysis.md §3.7). 초기값의 근거는 2026-09-25 로컬 데이터 분포다
 * (implementation-plan.md §6.1·§6.3). 값을 바꾸면 {@link #RULE_VERSION}도 올린다.
 */
final class FinancialRuleCatalog {

    /** 규칙 버전. 바뀌면 모든 기업을 다시 판정한다. */
    static final String RULE_VERSION = "fin-1";

    /** 매출 비교 기준값: 분기 10억, 연간 40억 (미만이면 증가율을 계산하지 않는다). */
    static final BigDecimal REVENUE_BASE_QUARTER = new BigDecimal("1000000000");
    static final BigDecimal REVENUE_BASE_ANNUAL = new BigDecimal("4000000000");

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

    /** 재무상태표 항등식 허용 오차(비율). */
    static final BigDecimal BALANCE_TOLERANCE = new BigDecimal("0.005");

    /** 다음 보고서 제출 기한: 분기·반기 45일, 사업보고서 90일. 이 값에 +7일을 더해 "미확인" 기준으로 쓴다. */
    static final int QUARTERLY_DEADLINE_DAYS = 45;
    static final int ANNUAL_DEADLINE_DAYS = 90;
    static final int STALE_GRACE_DAYS = 7;

    private FinancialRuleCatalog() {
    }
}
