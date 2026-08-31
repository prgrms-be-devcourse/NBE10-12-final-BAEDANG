package com.baedang.global.config;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import static org.assertj.core.api.Assertions.assertThat;

class SchedulingConfigTest {

    @Test
    void 일봉_작업_Executor는_단일_스레드로_구성된다() {
        ThreadPoolTaskExecutor executor = new SchedulingConfig().dailyCandleTaskExecutor();
        executor.initialize();
        try {
            assertThat(executor.getCorePoolSize()).isEqualTo(1);
            assertThat(executor.getMaxPoolSize()).isEqualTo(1);
            assertThat(executor.getThreadNamePrefix()).isEqualTo("daily-candle-");
        } finally {
            executor.shutdown();
        }
    }
}
