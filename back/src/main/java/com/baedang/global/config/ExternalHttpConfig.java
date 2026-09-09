package com.baedang.global.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import java.net.http.HttpClient;
import java.time.Duration;

@Configuration
public class ExternalHttpConfig {
    /** 외부 응답이 없더라도 제한된 수집 슬롯이 영구 점유되지 않게 합니다. */
    @Bean
    public RestClientCustomizer boundedExternalHttp(
            @Value("${toss.connect-timeout:2s}") Duration connectTimeout,
            @Value("${toss.read-timeout:5s}") Duration readTimeout) {
        if (connectTimeout.isNegative() || connectTimeout.isZero()
                || readTimeout.isNegative() || readTimeout.isZero()) {
            throw new IllegalArgumentException("외부 HTTP timeout은 양수여야 합니다");
        }
        return builder -> {
            JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                    HttpClient.newBuilder().connectTimeout(connectTimeout).build());
            factory.setReadTimeout(readTimeout);
            builder.requestFactory(factory);
        };
    }
}
