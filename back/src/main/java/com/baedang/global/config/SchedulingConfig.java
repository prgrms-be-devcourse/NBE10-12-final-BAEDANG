package com.baedang.global.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * {@code @Scheduled} 기반 배치를 켠다 — 지금은 상위 100종목 1분봉 수집
 * ({@code com.baedang.stock.scheduler.MinuteCandleCollectionScheduler})이 유일한
 * 사용처다. 배치 자체의 실행 여부는 각 {@code @Scheduled} 컴포넌트가
 * {@code toss.enabled} 등으로 개별 제어한다 — 이 클래스는 스케줄링 인프라만 켠다.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
