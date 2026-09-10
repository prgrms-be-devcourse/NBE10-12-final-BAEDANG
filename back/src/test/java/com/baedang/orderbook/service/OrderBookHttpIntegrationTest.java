package com.baedang.orderbook.service;

import com.baedang.market.entity.QuoteSnapshot;
import com.baedang.market.port.ExecutionExchangeRateProvider;
import com.baedang.market.port.MarketCalendarPort;
import com.baedang.market.port.MarketDataPort;
import com.baedang.market.port.MarketSessionProvider;
import com.baedang.market.port.MarketSessionStatus;
import com.baedang.market.repository.QuoteSnapshotRepository;
import com.baedang.orderbook.config.OrderBookProperties;
import com.baedang.orderbook.model.GeneratedOrderBook;
import com.baedang.orderbook.model.StockDescriptor;
import com.baedang.orderbook.repository.OrderBookVersionRepository;
import com.baedang.orderbook.support.MutableClock;
import com.baedang.stock.entity.MarketCountry;
import com.baedang.stock.entity.Stock;
import com.baedang.stock.repository.StockRepository;
import com.baedang.stock.service.StockTradingStatusService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.sql.init.mode=never",
        "toss.enabled=false",
        "trading.orderbook.enabled=true",
        "trading.orderbook.refresh-initial-delay=1h",
        "trading.orderbook.retention-initial-delay=1h",
        "JWT_SECRET=ZGV2LXNlY3JldC1rZXktZm9yLXRlc3Rpbmctb25seQ==",
        "logging.level.org.hibernate.SQL=OFF"
})
class OrderBookHttpIntegrationTest {
    @MockitoBean
    StockTradingStatusService tradingStatuses;

    @MockitoBean
    MarketDataPort currentPricePort;

    @BeforeEach
    void prepareTradingStatusBoundary() {
        Mockito.lenient().when(tradingStatuses.requireCurrent(ArgumentMatchers.any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        Mockito.lenient().when(tradingStatuses.refreshBatch(ArgumentMatchers.anyList()))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }


    private static final Instant BASE = Instant.parse("2026-09-03T01:00:00Z");
    private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb:latest-pg18")
                    .asCompatibleSubstituteFor("postgres"));

    @TestConfiguration
    static class ClockTestConfig {
        final MutableClock clock = new MutableClock(BASE);

        @Bean
        @Primary
        MutableClock mutableClock() {
            return clock;
        }
    }

    @MockitoBean MarketSessionProvider marketSessionProvider;
    @MockitoBean ExecutionExchangeRateProvider exchangeRateProvider;
    @MockitoBean MarketCalendarPort marketCalendarPort;

    @LocalServerPort int port;
    @Autowired ObjectMapper objectMapper;
    @Autowired OrderBookPublicationService publicationService;
    @Autowired OrderBookGenerator generator;
    @Autowired OrderBookProperties properties;
    @Autowired StockRepository stockRepository;
    @Autowired QuoteSnapshotRepository quoteSnapshotRepository;
    @Autowired OrderBookVersionRepository versionRepository;
    @Autowired ClockTestConfig clockConfig;

    private MutableClock clock;
    private Stock stock;

    @BeforeEach
    void setUp() {
        clock = clockConfig.clock;
        clock.setCurrent(BASE);
        when(marketSessionProvider.currentSession(any(), any()))
                .thenReturn(new MarketSessionStatus(true, BASE.plusSeconds(3600)));
        when(exchangeRateProvider.currentUsdKrwRate()).thenReturn(new BigDecimal("1383.60"));

        stock = Stock.create(
                "H" + UUID.randomUUID().toString().substring(0, 5).toUpperCase(),
                MarketCountry.KR, "KOSPI", "HTTP 호가 테스트 종목", null, "KRW", "STOCK", true);
        stock.applyRanking(1, new BigDecimal("1000000"));
        stock = stockRepository.saveAndFlush(stock);

        var quoteAt = BASE.minusSeconds(2).atOffset(ZoneOffset.UTC);
        quoteSnapshotRepository.saveAndFlush(new QuoteSnapshot(
                stock.getStockId(), new BigDecimal("70000"), "KRW", quoteAt, quoteAt));

        StockDescriptor descriptor = StockDescriptor.from(stock);
        GeneratedOrderBook generated = generator.generate(
                properties, descriptor, new BigDecimal("70000"), BASE.minusSeconds(2), BASE, 42L);
        publicationService.publish(generated, BASE.plusSeconds(3600)).orElseThrow();
    }

    @Test
    void 실제_HTTP_응답은_공유_가상_호가_계약을_반환한다() throws Exception {
        HttpResponse<String> response = getOrderBook();
        JsonNode body = objectMapper.readTree(response.body());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(body.path("virtual").asBoolean()).isTrue();
        assertThat(body.path("description").asText()).isEqualTo("현재가 기반 가상 호가·가상 잔량");
        assertThat(body.path("asks")).hasSize(10);
        assertThat(body.path("bids")).hasSize(10);
        for (String side : new String[]{"asks", "bids"}) {
            for (JsonNode level : body.path(side)) {
                assertThat(level.path("price").isTextual()).isTrue();
                assertThat(level.path("quantity").isTextual()).isTrue();
            }
        }
    }

    @Test
    void 실제_HTTP_장외_응답은_503이고_활성_버전을_생성하지_않는다() throws Exception {
        long activeBefore = versionRepository.countByStockIdAndIsActiveTrue(stock.getStockId());
        when(marketSessionProvider.currentSession(any(), any())).thenReturn(MarketSessionStatus.closed());

        HttpResponse<String> response = getOrderBook();
        JsonNode body = objectMapper.readTree(response.body());

        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(body.path("code").asText()).isEqualTo("ORDER_BOOK_UNAVAILABLE");
        assertThat(versionRepository.countByStockIdAndIsActiveTrue(stock.getStockId()))
                .isEqualTo(activeBefore);
    }

    private HttpResponse<String> getOrderBook() throws Exception {
        URI uri = URI.create("http://localhost:" + port
                + "/api/stocks/" + stock.getSymbol() + "/orderbook?marketCountry=KR");
        HttpRequest request = HttpRequest.newBuilder(uri).GET().build();
        return HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
    }


}
