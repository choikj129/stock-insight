package org.stockinsight.disclosure;

/**
 * 정기공시 기준 재무 수집 계기. 같은 기업·기본 보고서명(원 공시·정정 모두)의 가장 큰 공시번호다.
 */
public record PeriodicTrigger(long companyId, String baseReportName, String receiptNo) {
}
