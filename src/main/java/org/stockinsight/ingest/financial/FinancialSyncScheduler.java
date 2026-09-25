package org.stockinsight.ingest.financial;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 재무 수집 실행 시점. @Scheduled는 app.scheduler.enabled가 켜져 있을 때만 동작한다. */
@Component
class FinancialSyncScheduler {

    private final FinancialSyncJob job;
    private final FinancialSyncProperties properties;

    FinancialSyncScheduler(FinancialSyncJob job, FinancialSyncProperties properties) {
        this.job = job;
        this.properties = properties;
    }

    @Scheduled(cron = "${app.ingest.financial-sync.cron}", zone = "Asia/Seoul")
    void scheduled() {
        job.run();
    }

    @EventListener(ApplicationReadyEvent.class)
    void runOnStartup() {
        if (properties.runOnStartup()) {
            Thread.ofVirtual().name("financial-sync-startup").start(job::run);
        }
    }
}
