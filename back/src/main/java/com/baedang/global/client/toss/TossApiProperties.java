package com.baedang.global.client.toss;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code application.yaml} 의 {@code toss.*} 설정 바인딩.
 *
 * <p>{@code enabled=false} (기본값) 일 때는 {@link TossSecuritiesClient} 와
 * 실제 Toss 어댑터들이 아예 빈으로 등록되지 않고, 각 도메인의 Fake 구현체가
 * 대신 등록됩니다 — 토스 키가 없는 팀원도 앱을 정상적으로 띄울 수 있습니다.
 * ({@code toss.enabled} 를 참조하는 모든 {@code @ConditionalOnProperty} 는
 * 이 클래스가 아니라 각 어댑터/클라이언트 쪽에 붙습니다.)
 */
@ConfigurationProperties(prefix = "toss")
public record TossApiProperties(
        String baseUrl,
        String clientId,
        String clientSecret,
        boolean enabled
) {
}
