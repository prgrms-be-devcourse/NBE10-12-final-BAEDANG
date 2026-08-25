package com.baedang.global.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 로컬 개발용 CORS 허용. 프론트(Next.js, {@code localhost:3000})가 백엔드
 * ({@code localhost:8080})를 다른 오리진에서 호출하므로 필요합니다.
 *
 * <p>지금은 로컬 개발 오리진 하나만 허용합니다. 배포 환경이 생기면
 * 오리진을 하드코딩하지 말고 {@code application.yaml} 설정값으로 빼세요.
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins("http://localhost:3000")
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true);
    }
}
