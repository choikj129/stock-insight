package org.stockinsight.signal;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 재무 신호 계산 설정.
 *
 * @param cron         실행 시각. 재무 수집(06:30) 뒤 하루 한 번
 * @param runOnStartup 앱 기동 직후 한 번 실행한다 (수동 확인용)
 */
@ConfigurationProperties("app.signal.financial-signal")
public record FinancialSignalProperties(String cron, boolean runOnStartup) {
}
