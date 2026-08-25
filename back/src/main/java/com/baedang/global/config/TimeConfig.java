package com.baedang.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class TimeConfig {

    /** 현재 시각이 필요한 로직을 테스트에서 고정할 수 있도록 Clock을 주입합니다. */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
