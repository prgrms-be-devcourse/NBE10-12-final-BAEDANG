package com.baedang.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.auditing.DateTimeProvider;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

/**
 * JPA Auditing 활성화.
 *
 * <p>이게 없으면 {@link com.baedang.global.entity.BaseEntity} 의
 * {@code @CreatedDate} 가 동작하지 않아 {@code created_at} 이 null 로 들어갑니다.
 * NOT NULL 제약에 걸려 INSERT 가 실패하는데, 원인이 잘 안 보이는 종류의 오류입니다.
 *
 * <p>{@code dateTimeProvider} 를 직접 지정한 이유: 기본 제공자는
 * {@code LocalDateTime} 을 주는데 우리 엔티티는 {@code OffsetDateTime} 입니다.
 * 타입이 안 맞으면 auditing 이 조용히 값을 안 넣습니다.
 * <b>UTC 로 고정</b>해서 넣고, 표시할 때만 KST 로 바꿉니다.
 */
@Configuration
@EnableJpaAuditing(dateTimeProviderRef = "auditingDateTimeProvider")
public class JpaConfig {

    @Bean
    public DateTimeProvider auditingDateTimeProvider() {
        return () -> Optional.of(OffsetDateTime.now(ZoneOffset.UTC));
    }
}
