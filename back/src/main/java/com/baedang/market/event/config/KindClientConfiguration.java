package com.baedang.market.event.config;

import com.baedang.market.event.client.kind.KindHttpClient;
import com.baedang.market.event.client.kind.KindMarketEventAdapter;
import com.baedang.market.event.client.kind.KindMarketEventDetailParser;
import com.baedang.market.event.client.kind.KindRssParser;
import com.baedang.market.event.client.kind.KindUriPolicy;
import com.baedang.market.event.client.kind.KindViewerParser;
import com.baedang.market.event.port.MarketEventSourcePort;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Clock;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(KrxMarketEventProperties.class)
public class KindClientConfiguration {

    @Bean
    public KindUriPolicy kindUriPolicy(KrxMarketEventProperties properties) {
        return new KindUriPolicy(properties.baseUrl());
    }

    @Bean
    @ConditionalOnProperty(prefix = "krx.market-events", name = "enabled", havingValue = "true")
    public RestClient kindRestClient(KrxMarketEventProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(properties.readTimeout());
        return RestClient.builder()
                .baseUrl(properties.baseUrl().toString())
                .requestFactory(factory)
                .build();
    }

    @Bean
    @ConditionalOnProperty(prefix = "krx.market-events", name = "enabled", havingValue = "true")
    public KindHttpClient kindHttpClient(@Qualifier("kindRestClient") RestClient restClient) {
        return new KindHttpClient(restClient);
    }

    @Bean
    public KindRssParser kindRssParser(KindUriPolicy uriPolicy) {
        return new KindRssParser(uriPolicy);
    }

    @Bean
    public KindViewerParser kindViewerParser(KindUriPolicy uriPolicy) {
        return new KindViewerParser(uriPolicy);
    }

    @Bean
    public KindMarketEventDetailParser kindMarketEventDetailParser(KindUriPolicy uriPolicy) {
        return new KindMarketEventDetailParser(uriPolicy);
    }

    @Bean
    @ConditionalOnProperty(prefix = "krx.market-events", name = "enabled", havingValue = "true")
    public MarketEventSourcePort kindMarketEventAdapter(
            KindHttpClient httpClient,
            KindUriPolicy uriPolicy,
            KindRssParser rssParser,
            KindViewerParser viewerParser,
            KindMarketEventDetailParser detailParser,
            Clock clock
    ) {
        return new KindMarketEventAdapter(
                httpClient,
                uriPolicy,
                rssParser,
                viewerParser,
                detailParser,
                clock
        );
    }
}
