package org.stockinsight.analysis;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 재무 쉬운 설명 동기화 실행 시점. @Scheduled는 app.scheduler.enabled가 켜져 있을 때만 동작한다. */
@Component
class FinancialExplainScheduler {

    private final FinancialExplainJob job;
    private final FinancialExplainProperties properties;

    FinancialExplainScheduler(FinancialExplainJob job, FinancialExplainProperties properties) {
        this.job = job;
        this.properties = properties;
    }

    @Scheduled(cron = "${app.analysis.financial-explain.cron}", zone = "Asia/Seoul")
    void scheduled() {
        job.run();
    }

    @EventListener(ApplicationReadyEvent.class)
    void runOnStartup() {
        if (properties.runOnStartup()) {
            Thread.ofVirtual().name("financial-explain-startup").start(job::run);
        }
    }
}
