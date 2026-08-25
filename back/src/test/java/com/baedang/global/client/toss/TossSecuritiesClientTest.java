package com.baedang.global.client.toss;

import com.baedang.global.error.BusinessException;
import com.baedang.global.error.ErrorCode;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link TossSecuritiesClient} 단위 테스트 — 실제 Toss 를 호출하지 않고 WireMock 으로
 * 응답을 스텁한다. 토큰 캐싱, 화이트리스트 경로 호출, 에러 매핑을 검증한다.
 */
class TossSecuritiesClientTest {

    private WireMockServer wireMockServer;
    private TossSecuritiesClient client;

    private static final String TOKEN_RESPONSE = """
            {
              "access_token": "test-access-token",
              "token_type": "Bearer",
              "expires_in": 86400
            }
            """;

    record TestBody(String baseCurrency, String quoteCurrency) {
    }

    @BeforeEach
    void setUp() {
        wireMockServer = new WireMockServer(options().dynamicPort());
        wireMockServer.start();
        configureFor("localhost", wireMockServer.port());

        stubFor(post(urlEqualTo("/oauth2/token"))
                .willReturn(okJson(TOKEN_RESPONSE)));

        TossApiProperties properties = new TossApiProperties(
                "http://localhost:" + wireMockServer.port(), "test-id", "test-secret", true);
        client = new TossSecuritiesClient(RestClient.builder(), properties);
    }

    @AfterEach
    void tearDown() {
        wireMockServer.stop();
    }

    @Test
    void 정상_응답을_DTO로_매핑한다() {
        stubFor(get(urlPathEqualTo("/api/v1/exchange-rate"))
                .willReturn(okJson("""
                        { "baseCurrency": "USD", "quoteCurrency": "KRW" }
                        """)));

        TestBody body = client.get(TossPathWhitelist.EXCHANGE_RATE,
                Map.of("baseCurrency", "USD", "quoteCurrency", "KRW"), TestBody.class);

        assertThat(body.baseCurrency()).isEqualTo("USD");
        assertThat(body.quoteCurrency()).isEqualTo("KRW");
    }

    @Test
    void 토큰은_한번_발급받으면_재사용한다() {
        stubFor(get(urlPathEqualTo("/api/v1/exchange-rate"))
                .willReturn(okJson("""
                        { "baseCurrency": "USD", "quoteCurrency": "KRW" }
                        """)));

        client.get(TossPathWhitelist.EXCHANGE_RATE, Map.of(), TestBody.class);
        client.get(TossPathWhitelist.EXCHANGE_RATE, Map.of(), TestBody.class);
        client.get(TossPathWhitelist.EXCHANGE_RATE, Map.of(), TestBody.class);

        // /oauth2/token 은 세 번 중 처음 한 번만 호출돼야 한다 — 매 요청마다 발급받으면
        // rate limit 에 걸린다는 docs/erd.md 규칙을 지키는지 확인.
        verify(1, postRequestedFor(urlEqualTo("/oauth2/token")));
        verify(3, getRequestedFor(urlPathEqualTo("/api/v1/exchange-rate")));
    }

    @Test
    void 요청이_실패하면_BusinessException_TOSS_API_ERROR로_변환한다() {
        stubFor(get(urlPathEqualTo("/api/v1/exchange-rate"))
                .willReturn(aResponse().withStatus(500)));

        assertThatThrownBy(() -> client.get(TossPathWhitelist.EXCHANGE_RATE, Map.of(), TestBody.class))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.TOSS_API_ERROR);
    }

    @Test
    void 레이트리밋_429는_TOSS_RATE_LIMITED로_변환한다() {
        stubFor(get(urlPathEqualTo("/api/v1/exchange-rate"))
                .willReturn(aResponse().withStatus(429)));

        assertThatThrownBy(() -> client.get(TossPathWhitelist.EXCHANGE_RATE, Map.of(), TestBody.class))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.TOSS_RATE_LIMITED);
    }

    @Test
    void 토큰_발급_자체가_실패하면_TOSS_API_ERROR로_변환한다() {
        wireMockServer.resetAll();
        stubFor(post(urlEqualTo("/oauth2/token")).willReturn(aResponse().withStatus(401)));

        assertThatThrownBy(() -> client.get(TossPathWhitelist.EXCHANGE_RATE, Map.of(), TestBody.class))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.TOSS_API_ERROR);
    }
}
