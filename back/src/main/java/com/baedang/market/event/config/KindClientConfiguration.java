package com.baedang.market.event.config;

import com.baedang.market.event.client.kind.KindHttpClient;
import com.baedang.market.event.client.kind.KindUriPolicy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(KrxMarketEventProperties.class)
public class KindClientConfiguration {

    @Bean
    public KindUriPolicy kindUriPolicy(KrxMarketEventProperties properties) {
        return new KindUriPolicy(properties.baseUrl());
    }

    @Bean
    @ConditionalOnProperty(prefix = "krx.market-events", name = "enabled", havingValue = "true")
    public KindHttpClient kindHttpClient(KrxMarketEventProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(properties.readTimeout());
        RestClient restClient = RestClient.builder()
                .baseUrl(properties.baseUrl().toString())
                .requestFactory(factory)
                .build();
        return new KindHttpClient(restClient);
    }
}
