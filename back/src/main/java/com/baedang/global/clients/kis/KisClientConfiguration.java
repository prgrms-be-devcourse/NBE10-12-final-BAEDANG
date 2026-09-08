package com.baedang.global.clients.kis;

import java.time.Clock;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import com.baedang.global.clients.FixedIntervalGate;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(KisProperties.class)
public class KisClientConfiguration {

    @Bean
    RestClient kisRestClient(KisProperties properties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.connectTimeout());
        requestFactory.setReadTimeout(properties.readTimeout());
        return RestClient.builder()
                .baseUrl(properties.baseUrl().toString())
                .requestFactory(requestFactory)
                .build();
    }

    @Bean
    KisRateLimiter kisRateLimiter(
            KisProperties properties,
            ObjectProvider<MeterRegistry> meterRegistryProvider
    ) {
        MeterRegistry meterRegistry = meterRegistryProvider.getIfAvailable(SimpleMeterRegistry::new);
        return new KisRateLimiter(
                new FixedIntervalGate(properties.requestsPerSecond()), meterRegistry);
    }

    @Bean
    KisTokenProvider kisTokenProvider(RestClient restClient, KisProperties properties, Clock clock) {
        return new KisTokenProvider(restClient, properties, clock);
    }

    @Bean
    KisSecuritiesClient kisSecuritiesClient(
            RestClient restClient,
            KisProperties properties,
            KisRateLimiter rateLimiter,
            KisTokenProvider tokenProvider,
            ObjectProvider<ObjectMapper> objectMapperProvider
    ) {
        ObjectMapper objectMapper = objectMapperProvider.getIfAvailable(ObjectMapper::new);
        return new KisSecuritiesClient(
                restClient, properties, rateLimiter, tokenProvider, objectMapper);
    }
}
