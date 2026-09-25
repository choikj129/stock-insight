package org.stockinsight.ingest.disclosure;

import java.time.Period;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 공시 목록 수집 설정.
 *
 * @param cron              전체 실행: 당일 + 확정되지 않은 날짜 + 초기 적재 남은 날짜. 기업 목록 동기화 뒤에 돈다
 * @param intradayCron      장중 실행: 당일분만 읽는다
 * @param runOnStartup      앱 기동 직후 전체 실행을 한 번 한다 (초기 적재·수동 확인용)
 * @param maxCallsPerRun    한 번 실행에서 쓸 OpenDART 호출 수 상한. 하루 한도(20,000건)를 다른 수집과 나눠 쓴다
 * @param initialLoadPeriod 오늘부터 거슬러 올라가 받을 기간. 늘리면 늘어난 기간만 이어서 받는다
 */
@ConfigurationProperties("app.ingest.disclosure-sync")
public record DisclosureSyncProperties(
        String cron,
        String intradayCron,
        boolean runOnStartup,
        int maxCallsPerRun,
        Period initialLoadPeriod) {
}
