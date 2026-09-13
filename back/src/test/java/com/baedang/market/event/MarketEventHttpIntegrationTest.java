package com.baedang.market.event;

import com.baedang.market.event.entity.KrMarket;
import com.baedang.market.event.entity.MarketEvent;
import com.baedang.market.event.entity.MarketEventSource;
import com.baedang.market.event.entity.SidecarDirection;
import com.baedang.market.event.repository.MarketEventRepository;
import com.baedang.market.port.MarketCalendarPort;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실제 HTTP로 조회 API를 호출한다. 인증 없이 접근 가능한지, 응답 시각이 {@code +09:00}인지,
 * 정렬과 상한이 저장된 데이터에 대해 실제로 지켜지는지 확인한다.
 */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.sql.init.mode=never",
        "toss.enabled=false",
        "krx.market-events.enabled=false",
        "logging.level.org.hibernate.SQL=OFF"
})
class MarketEventHttpIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb:latest-pg18")
                    .asCompatibleSubstituteFor("postgres"));

    private static final URI SOURCE_URL =
            URI.create("https://kind.krx.co.kr/external/2026/07/13/000273/20260713000658/99443.htm");

    @LocalServerPort
    private int port;

    /** toss가 꺼져 있어 위임 빈이 없다. 캘린더는 이 테스트의 관심사가 아니므로 대체한다. */
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    MarketCalendarPort marketCalendarPort;

    @Autowired
    private MarketEventRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void cleanUp() {
        // 리포지토리는 append-only라 삭제 메서드를 노출하지 않는다. 테스트 정리만 직접 한다.
        jdbc.execute("DELETE FROM market_event");
    }

    /** 인증 헤더 없이 호출한다. */
    @Test
    void events_endpoint_is_public_and_returns_kst_offsets() throws Exception {
        HttpResponse<String> response = get("/api/market/events?market=KOSPI&date=2026-07-13");

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = objectMapper.readTree(response.body());
        assertThat(body.get("market").asText()).isEqualTo("KOSPI");
        assertThat(body.get("date").asText()).isEqualTo("2026-07-13");
        assertThat(body.get("items").isArray()).isTrue();
        body.get("items").forEach(item ->
                assertThat(item.get("triggeredAt").asText()).endsWith("+09:00"));
    }

    @Test
    void invalid_market_and_date_return_400_with_field() throws Exception {
        assertThat(get("/api/market/events?market=NYSE&date=2026-07-13").statusCode()).isEqualTo(400);
        assertThat(get("/api/market/events?market=KOSPI&date=not-a-date").statusCode()).isEqualTo(400);

        JsonNode body = objectMapper.readTree(
                get("/api/market/events?market=NYSE&date=2026-07-13").body());
        assertThat(body.get("code").asText()).isEqualTo("INVALID_INPUT");
        assertThat(body.get("data").get("field").asText()).isEqualTo("market");
    }

    /**
     * 저장된 이벤트가 조회에 실제로 나타난다. 저장은 UTC로 하고 질의는 KST 날짜 경계로 하므로,
     * KST 7월 13일 13:00(= UTC 04:00)에 저장한 이벤트가 같은 날짜 질의에 잡혀야 한다.
     */
    @Test
    void stored_events_appear_in_newest_first_order_with_kst_boundaries() throws Exception {
        repository.saveAndFlush(circuitBreaker("20260713000701", "2026-07-13T04:10:00Z"));
        repository.saveAndFlush(circuitBreaker("20260713000702", "2026-07-13T04:20:00Z"));
        // KST 7월 14일 00:30 (= UTC 7월 13일 15:30) — 7월 13일 질의에는 나오면 안 된다.
        repository.saveAndFlush(circuitBreaker("20260713000703", "2026-07-13T15:30:00Z"));

        JsonNode items = objectMapper.readTree(
                        get("/api/market/events?market=KOSPI&date=2026-07-13").body())
                .get("items");

        assertThat(items).hasSize(2);
        assertThat(items.get(0).get("triggeredAt").asText()).isEqualTo("2026-07-13T13:20:00+09:00");
        assertThat(items.get(1).get("triggeredAt").asText()).isEqualTo("2026-07-13T13:10:00+09:00");
    }

    @Test
    void sidecar_response_has_direction_and_no_stage() throws Exception {
        repository.saveAndFlush(MarketEvent.sidecar(
                MarketEventSource.KRX_KIND, "20260713000704", KrMarket.KOSPI, SidecarDirection.SELL,
                Instant.parse("2026-07-13T04:10:00Z"), Instant.parse("2026-07-13T04:15:00Z"),
                Instant.parse("2026-07-13T04:11:00Z"), Instant.parse("2026-07-13T04:11:05Z"),
                "유가증권시장 매도 사이드카(Sidecar) 발동", SOURCE_URL));

        JsonNode item = objectMapper.readTree(
                        get("/api/market/events?market=KOSPI&date=2026-07-13").body())
                .get("items").get(0);

        assertThat(item.get("eventType").asText()).isEqualTo("SIDECAR");
        assertThat(item.get("direction").asText()).isEqualTo("SELL");
        // 전역 jackson 설정이 non_null이라 CB 전용 필드는 응답에서 생략된다.
        assertThat(item.has("stage")).isFalse();
        assertThat(item.has("retryPolicy")).isFalse();
    }

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private MarketEvent circuitBreaker(String sourceEventId, String triggeredAt) {
        return MarketEvent.circuitBreaker(
                MarketEventSource.KRX_KIND,
                sourceEventId,
                KrMarket.KOSPI,
                1,
                Instant.parse(triggeredAt),
                Instant.parse(triggeredAt).plusSeconds(1200),
                Instant.parse(triggeredAt).plusSeconds(30),
                Instant.parse(triggeredAt).plusSeconds(40),
                "유가증권시장 매매거래 일시중단(1단계 CB 발동)",
                SOURCE_URL);
    }
}
