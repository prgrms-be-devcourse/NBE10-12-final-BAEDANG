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
    void US_휴장일에는_regularMarket이_명시적으로_null로_오며_다음_개장_시각을_준다() {
        // 2026-08-27 실제 호출 캡처(호영님 공유, 일요일 8/23 조회) 기준. 필드가 사라지는 게
        // 아니라 "regularMarket": null 처럼 명시적으로 null이 온다 — Jackson 입장에서는
        // "필드 없음"과 결과가 같아서 기존 null 체크 로직이 그대로 맞다.
        // nextBusinessDay.date(8/24)의 regularMarket.startTime은 8/24를 직접 조회했을 때의
        // regularMarket.startTime과 동일해서(22:30 KST, DST) 교차검증까지 된 값이다.
        stubFor(get(urlPathEqualTo("/api/v1/market-calendar/US"))
                .willReturn(okJson("""
                        {
                          "result": {
                            "today": {
                              "date": "2026-08-23",
                              "dayMarket": null,
                              "preMarket": null,
                              "regularMarket": null,
                              "afterMarket": null
                            },
                            "previousBusinessDay": {
                              "date": "2026-08-21",
                              "dayMarket": { "startTime": "2026-08-21T09:00:00+09:00", "endTime": "2026-08-21T17:00:00+09:00" },
                              "preMarket": { "startTime": "2026-08-21T17:00:00+09:00", "endTime": "2026-08-21T22:30:00+09:00" },
                              "regularMarket": { "startTime": "2026-08-21T22:30:00+09:00", "endTime": "2026-08-22T05:00:00+09:00" },
                              "afterMarket": { "startTime": "2026-08-22T05:00:00+09:00", "endTime": "2026-08-22T08:50:00+09:00" }
                            },
                            "nextBusinessDay": {
                              "date": "2026-08-24",
                              "dayMarket": { "startTime": "2026-08-24T09:00:00+09:00", "endTime": "2026-08-24T17:00:00+09:00" },
                              "preMarket": { "startTime": "2026-08-24T17:00:00+09:00", "endTime": "2026-08-24T22:30:00+09:00" },
                              "regularMarket": { "startTime": "2026-08-24T22:30:00+09:00", "endTime": "2026-08-25T05:00:00+09:00" },
                              "afterMarket": { "startTime": "2026-08-25T05:00:00+09:00", "endTime": "2026-08-25T08:50:00+09:00" }
                            }
                          }
                        }
                        """)));

        MarketCalendarDay day = adapter.fetchUsMarketCalendar(LocalDate.of(2026, 8, 23));

        assertThat(day.isOpen()).isFalse();
        assertThat(day.regularOpenAt()).isNull();
        assertThat(day.regularCloseAt()).isNull();
        // previousBusinessDay는 우리 도메인에 필요 없어 매핑 안 하지만, 응답에 있어도
        // @JsonIgnoreProperties(ignoreUnknown = true) 덕분에 역직렬화가 깨지지 않는다.
        assertThat(day.nextOpensAt()).isEqualTo(OffsetDateTime.parse("2026-08-24T22:30:00+09:00"));
    }
}
