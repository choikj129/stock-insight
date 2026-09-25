package org.stockinsight.ingest.disclosure;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 공시 목록 수집 실행 시점. @Scheduled는 app.scheduler.enabled가 켜져 있을 때만 동작한다. */
@Component
class DisclosureSyncScheduler {

    private final DisclosureSyncJob job;
    private final DisclosureSyncProperties properties;

    DisclosureSyncScheduler(DisclosureSyncJob job, DisclosureSyncProperties properties) {
        this.job = job;
        this.properties = properties;
    }

    @Scheduled(cron = "${app.ingest.disclosure-sync.cron}", zone = "Asia/Seoul")
    void scheduled() {
        job.run();
    }

    @Scheduled(cron = "${app.ingest.disclosure-sync.intraday-cron}", zone = "Asia/Seoul")
    void intraday() {
        job.runToday();
    }

    @EventListener(ApplicationReadyEvent.class)
    void runOnStartup() {
        if (properties.runOnStartup()) {
            Thread.ofVirtual().name("disclosure-sync-startup").start(job::run);
        }
    }
}
