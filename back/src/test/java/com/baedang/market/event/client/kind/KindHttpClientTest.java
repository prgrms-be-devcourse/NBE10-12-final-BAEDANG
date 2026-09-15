package com.baedang.market.event.client.kind;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.configureFor;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.ok;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KindHttpClientTest {

    private WireMockServer wireMock;
    private URI base;
    private KindHttpClient client;

    @BeforeEach
    void setUp() {
        wireMock = new WireMockServer(options().dynamicPort());
        wireMock.start();
        configureFor("localhost", wireMock.port());
        base = URI.create("http://localhost:" + wireMock.port());

        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofMillis(500));
        RestClient restClient = RestClient.builder()
                .baseUrl(base.toString())
                .requestFactory(factory)
                .build();
        client = new KindHttpClient(restClient);
    }

    @AfterEach
    void tearDown() {
        if (wireMock != null) {
            wireMock.stop();
        }
    }

    @Test
    void successful_get_decodes_utf8_text() {
        wireMock.stubFor(get(urlPathEqualTo("/sample"))
                .willReturn(ok("유가증권시장 매매거래 정지 테스트")
                        .withHeader("Content-Type", "text/html; charset=UTF-8")));

        String result = client.getText(base.resolve("/sample"), 1024);
        assertThat(result).isEqualTo("유가증권시장 매매거래 정지 테스트");
    }

    @Test
    void redirect_is_not_followed() {
        wireMock.stubFor(get(urlPathEqualTo("/source"))
                .willReturn(aResponse().withStatus(302).withHeader("Location", "/target")));

        assertThatThrownBy(() -> client.getText(base.resolve("/source"), 1024))
                .isInstanceOf(IllegalStateException.class);
        wireMock.verify(0, getRequestedFor(urlPathEqualTo("/target")));
    }

    @Test
    void response_over_limit_is_rejected() {
        wireMock.stubFor(get(urlPathEqualTo("/large"))
                .willReturn(ok("x".repeat(1025))));

        assertThatThrownBy(() -> client.getText(base.resolve("/large"), 1024))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void response_at_exact_limit_succeeds() {
        String exact = "y".repeat(1024);
        wireMock.stubFor(get(urlPathEqualTo("/exact"))
                .willReturn(ok(exact)));

        String result = client.getText(base.resolve("/exact"), 1024);
        assertThat(result).isEqualTo(exact);
    }

    @Test
    void non_2xx_status_is_rejected() {
        wireMock.stubFor(get(urlPathEqualTo("/not-found"))
                .willReturn(aResponse().withStatus(404)));
        wireMock.stubFor(get(urlPathEqualTo("/server-error"))
                .willReturn(aResponse().withStatus(500)));

        assertThatThrownBy(() -> client.getText(base.resolve("/not-found"), 1024))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> client.getText(base.resolve("/server-error"), 1024))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void read_timeout_is_enforced() {
        wireMock.stubFor(get(urlPathEqualTo("/delayed"))
                .willReturn(ok("slow").withFixedDelay(1000)));

        assertThatThrownBy(() -> client.getText(base.resolve("/delayed"), 1024))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void connection_failure_is_rejected() {
        URI unreachable = URI.create("http://localhost:1");

        assertThatThrownBy(() -> client.getText(unreachable, 1024))
                .isInstanceOf(IllegalStateException.class);
    }
}
