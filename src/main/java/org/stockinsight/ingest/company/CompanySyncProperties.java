package org.stockinsight.ingest.company;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 기업 목록 동기화 설정.
 *
 * @param runOnStartup     앱 기동 직후 한 번 실행한다 (초기 적재·수동 확인용)
 * @param maxCallsPerRun   한 번 실행에서 쓸 OpenDART 호출 수 상한. 하루 한도(20,000건)를 다른 수집과 나눠 쓴다
 * @param refreshAfter     변경이 없어도 이 기간이 지나면 기업개황을 다시 받는다 (코넥스 → 코스닥 이전상장 등 감지)
 * @param minListedCount   고유번호 파일의 상장사 수가 이보다 적으면 비정상 응답으로 보고 중단한다
 * @param maxDelistedRatio 한 번에 상장폐지로 바뀌는 비율 상한. 넘으면 비정상 응답으로 보고 중단한다
 */
@ConfigurationProperties("app.ingest.company-sync")
public record CompanySyncProperties(
        String cron,
        boolean runOnStartup,
        int maxCallsPerRun,
        Duration refreshAfter,
        int minListedCount,
        double maxDelistedRatio) {
}
