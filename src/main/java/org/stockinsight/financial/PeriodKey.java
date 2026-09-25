package org.stockinsight.financial;

import java.time.LocalDate;

/**
 * 분기 하나의 식별자: 회계연도 시작일 + 분기 번호(1~4, 비12월 결산도 회계연도 시작일 기준으로 센다, ai-analysis.md §3.6).
 * 분기 번호와 실제 보고서 종류(1분기·반기·3분기·사업보고서)의 대응은 {@link PeriodType}과 다음처럼 연결된다:
 * 1분기→Q1, 2분기→반기(H1), 3분기→Q3, 4분기→사업보고서(FY, 연간 − 3분기 누적의 파생값).
 */
public record PeriodKey(LocalDate fiscalYearStart, int quarterNumber) {

    public PeriodKey {
        if (quarterNumber < 1 || quarterNumber > 4) {
            throw new IllegalArgumentException("분기 번호는 1~4여야 합니다: " + quarterNumber);
        }
    }

    /** 변화 신호의 근거 키(회계연도 시작일:기간 구분). 분기 보고서가 실제로 있는 유형에만 쓴다. */
    public String flowBasisKey(PeriodType reportType) {
        return fiscalYearStart + ":" + reportType.name();
    }

    /** 상태 신호(지속)의 근거 키. 파생 4분기도 포함해 분기 단위로 추적한다. */
    public String stateBasisKey() {
        return fiscalYearStart + ":Q" + quarterNumber;
    }

    /** 부채비율 급등의 근거 키. 같은 회계연도 안에서는 한 건이다. */
    public String debtJumpBasisKey() {
        return fiscalYearStart + ":FYDEBT";
    }

    /** 화면·AI 자리표시자의 기간 키(ai-analysis.md §3.8): 회계연도 시작 연월.분기. */
    public String displayKey() {
        return "%d-%02d.Q%d".formatted(fiscalYearStart.getYear(), fiscalYearStart.getMonthValue(), quarterNumber);
    }
}
