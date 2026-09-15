package com.baedang.global.config;

import org.junit.jupiter.api.Test;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CorsConfigTest {

    // CorsRegistry#getCorsConfigurations()는 protected라 다른 패키지의 테스트에서
    // 바로 부를 수 없다 — 상속으로 열어서 등록된 설정을 검증한다.
    private static class ExposedCorsRegistry extends CorsRegistry {
        Map<String, CorsConfiguration> exposedConfigurations() {
            return getCorsConfigurations();
        }
    }

    @Test
    void 설정값으로_받은_오리진을_그대로_허용한다() {
        CorsConfig config = new CorsConfig(new String[]{"http://localhost:3000"});
        ExposedCorsRegistry registry = new ExposedCorsRegistry();

        config.addCorsMappings(registry);

        CorsConfiguration corsConfiguration = registry.exposedConfigurations().get("/api/**");
        assertThat(corsConfiguration.getAllowedOrigins()).containsExactly("http://localhost:3000");
    }

    @Test
    void 콤마로_구분된_여러_오리진을_전부_허용한다() {
        // cors.allowed-origins: ${CORS_ALLOWED_ORIGINS:http://localhost:3000} 는 Spring이
        // 콤마 구분 문자열을 String[]로 바인딩해준다 — 배포 환경이 늘어나면 .env에 콤마로
        // 추가하는 방식이 실제로 동작하는지 확인한다.
        CorsConfig config = new CorsConfig(new String[]{"http://localhost:3000", "https://baedang.example.com"});
        ExposedCorsRegistry registry = new ExposedCorsRegistry();

        config.addCorsMappings(registry);

        CorsConfiguration corsConfiguration = registry.exposedConfigurations().get("/api/**");
        assertThat(corsConfiguration.getAllowedOrigins())
                .containsExactly("http://localhost:3000", "https://baedang.example.com");
    }
}
