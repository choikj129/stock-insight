package org.stockinsight.ingest.company;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 기업 목록 동기화 실행 시점. @Scheduled는 app.scheduler.enabled가 켜져 있을 때만 동작한다. */
@Component
class CompanySyncScheduler {

    private final CompanySyncJob job;
    private final CompanySyncProperties properties;

    CompanySyncScheduler(CompanySyncJob job, CompanySyncProperties properties) {
        this.job = job;
        this.properties = properties;
    }

    @Scheduled(cron = "${app.ingest.company-sync.cron}", zone = "Asia/Seoul")
    void scheduled() {
        job.run();
    }

    @EventListener(ApplicationReadyEvent.class)
    void runOnStartup() {
        if (properties.runOnStartup()) {
            Thread.ofVirtual().name("company-sync-startup").start(job::run);
        }
    }
}
