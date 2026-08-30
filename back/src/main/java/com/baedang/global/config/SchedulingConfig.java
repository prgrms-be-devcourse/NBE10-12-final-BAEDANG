package com.baedang.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 스프링 @Scheduled 스케줄러 활성화 설정.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {

    /** 일봉 정기 수집을 별도 스레드에서 실행해 스케줄러 스레드 점유를 방지합니다. */
    @Bean(name = "dailyCandleTaskExecutor")
    public ThreadPoolTaskExecutor dailyCandleTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(10);
        executor.setThreadNamePrefix("daily-candle-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        return executor;
    }
}