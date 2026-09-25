package org.stockinsight.financial;

/** 정기공시 보고서명에서 재무 수집 조회 키를 계산할 수 없다 (결산월 없음·불일치). */
public class PeriodicReportNameException extends RuntimeException {

    public PeriodicReportNameException(String message) {
        super(message);
    }
}
