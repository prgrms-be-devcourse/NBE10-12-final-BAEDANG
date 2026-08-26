package com.baedang.market.client.toss;

import com.baedang.global.clients.toss.TossSecuritiesClient;
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
 * <p>WireMock 응답 픽스처는 2026-08-26 실제 Toss 호출 캡처(호영님 공유) 기준으로
 * 작성했습니다. 최상위 {@code result} 래핑, KR의 {@code integrated} 구조, US의
 * {@code integrated} 없는 구조를 전부 실제 응답 그대로 반영합니다.
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

        TossSecuritiesClient client = new TossSecuritiesClient(RestClient.builder(), "http://localhost:" + wireMockServer.port(), "test-id", "test-secret");
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
                          "result": {
                            "baseCurrency": "USD",
                            "quoteCurrency": "KRW",
                            "rate": "1398.50",
                            "midRate": "1385.20",
                            "basisPoint": 16,
                            "rateChangeType": "UP",
                            "validFrom": "2026-08-25T15:00:00+09:00",
                            "validUntil": "2026-08-25T16:00:00+09:00"
                          }
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
    void KR_정규장이_열리는_날은_isOpen_true와_시작종료시각을_반환한다() {
        stubFor(get(urlPathEqualTo("/api/v1/market-calendar/KR"))
                .willReturn(okJson("""
                        {
                          "result": {
                            "today": {
                              "date": "2026-08-26",
                              "integrated": {
                                "preMarket": { "startTime": "2026-08-26T08:00:00+09:00", "singlePriceAuctionStartTime": "2026-08-26T08:50:00+09:00", "endTime": "2026-08-26T09:00:00+09:00" },
                                "regularMarket": {
                                  "startTime": "2026-08-26T09:00:00+09:00",
                                  "singlePriceAuctionStartTime": "2026-08-26T15:20:00+09:00",
                                  "endTime": "2026-08-26T15:30:00+09:00"
                                },
                                "afterMarket": { "startTime": "2026-08-26T15:30:00+09:00", "singlePriceAuctionEndTime": "2026-08-26T15:40:00+09:00", "endTime": "2026-08-26T20:00:00+09:00" }
                              }
                            }
                          }
                        }
                        """)));

        MarketCalendarDay day = adapter.fetchKrMarketCalendar(LocalDate.of(2026, 8, 26));

        assertThat(day.isOpen()).isTrue();
        assertThat(day.regularOpenAt()).isEqualTo(OffsetDateTime.parse("2026-08-26T09:00:00+09:00"));
        assertThat(day.regularCloseAt()).isEqualTo(OffsetDateTime.parse("2026-08-26T15:30:00+09:00"));
        // 이미 열려 있으면 "다음 개장 시각"은 필요 없다 — docs/api-spec.md의 open:true → nextOpensAt:null 규칙.
        assertThat(day.nextOpensAt()).isNull();
    }

    @Test
    void KR_휴장일은_integrated가_null이고_isOpen_false를_반환하며_다음_개장_시각을_함께_준다() {
        stubFor(get(urlPathEqualTo("/api/v1/market-calendar/KR"))
                .willReturn(okJson("""
                        {
                          "result": {
                            "today": { "date": "2026-08-15", "integrated": null },
                            "nextBusinessDay": {
                              "date": "2026-08-17",
                              "integrated": {
                                "regularMarket": {
                                  "startTime": "2026-08-17T09:00:00+09:00",
                                  "endTime": "2026-08-17T15:30:00+09:00"
                                }
                              }
                            }
                          }
                        }
                        """)));

        MarketCalendarDay day = adapter.fetchKrMarketCalendar(LocalDate.of(2026, 8, 15));

        assertThat(day.isOpen()).isFalse();
        assertThat(day.regularOpenAt()).isNull();
        assertThat(day.regularCloseAt()).isNull();
        // nextOpensAt은 nextBusinessDay.integrated.regularMarket.startTime을 그대로 옮긴 값이다.
        assertThat(day.nextOpensAt()).isEqualTo(OffsetDateTime.parse("2026-08-17T09:00:00+09:00"));
    }

    @Test
    void KR_휴장일이고_nextBusinessDay마저_없으면_다음_개장_시각도_null이다() {
        stubFor(get(urlPathEqualTo("/api/v1/market-calendar/KR"))
                .willReturn(okJson("""
                        { "result": { "today": { "date": "2026-08-15", "integrated": null } } }
                        """)));

        MarketCalendarDay day = adapter.fetchKrMarketCalendar(LocalDate.of(2026, 8, 15));

        assertThat(day.isOpen()).isFalse();
        assertThat(day.nextOpensAt()).isNull();
    }

    @Test
    void US_응답은_integrated로_감싸지_않고_today_바로_아래_regularMarket을_준다() {
        stubFor(get(urlPathEqualTo("/api/v1/market-calendar/US"))
                .willReturn(okJson("""
                        {
                          "result": {
                            "today": {
                              "date": "2026-08-26",
                              "dayMarket": { "startTime": "2026-08-26T09:00:00+09:00", "endTime": "2026-08-26T17:00:00+09:00" },
                              "preMarket": { "startTime": "2026-08-26T17:00:00+09:00", "endTime": "2026-08-26T22:30:00+09:00" },
                              "regularMarket": {
                                "startTime": "2026-08-26T22:30:00+09:00",
                                "endTime": "2026-08-27T05:00:00+09:00"
                              },
                              "afterMarket": { "startTime": "2026-08-27T05:00:00+09:00", "endTime": "2026-08-27T08:50:00+09:00" }
                            }
                          }
                        }
                        """)));

        MarketCalendarDay day = adapter.fetchUsMarketCalendar(LocalDate.of(2026, 8, 26));

        assertThat(day.isOpen()).isTrue();
        // 8월은 DST 기간이라 22:30~05:00 이 나와야 한다 — 이 계산은 전부 Toss 응답을 그대로 신뢰한다.
        assertThat(day.regularOpenAt()).isEqualTo(OffsetDateTime.parse("2026-08-26T22:30:00+09:00"));
        assertThat(day.regularCloseAt()).isEqualTo(OffsetDateTime.parse("2026-08-27T05:00:00+09:00"));
        assertThat(day.nextOpensAt()).isNull();
        verify(getRequestedFor(urlPathEqualTo("/api/v1/market-calendar/US")));
    }

    @Test
    void US_regularMarket이_없으면_휴장일로_판단하고_다음_개장_시각을_준다() {
        // ⚠️ 실제 US 휴장일 응답을 아직 캡처하지 못해, KR과 같은 "필드 없으면 휴장" 가정으로
        // 작성한 방어적 테스트다. 실제 응답을 확인하면 이 픽스처도 다시 검증해야 한다.
        stubFor(get(urlPathEqualTo("/api/v1/market-calendar/US"))
                .willReturn(okJson("""
                        {
                          "result": {
                            "today": { "date": "2026-08-15" },
                            "nextBusinessDay": {
                              "date": "2026-08-17",
                              "regularMarket": {
                                "startTime": "2026-08-17T22:30:00+09:00",
                                "endTime": "2026-08-18T05:00:00+09:00"
                              }
                            }
                          }
                        }
                        """)));

        MarketCalendarDay day = adapter.fetchUsMarketCalendar(LocalDate.of(2026, 8, 15));

        assertThat(day.isOpen()).isFalse();
        assertThat(day.regularOpenAt()).isNull();
        assertThat(day.nextOpensAt()).isEqualTo(OffsetDateTime.parse("2026-08-17T22:30:00+09:00"));
    }
}
