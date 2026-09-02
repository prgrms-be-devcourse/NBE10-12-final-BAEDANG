package com.baedang.global.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * CORS 허용. 프론트(Next.js)가 백엔드를 다른 오리진에서 호출하므로 필요합니다.
 *
 * <p>허용 오리진은 {@code cors.allowed-origins}(콤마로 여러 개 지정 가능)로 관리합니다 —
 * 로컬 개발 오리진(localhost:3000)이 기본값이고, 배포 환경이 늘어나면 코드를 고치지 않고
 * {@code .env}의 {@code CORS_ALLOWED_ORIGINS}만 채우면 됩니다.
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    private final String[] allowedOrigins;

    public CorsConfig(@Value("${cors.allowed-origins}") String[] allowedOrigins) {
        this.allowedOrigins = allowedOrigins;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(allowedOrigins)
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true);
    }
}
