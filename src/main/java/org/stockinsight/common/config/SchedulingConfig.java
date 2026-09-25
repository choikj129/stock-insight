package org.stockinsight.common.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 스케줄러는 app.scheduler.enabled로 켜고 끈다. 서버가 여러 대면 한 대에서만 켠다 (docs/architecture.md §2.3). */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnBooleanProperty("app.scheduler.enabled")
public class SchedulingConfig {
}
