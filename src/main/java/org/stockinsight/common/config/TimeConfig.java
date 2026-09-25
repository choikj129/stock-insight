package org.stockinsight.common.config;

import java.time.Clock;
import java.time.ZoneId;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class TimeConfig {

    /** 스케줄과 날짜 판단의 기준 시간대 (docs/architecture.md §4.3). */
    public static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
