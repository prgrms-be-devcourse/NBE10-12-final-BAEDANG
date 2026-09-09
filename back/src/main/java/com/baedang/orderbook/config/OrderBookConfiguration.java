package com.baedang.orderbook.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(OrderBookProperties.class)
public class OrderBookConfiguration {
}
