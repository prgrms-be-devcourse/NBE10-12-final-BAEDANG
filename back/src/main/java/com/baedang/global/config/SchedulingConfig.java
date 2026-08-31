package com.baedang.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * {@code @Scheduled} 기반 배치를 켠다.
 *
 * <p>배치 자체의 실행 여부는 각 {@code @Scheduled} 컴포넌트가
 * {@code toss.enabled} 등으로 개별 제어한다. 이 클래스는 스케줄링 인프라만 켠다.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {

    /**
     * 일봉 정기 수집을 별도 스레드에서 실행해 스케줄러 스레드 점유를 방지한다.
     */
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
