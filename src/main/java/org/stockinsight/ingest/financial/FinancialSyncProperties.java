package org.stockinsight.ingest.financial;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 재무 수집 설정.
 *
 * @param cron             실행 시각. 공시 목록 수집(06:00) 뒤 하루 한 번
 * @param runOnStartup     앱 기동 직후 한 번 실행한다 (초기 적재·수동 확인용)
 * @param maxCallsPerRun   한 번 실행에서 쓸 OpenDART 호출 수 상한
 * @param initialLoadYears 초기 적재 대상 연도 수. 올해 포함 이 값+1개 bsns_year를 받는다
 */
@ConfigurationProperties("app.ingest.financial-sync")
public record FinancialSyncProperties(
        String cron,
        boolean runOnStartup,
        int maxCallsPerRun,
        int initialLoadYears) {
}
