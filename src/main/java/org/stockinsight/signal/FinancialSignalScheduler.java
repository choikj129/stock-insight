package org.stockinsight.signal;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 재무 신호 계산 실행 시점. @Scheduled는 app.scheduler.enabled가 켜져 있을 때만 동작한다. */
@Component
class FinancialSignalScheduler {

    private final FinancialSignalJob job;
    private final FinancialSignalProperties properties;

    FinancialSignalScheduler(FinancialSignalJob job, FinancialSignalProperties properties) {
        this.job = job;
        this.properties = properties;
    }

    @Scheduled(cron = "${app.signal.financial-signal.cron}", zone = "Asia/Seoul")
    void scheduled() {
        job.run();
    }

    @EventListener(ApplicationReadyEvent.class)
    void runOnStartup() {
        if (properties.runOnStartup()) {
            Thread.ofVirtual().name("financial-signal-startup").start(job::run);
        }
    }
}
