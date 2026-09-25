package org.stockinsight.financial;

import java.time.LocalDate;
import java.util.List;

/**
 * 기업 하나의 재무 요약(ai-analysis.md §3.8). 시계열 기준(연결/별도)·통화는 최신 기간 기준으로 고정한다(D-37).
 * 신호 계산과 (향후) 화면·AI 입력이 이 구조를 함께 쓴다. 신호 자체는 포함하지 않는다(signal 패키지가 이 요약으로 계산한다).
 *
 * @param basis            CFS 또는 OFS. 최신 기간에 연결이 있으면 연결
 * @param quarters         최근 12분기, 최신이 먼저(내림차순)
 * @param annual           최근 3개 사업연도, 최신이 먼저
 * @param hasAnyReport      이 기업에 재무 보고서가 하나도 없으면 false(나머지 필드는 비어 있다)
 */
public record FinancialSummary(
        long companyId,
        boolean hasAnyReport,
        String basis,
        String currency,
        LocalDate latestPeriodEnd,
        String latestReceiptNo,
        List<SummaryFlag> flags,
        List<QuarterEntry> quarters,
        List<AnnualEntry> annual) {
}
