package com.baedang.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 애플리케이션의 {@code @Scheduled} 기반 배치 실행 인프라를 구성한다.
 *
 * <p>이 설정은 스케줄링 기능과 공용 실행기만 제공한다. 각 배치의 활성화 여부는 해당
 * 스케줄러가 {@code toss.enabled} 등의 조건으로 개별 제어한다.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {

    /**
     * 일봉 수집의 외부 API 호출과 DB I/O를 스케줄러 스레드에서 분리한다.
     *
     * <p>단일 실행 스레드로 KR·US 수집 작업을 직렬화해 동일 인스턴스에서 수집이 겹치지 않게
     * 하고, 실행 중 추가된 트리거는 최대 10개까지 대기시킨다. 애플리케이션 종료 시에는 진행
     * 중인 작업이 마무리되도록 최대 30초간 기다린다.
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
