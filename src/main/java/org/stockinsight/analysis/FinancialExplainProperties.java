package org.stockinsight.analysis;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 재무 쉬운 설명 동기화 설정(ai-analysis.md §6).
 *
 * @param cron               실행 시각. 재무 신호 계산(07:00) 뒤
 * @param runOnStartup       앱 기동 직후 한 번 실행한다 (수동 확인용)
 * @param goldenSetCompanyIds AI 적용 대상(company.ai_covered)에 더해 항상 포함할 기업 ID (§4.4.6 골든셋 목록)
 * @param dailyBudgetUsd     일일 AI 예산(USD). 모든 분석 종류가 공유한다(§6.2)
 * @param monthlyBudgetUsd   월간 AI 예산(USD). 모든 분석 종류가 공유한다(§6.2)
 * @param publish            검증을 통과한 결과를 게시본으로 승격할지. 골든셋 검토를 통과하기 전까지는 false로 두어
 *                           초안으로만 저장한다(D-39, §4.4.8)
 */
@ConfigurationProperties("app.analysis.financial-explain")
public record FinancialExplainProperties(
        String cron,
        boolean runOnStartup,
        List<Long> goldenSetCompanyIds,
        BigDecimal dailyBudgetUsd,
        BigDecimal monthlyBudgetUsd,
        boolean publish) {

    public FinancialExplainProperties {
        goldenSetCompanyIds = goldenSetCompanyIds == null ? List.of() : List.copyOf(goldenSetCompanyIds);
    }
}
