package org.stockinsight.disclosure;

import java.time.LocalDate;

/**
 * 저장된 공시.
 *
 * @param receiptNo         공시번호(접수번호, 14자리). 공시의 자연키이자 근거 키다
 * @param receivedOn        접수일. 공시의 기준일이다
 * @param remark            OpenDART 비고 원문. 마지막으로 읽은 시점의 값이다
 * @param originalReceiptNo 정정 제출이면 코드가 연결한 원 공시의 공시번호. 원 공시가 수집 범위 밖이면 null
 */
public record Disclosure(
        String receiptNo,
        long companyId,
        DisclosureType type,
        String reportName,
        String baseReportName,
        String amendmentLabel,
        LocalDate receivedOn,
        String filerName,
        String remark,
        String originalReceiptNo) {
}
