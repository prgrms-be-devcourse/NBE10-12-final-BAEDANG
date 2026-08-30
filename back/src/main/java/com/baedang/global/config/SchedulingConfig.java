package com.baedang.global.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * {@code @Scheduled} 어노테이션을 활성화합니다.
 *
 * <p>스케줄러 빈은 각자 {@code @ConditionalOnProperty} 로 조건부 등록합니다 —
 * 토스 키가 없는 팀원 로컬 환경에서 매분 외부 호출이 실패하는 상황을 막기 위해서입니다.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
