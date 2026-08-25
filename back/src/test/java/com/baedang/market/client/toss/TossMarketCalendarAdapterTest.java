package com.baedang.market.client.toss;

import com.baedang.global.client.toss.TossApiProperties;
import com.baedang.global.client.toss.TossSecuritiesClient;
import com.baedang.market.port.ExchangeRateQuote;
import com.baedang.market.port.MarketCalendarDay;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.time.OffsetDateTime;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link TossMarketCalendarAdapter} 단위 테스트.
 *
 * <p>WireMock 응답 픽스처는 실제 Toss OpenAPI 스펙
 * ({@code https://openapi.tossinvest.com/openapi-docs/latest/openapi.json}) 기준으로
 * 확인한 필드명·구조를 그대로 사용한다.
 */
class TossMarketCalendarAdapterTest {

    private WireMockServer wireMockServer;
    private TossMarketCalendarAdapter adapter;

    private static final String TOKEN_RESPONSE = """
            { "access_token": "test-token", "token_type": "Bearer", "expires_in": 86400 }
            """;

    @BeforeEach
    void setUp() {
        wireMockServer = new WireMockServer(options().dynamicPort());
        wireMockServer.start();
        configureFor("localhost", wireMockServer.port());
        stubFor(post(urlEqualTo("/oauth2/token")).willReturn(okJson(TOKEN_RESPONSE)));

        TossApiProperties properties = new TossApiProperties(
                "http://localhost:" + wireMockServer.port(), "test-id", "test-secret", true);
        TossSecuritiesClient client = new TossSecuritiesClient(RestClient.builder(), properties);
        adapter = new TossMarketCalendarAdapter(client);
    }

    @AfterEach
    void tearDown() {
        wireMockServer.stop();
    }

    @Test
    void 환율_응답을_도메인_모델로_매핑한다() {
        stubFor(get(urlPathEqualTo("/api/v1/exchange-rate"))
                .willReturn(okJson("""
                        {
                          "baseCurrency": "USD",
                          "quoteCurrency": "KRW",
                          "rate": "1398.50",
                          "midRate": "1385.20",
                          "basisPoint": 16,
                          "rateChangeType": "UP",
                          "validFrom": "2026-08-25T15:00:00+09:00",
                          "validUntil": "2026-08-25T16:00:00+09:00"
                        }
                        """)));

        ExchangeRateQuote quote = adapter.fetchExchangeRate();

        assertThat(quote.baseCurrency()).isEqualTo("USD");
        assertThat(quote.quoteCurrency()).isEqualTo("KRW");
        assertThat(quote.rate()).isEqualByComparingTo("1398.50");
        assertThat(quote.midRate()).isEqualByComparingTo("1385.20");
        assertThat(quote.validFrom()).isEqualTo(OffsetDateTime.parse("2026-08-25T15:00:00+09:00"));

        // basisPoint/rateChangeType 처럼 우리가 안 쓰는 필드가 있어도 역직렬화가 깨지지 않아야 한다.
        verify(getRequestedFor(urlPathEqualTo("/api/v1/exchange-rate"))
                .withQueryParam("baseCurrency", equalTo("USD"))
                .withQueryParam("quoteCurrency", equalTo("KRW")));
    }

    @Test
    void 정규장이_열리는_날은_isOpen_true와_시작종료시각을_반환한다() {
        stubFor(get(urlPathEqualTo("/api/v1/market-calendar/KR"))
                .willReturn(okJson("""
                        {
                          "today": {
                            "date": "2026-08-25",
                            "integrated": {
                              "preMarket": { "startTime": "2026-08-25T08:30:00+09:00", "endTime": "2026-08-25T09:00:00+09:00" },
                              "regularMarket": {
                                "startTime": "2026-08-25T09:00:00+09:00",
                                "singlePriceAuctionStartTime": "2026-08-25T15:20:00+09:00",
                                "endTime": "2026-08-25T15:30:00+09:00"
                              },
                              "afterMarket": { "startTime": "2026-08-25T15:40:00+09:00", "endTime": "2026-08-25T18:00:00+09:00" }
                            }
                          }
                        }
                        """)));

        MarketCalendarDay day = adapter.fetchKrMarketCalendar(LocalDate.of(2026, 8, 25));

        assertThat(day.isOpen()).isTrue();
        assertThat(day.regularOpenAt()).isEqualTo(OffsetDateTime.parse("2026-08-25T09:00:00+09:00"));
        assertThat(day.regularCloseAt()).isEqualTo(OffsetDateTime.parse("2026-08-25T15:30:00+09:00"));
    }

    @Test
    void 휴장일은_integrated가_null이고_isOpen_false를_반환한다() {
        stubFor(get(urlPathEqualTo("/api/v1/market-calendar/KR"))
                .willReturn(okJson("""
                        { "today": { "date": "2026-08-15", "integrated": null } }
                        """)));

        MarketCalendarDay day = adapter.fetchKrMarketCalendar(LocalDate.of(2026, 8, 15));

        assertThat(day.isOpen()).isFalse();
        assertThat(day.regularOpenAt()).isNull();
        assertThat(day.regularCloseAt()).isNull();
    }

    @Test
    void US_장운영정보는_market_calendar_US_경로를_호출한다() {
        stubFor(get(urlPathEqualTo("/api/v1/market-calendar/US"))
                .willReturn(okJson("""
                        {
                          "today": {
                            "date": "2026-08-25",
                            "integrated": {
                              "regularMarket": {
                                "startTime": "2026-08-25T22:30:00+09:00",
                                "endTime": "2026-08-26T05:00:00+09:00"
                              }
                            }
                          }
                        }
                        """)));

        MarketCalendarDay day = adapter.fetchUsMarketCalendar(LocalDate.of(2026, 8, 25));

        // 8월은 DST 기간이라 22:30~05:00 이 나와야 한다 — 이 계산은 전부 Toss 응답을 그대로 신뢰한다.
        assertThat(day.regularOpenAt()).isEqualTo(OffsetDateTime.parse("2026-08-25T22:30:00+09:00"));
        assertThat(day.regularCloseAt()).isEqualTo(OffsetDateTime.parse("2026-08-26T05:00:00+09:00"));
        verify(getRequestedFor(urlPathEqualTo("/api/v1/market-calendar/US")));
    }
}
