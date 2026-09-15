package com.baedang.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class TimeConfig {

    /**
     * 애플리케이션의 현재 시각 조회 방식을 일관되게 관리하기 위해 UTC Clock을 제공합니다.
     * 테스트에서는 고정된 Clock으로 교체하여 시간 의존 로직을 검증할 수 있습니다.
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
