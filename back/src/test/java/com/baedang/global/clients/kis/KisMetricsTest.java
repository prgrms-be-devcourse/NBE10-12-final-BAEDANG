package com.baedang.global.clients.kis;

import com.baedang.global.config.TimeConfig;
import com.baedang.global.error.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KisMetricsTest {

    private static final String PATH = "/uapi/domestic-stock/v1/quotations/search-stock-info";
    private WireMockServer wireMockServer;

    @BeforeEach
    void setUp() {
        wireMockServer = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMockServer.start();
        WireMock.configureFor("localhost", wireMockServer.port());
    }

    @AfterEach
    void tearDown() {
        wireMockServer.stop();
    }

    @Test
    void records_success_error_rate_limit_and_token_issue_results() {
        stubToken(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                .withBody("{\"access_token\":\"token\",\"expires_in\":86400}"));
        stubFor(get(urlPathEqualTo(PATH)).withQueryParam("case", equalTo("success"))
                .willReturn(kisResponse("0", "", "OK")));
        stubFor(get(urlPathEqualTo(PATH)).withQueryParam("case", equalTo("error"))
                .willReturn(kisResponse("1", "ERR001", "failed")));
        stubFor(get(urlPathEqualTo(PATH)).withQueryParam("case", equalTo("limited"))
                .inScenario("rate-limit")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(kisResponse("1", "EGW00201", "limited"))
                .willSetStateTo("recovered"));
        stubFor(get(urlPathEqualTo(PATH)).withQueryParam("case", equalTo("limited"))
                .inScenario("rate-limit")
                .whenScenarioStateIs("recovered")
                .willReturn(kisResponse("0", "", "OK")));

        contextRunner().run(context -> {
            KisSecuritiesClient client = context.getBean(KisSecuritiesClient.class);
            MeterRegistry registry = context.getBean(MeterRegistry.class);

            client.get(PATH, Map.of("case", "success"), JsonNode.class);
            assertThatThrownBy(() -> client.get(PATH, Map.of("case", "error"), JsonNode.class))
                    .isInstanceOf(BusinessException.class);
            client.get(PATH, Map.of("case", "limited"), JsonNode.class);

            assertThat(counter(registry, "kis.api.requests", "endpoint", "SEARCH_STOCK_INFO", "result", "success"))
                    .isEqualTo(2.0);
            assertThat(counter(registry, "kis.api.requests", "endpoint", "SEARCH_STOCK_INFO", "result", "error"))
                    .isEqualTo(1.0);
            assertThat(counter(registry, "kis.api.requests", "endpoint", "SEARCH_STOCK_INFO", "result", "rate_limited"))
                    .isEqualTo(1.0);
            assertThat(counter(registry, "kis.token.issued", "result", "success"))
                    .isEqualTo(1.0);
        });
    }

    @Test
    void records_failed_token_issue_once_during_cooldown() {
        stubToken(aResponse().withStatus(500));

        contextRunner().run(context -> {
            KisSecuritiesClient client = context.getBean(KisSecuritiesClient.class);
            MeterRegistry registry = context.getBean(MeterRegistry.class);

            assertThatThrownBy(() -> client.get(PATH, Map.of(), JsonNode.class))
                    .isInstanceOf(BusinessException.class);
            assertThatThrownBy(() -> client.get(PATH, Map.of(), JsonNode.class))
                    .isInstanceOf(BusinessException.class);

            assertThat(counter(registry, "kis.token.issued", "result", "error"))
                    .isEqualTo(1.0);
        });
    }

    private ApplicationContextRunner contextRunner() {
        return new ApplicationContextRunner()
                .withUserConfiguration(TimeConfig.class, KisClientConfiguration.class)
                .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .withPropertyValues(
                        "kis.enabled=true",
                        "kis.base-url=http://localhost:" + wireMockServer.port(),
                        "kis.app-key=test-key",
                        "kis.app-secret=test-secret",
                        "kis.requests-per-second=18",
                        "kis.connect-timeout=3s",
                        "kis.read-timeout=5s",
                        "kis.financial-cache-ttl=7d",
                        "kis.industry-cache-ttl=30d",
                        "kis.load-financials=false");
    }

    private static ResponseDefinitionBuilder kisResponse(
            String rtCd, String msgCd, String message) {
        return aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                .withBody("{\"rt_cd\":\"" + rtCd + "\",\"msg_cd\":\"" + msgCd
                        + "\",\"msg1\":\"" + message + "\"}");
    }

    private static void stubToken(ResponseDefinitionBuilder response) {
        stubFor(post(urlEqualTo("/oauth2/tokenP")).willReturn(response));
    }

    private static double counter(MeterRegistry registry, String name, String... tags) {
        return registry.get(name).tags(tags).counter().count();
    }
}
