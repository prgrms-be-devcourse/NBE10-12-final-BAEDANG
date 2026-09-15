package com.baedang.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * {@code @Async} 기반 백그라운드 실행 인프라를 구성한다.
 *
 * <p>SchedulingConfig가 {@code @Scheduled} 배치 전용 실행기를 모아두는 것과 같은
 * 이유로, {@code @Async} 전용 실행기는 이 클래스에 따로 둔다.
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    /**
     * 비밀번호 재설정 메일 발송(PasswordResetMailSender) 전용 실행기.
     *
     * <p>가입 여부에 따른 응답 시간 차이(타이밍 사이드채널, PR #207 리뷰 지적)를
     * 줄이려고 SMTP 왕복을 요청 스레드에서 떼어낸다. 요청량이 많지 않은 기능이라
     * 단일 스레드와 유한 큐로 처리합니다. 포화·종료 시 등록 실패는 AuthService가
     * 커밋 후 기록하며, 요청 스레드에서 SMTP를 실행하거나 API 오류로 전파하지 않습니다.
     */
    @Bean(name = "passwordResetMailExecutor")
    public ThreadPoolTaskExecutor passwordResetMailExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("password-reset-mail-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        return executor;
    }
}
