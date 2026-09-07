package com.baedang.global.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Swagger(springdoc-openapi) 문서 설정.
 *
 * <p>Swagger UI: {@code /swagger-ui/index.html}, OpenAPI 스펙: {@code /v3/api-docs}.
 * 두 경로 모두 {@code SecurityConfig}의 {@code anyRequest().permitAll()}에 걸려 인증 없이 열린다.
 *
 * <p>인증이 필요한 엔드포인트(주문·계좌·마이페이지 등)를 UI에서 시험할 수 있도록 JWT bearer
 * 보안 스킴을 등록한다 — 우상단 "Authorize"에 access token을 넣으면 Authorization 헤더가 붙는다.
 */
@Configuration
public class OpenApiConfig {

    private static final String BEARER_SCHEME = "bearerAuth";

    @Bean
    public OpenAPI baedangOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("BAEDANG Mock Stock Trading API")
                        .description("토스증권 Open API 기반 모의 주식 트레이딩 서비스 API 문서")
                        .version("v0.0.1"))
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME))
                .components(new Components().addSecuritySchemes(BEARER_SCHEME,
                        new SecurityScheme()
                                .name(BEARER_SCHEME)
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")));
    }
}
