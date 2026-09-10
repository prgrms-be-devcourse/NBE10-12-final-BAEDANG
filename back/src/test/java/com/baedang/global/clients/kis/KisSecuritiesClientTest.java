package com.baedang.global.clients.kis;

import com.baedang.global.clients.FixedIntervalGate;
import com.baedang.global.error.BusinessException;
import com.baedang.global.error.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KisSecuritiesClientTest {

    private static final String PATH = "/uapi/domestic-stock/v1/quotations/search-stock-info";
    private static final String TOKEN_PATH = "/oauth2/tokenP";
    private static final String APP_KEY = "test-app-key";
    private static final String APP_SECRET = "test-app-secret";

    private WireMockServer wireMockServer;
    private KisSecuritiesClient client;
    private KisTokenProvider tokenProvider;
    private MutableClock clock;
    private List<Long> retrySleeps;

    @BeforeEach
    void setUp() {
        wireMockServer = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMockServer.start();
        WireMock.configureFor("localhost", wireMockServer.port());

        AtomicLong now = new AtomicLong();
        FixedIntervalGate gate = new FixedIntervalGate(1_000, now::get, ignored -> {
        });
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        KisRateLimiter rateLimiter = new KisRateLimiter(gate, meterRegistry);
        KisProperties properties = properties(true);
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        RestClient restClient = RestClient.builder()
                .requestFactory(requestFactory)
                .baseUrl("http://localhost:" + wireMockServer.port())
                .build();
        clock = new MutableClock(Instant.parse("2026-09-08T00:00:00Z"));
        retrySleeps = new ArrayList<>();
        tokenProvider = new KisTokenProvider(restClient, properties, clock, meterRegistry);
        client = new KisSecuritiesClient(
                restClient,
                properties,
                rateLimiter,
                tokenProvider,
                new ObjectMapper(),
                meterRegistry,
                retrySleeps::add);
    }

    @AfterEach
    void tearDown() {
        wireMockServer.stop();
    }

    @Test
    void allowed_get_sends_contract_headers_and_reuses_token() {
        stubToken("token-1");
        stubFor(get(urlPathEqualTo(PATH))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("{\"rt_cd\":\"0\",\"msg_cd\":\"\",\"msg1\":\"OK\",\"output\":{\"value\":\"ok\"}}")));


        JsonNode first = client.get(PATH, Map.of("PDNO", "005930"), JsonNode.class);
        JsonNode second = client.get(PATH, Map.of("PDNO", "005930"), JsonNode.class);

        assertThat(first.path("output").path("value").asText()).isEqualTo("ok");
        assertThat(second.path("output").path("value").asText()).isEqualTo("ok");
        verify(2, getRequestedFor(urlPathEqualTo(PATH))
                .withQueryParam("PDNO", equalTo("005930"))
                .withHeader("authorization", equalTo("Bearer token-1"))
                .withHeader("appkey", equalTo(APP_KEY))
                .withHeader("appsecret", equalTo(APP_SECRET))
                .withHeader("tr_id", equalTo("CTPF1002R"))
                .withHeader("custtype", equalTo("P")));
        verify(1, postRequestedFor(urlEqualTo(TOKEN_PATH))
                .withRequestBody(equalToJson("{\"grant_type\":\"client_credentials\",\"appkey\":\""
                        + APP_KEY + "\",\"appsecret\":\"" + APP_SECRET + "\"}")));
    }

    @ParameterizedTest
    @MethodSource("allowedEndpoints")
    void every_allowed_endpoint_uses_its_fixed_tr_id(String path, String trId) {
        stubToken("token");
        stubFor(get(urlPathEqualTo(path))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("{\"rt_cd\":\"0\",\"msg_cd\":\"\",\"msg1\":\"OK\"}")));

        client.get(path, Map.of(), JsonNode.class);

        verify(1, getRequestedFor(urlPathEqualTo(path))
                .withHeader("tr_id", equalTo(trId)));
    }

    private static Stream<Arguments> allowedEndpoints() {
        return Stream.of(
                Arguments.of("/uapi/domestic-stock/v1/quotations/search-stock-info", "CTPF1002R"),
                Arguments.of("/uapi/domestic-stock/v1/finance/balance-sheet", "FHKST66430100"),
                Arguments.of("/uapi/domestic-stock/v1/finance/income-statement", "FHKST66430200"),
                Arguments.of("/uapi/domestic-stock/v1/finance/financial-ratio", "FHKST66430300"),
                Arguments.of("/uapi/domestic-stock/v1/finance/profit-ratio", "FHKST66430400")
        );
    }


    @Test
    void unauthorized_request_refreshes_token_and_retries_once() {
        stubFor(post(urlEqualTo(TOKEN_PATH))
                .inScenario("token-refresh")
                .whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("{\"access_token\":\"token-1\",\"token_type\":\"Bearer\",\"expires_in\":86400}"))
                .willSetStateTo("refreshed"));
        stubFor(post(urlEqualTo(TOKEN_PATH))
                .inScenario("token-refresh")
                .whenScenarioStateIs("refreshed")
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("{\"access_token\":\"token-2\",\"token_type\":\"Bearer\",\"expires_in\":86400}")));
        stubFor(get(urlPathEqualTo(PATH))
                .inScenario("auth-retry")
                .whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withStatus(401))
                .willSetStateTo("retried"));
        stubFor(get(urlPathEqualTo(PATH))
                .inScenario("auth-retry")
                .whenScenarioStateIs("retried")
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("{\"rt_cd\":\"0\",\"msg_cd\":\"\",\"msg1\":\"OK\"}")));

        assertThat(client.get(PATH, Map.of(), JsonNode.class).path("rt_cd").asText()).isEqualTo("0");
        verify(2, postRequestedFor(urlEqualTo(TOKEN_PATH)));
        verify(2, getRequestedFor(urlPathEqualTo(PATH)));
    }

    @Test
    void order_and_unregistered_paths_are_rejected_before_any_http_request() {
        for (String forbiddenPath : List.of(
                "/uapi/domestic-stock/v1/trading/order-cash",
                "/uapi/domestic-stock/v1/quotations/unregistered")) {
            assertThatThrownBy(() -> client.get(forbiddenPath, Map.of(), JsonNode.class))
                    .isInstanceOf(BusinessException.class)
                    .extracting(exception -> ((BusinessException) exception).getErrorCode())
                    .isEqualTo(ErrorCode.INTERNAL_ERROR);
            verify(0, getRequestedFor(urlPathEqualTo(forbiddenPath)));
        }
        verify(0, postRequestedFor(urlEqualTo(TOKEN_PATH)));
    }

    @Test
    void nonzero_rt_code_is_kis_api_error() {
        stubToken("token-1");
        stubFor(get(urlPathEqualTo(PATH)).willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"rt_cd\":\"1\",\"msg_cd\":\"ERR001\",\"msg1\":\"failed\"}")));

        assertThatThrownBy(() -> client.get(PATH, Map.of(), JsonNode.class))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.KIS_API_ERROR);
                    assertThat(exception.getDetail())
                            .contains("endpoint=SEARCH_STOCK_INFO")
                            .contains("trId=CTPF1002R")
                            .contains("msgCd=ERR001")
                            .doesNotContain(APP_KEY, APP_SECRET, "token-1", "failed");
                });
    }

    @Test
    void token_expiry_message_refreshes_token_and_retries_once() {
        stubTokenSequence(86_400L, null);
        stubFor(get(urlPathEqualTo(PATH))
                .inScenario("body-token-expiry")
                .whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("{\"rt_cd\":\"1\",\"msg_cd\":\"EGW00123\",\"msg1\":\"expired\"}"))
                .willSetStateTo("refreshed"));
        stubFor(get(urlPathEqualTo(PATH))
                .inScenario("body-token-expiry")
                .whenScenarioStateIs("refreshed")
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("{\"rt_cd\":\"0\",\"msg_cd\":\"\",\"msg1\":\"OK\"}")));

        JsonNode response = client.get(PATH, Map.of(), JsonNode.class);

        assertThat(response.path("rt_cd").asText()).isEqualTo("0");
        verify(1, getRequestedFor(urlPathEqualTo(PATH))
                .withHeader("authorization", equalTo("Bearer token-1")));
        verify(1, getRequestedFor(urlPathEqualTo(PATH))
                .withHeader("authorization", equalTo("Bearer token-2")));
        verify(2, postRequestedFor(urlEqualTo(TOKEN_PATH)));
    }

    @Test
    void second_token_expiry_message_is_not_retried_again() {
        stubTokenSequence(86_400L, null);
        stubFor(get(urlPathEqualTo(PATH)).willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"rt_cd\":\"1\",\"msg_cd\":\"EGW00123\",\"msg1\":\"expired\"}")));

        assertThatThrownBy(() -> client.get(PATH, Map.of(), JsonNode.class))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.KIS_API_ERROR);
                    assertThat(exception.getDetail()).contains("msgCd=EGW00123");
                });
        verify(2, getRequestedFor(urlPathEqualTo(PATH)));
        verify(2, postRequestedFor(urlEqualTo(TOKEN_PATH)));
    }

    @Test
    void rate_limit_message_is_retried_once_after_injected_sleep() {
        stubToken("token-1");
        stubFor(get(urlPathEqualTo(PATH))
                .inScenario("rate-limit")
                .whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("{\"rt_cd\":\"1\",\"msg_cd\":\"EGW00201\",\"msg1\":\"limited\"}"))
                .willSetStateTo("retried"));
        stubFor(get(urlPathEqualTo(PATH))
                .inScenario("rate-limit")
                .whenScenarioStateIs("retried")
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("{\"rt_cd\":\"0\",\"msg_cd\":\"\",\"msg1\":\"OK\"}")));

        assertThat(client.get(PATH, Map.of(), JsonNode.class).path("rt_cd").asText()).isEqualTo("0");
        verify(2, getRequestedFor(urlPathEqualTo(PATH)));
        assertThat(retrySleeps).containsExactly(1_000L);
    }

    @Test
    void second_rate_limit_failure_is_kis_rate_limited() {
        stubToken("token-1");
        stubFor(get(urlPathEqualTo(PATH))
                .willReturn(aResponse().withStatus(429).withHeader("Content-Type", "application/json")
                        .withBody("{\"rt_cd\":\"1\",\"msg_cd\":\"EGW00201\",\"msg1\":\"limited\"}")));

        assertThatThrownBy(() -> client.get(PATH, Map.of(), JsonNode.class))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(ErrorCode.KIS_RATE_LIMITED);
        verify(2, getRequestedFor(urlPathEqualTo(PATH)));
        assertThat(retrySleeps).containsExactly(1_000L);
    }
    @Test
    void server_error_is_not_retried() {
        stubToken("token");
        stubFor(get(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(500)));

        assertThatThrownBy(() -> client.get(PATH, Map.of(), JsonNode.class))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(ErrorCode.KIS_API_ERROR);
        verify(1, getRequestedFor(urlPathEqualTo(PATH)));
    }


    @Test
    void expired_replacement_token_is_not_reused_for_a_stale_request() {
        stubTokenSequence(60L, null);
        assertThat(tokenProvider.getToken()).isEqualTo("token-1");
        clock.advance(Duration.ofSeconds(61));

        assertThat(tokenProvider.refreshIfStillStale("older-token")).isEqualTo("token-2");
        verify(2, postRequestedFor(urlEqualTo(TOKEN_PATH)));
    }

    @Test
    void explicit_expiration_is_interpreted_in_korea_time_and_refreshed_five_minutes_early() {
        stubTokenSequence(86_400L, "2026-09-08 09:06:00");
        assertThat(tokenProvider.getToken()).isEqualTo("token-1");
        clock.advance(Duration.ofSeconds(61));

        assertThat(tokenProvider.getToken()).isEqualTo("token-2");
        verify(2, postRequestedFor(urlEqualTo(TOKEN_PATH)));
    }

    @Test
    void concurrent_token_requests_issue_once() throws Exception {
        stubToken("shared-token");
        try (var executor = Executors.newFixedThreadPool(5)) {
            List<Callable<String>> requests = IntStream.range(0, 5)
                    .mapToObj(ignored -> (Callable<String>) tokenProvider::getToken)
                    .toList();
            List<Future<String>> results = executor.invokeAll(requests, 5, TimeUnit.SECONDS);

            assertThat(results).allSatisfy(result ->
                    assertThat(result.get(1, TimeUnit.SECONDS)).isEqualTo("shared-token"));
        }
        verify(1, postRequestedFor(urlEqualTo(TOKEN_PATH)));
    }

    @Test
    void token_failure_is_cached_without_exposing_credentials() {
        stubFor(post(urlEqualTo(TOKEN_PATH)).willReturn(aResponse()
                .withStatus(500)
                .withBody(APP_KEY + APP_SECRET)));

        assertThatThrownBy(tokenProvider::getToken)
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> assertThat(exception.getMessage())
                        .doesNotContain(APP_KEY, APP_SECRET));
        assertThatThrownBy(tokenProvider::getToken)
                .isInstanceOf(BusinessException.class);
        verify(1, postRequestedFor(urlEqualTo(TOKEN_PATH)));
    }

    @Test
    void second_authentication_failure_is_not_retried_again() {
        stubToken("token");
        stubFor(get(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(401)));

        assertThatThrownBy(() -> client.get(PATH, Map.of(), JsonNode.class))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(ErrorCode.KIS_API_ERROR);
        verify(2, getRequestedFor(urlPathEqualTo(PATH)));
    }

    private void stubToken(String token) {
        stubFor(post(urlEqualTo(TOKEN_PATH))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("{\"access_token\":\"" + token
                                + "\",\"token_type\":\"Bearer\",\"expires_in\":86400}")));
    }

    private void stubTokenSequence(long firstExpiresIn, String firstExplicitExpiration) {
        String explicitField = firstExplicitExpiration == null ? "" :
                ",\"access_token_token_expired\":\"" + firstExplicitExpiration + "\"";
        stubFor(post(urlEqualTo(TOKEN_PATH))
                .inScenario("token-sequence")
                .whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("{\"access_token\":\"token-1\",\"expires_in\":"
                                + firstExpiresIn + explicitField + "}"))
                .willSetStateTo("second"));
        stubFor(post(urlEqualTo(TOKEN_PATH))
                .inScenario("token-sequence")
                .whenScenarioStateIs("second")
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("{\"access_token\":\"token-2\",\"expires_in\":86400}")));
    }

    private static KisProperties properties(boolean enabled) {
        return new KisProperties(
                enabled,
                URI.create("http://localhost"),
                APP_KEY,
                APP_SECRET,
                18,
                Duration.ofSeconds(3),
                Duration.ofSeconds(5),
                Duration.ofDays(7),
                Duration.ofDays(30),
                enabled);
    }

    private static final class MutableClock extends Clock {
        private Instant current;

        private MutableClock(Instant current) {
            this.current = current;
        }

        private void advance(Duration duration) {
            current = current.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return current;
        }
    }
}
