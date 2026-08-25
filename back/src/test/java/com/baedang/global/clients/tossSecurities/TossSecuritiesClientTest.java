package com.baedang.global.clients.tossSecurities;

import com.baedang.global.error.BusinessException;
import com.baedang.global.error.ErrorCode;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.client.RestClient;

import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link TossSecuritiesClient} 단위 테스트 — 실제 Toss 를 호출하지 않고 WireMock 으로
 * 응답을 스텁한다.
 *
 * <p><b>스텁이 Authorization 헤더로 분기하는 이유</b><br>
 * 이 클라이언트는 토큰을 미리 발급하지 않는다. 첫 요청은 {@code Bearer null} 로 나가고
 * <b>401 을 받은 뒤에야</b> 발급 → 재시도한다. 그래서 모든 응답을 200 으로 스텁하면
 * 토큰 발급 경로가 아예 실행되지 않는다 — 실제 Toss 처럼 유효하지 않은 토큰에 401 을
 * 주도록 스텁해야 인증 흐름이 검증된다.
 *
 * <p><b>기대 에러 코드가 전부 INTERNAL_ERROR 인 이유</b><br>
 * 현재 클라이언트는 화이트리스트 위반·Toss 오류·토큰 발급 실패를 모두 하나의 코드로
 * 던진다(설계상 "우선 하나의 5xx 로 통일"). 예외 코드만으로는 어느 지점에서 터졌는지
 * 구분되지 않으므로, 각 테스트는 <b>스텁 구성과 요청 횟수</b>로 경로를 특정한다.
 */
class TossSecuritiesClientTest {

    private static final String EXCHANGE_RATE = "/api/v1/exchange-rate";
    private static final String TOKEN_PATH = "/oauth2/token";
    private static final String VALID_TOKEN = "test-access-token";

    private static final String TOKEN_RESPONSE = """
            {
              "access_token": "test-access-token",
              "token_type": "Bearer",
              "expires_in": 86400
            }
            """;

    private static final String RATE_BODY = """
            { "baseCurrency": "USD", "quoteCurrency": "KRW" }
            """;

    private WireMockServer wireMockServer;
    private TossSecuritiesClient client;

    record TestBody(String baseCurrency, String quoteCurrency) {
    }

    @BeforeEach
    void setUp() {
        wireMockServer = new WireMockServer(options().dynamicPort());
        wireMockServer.start();
        configureFor("localhost", wireMockServer.port());

        client = new TossSecuritiesClient(
                RestClient.builder(),
                "http://localhost:" + wireMockServer.port(),
                "test-id",
                "test-secret"
        );
    }

    @AfterEach
    void tearDown() {
        wireMockServer.stop();
    }

    private static ErrorCode errorCodeOf(Throwable t) {
        return ((BusinessException) t).getErrorCode();
    }

    /**
     * 토큰 발급은 성공하고, 유효한 토큰일 때만 200 을 주는 스텁.
     */
    private void givenTokenIssued() {
        stubFor(post(urlEqualTo(TOKEN_PATH)).willReturn(okJson(TOKEN_RESPONSE)));
        stubFor(get(urlPathEqualTo(EXCHANGE_RATE))
                .withHeader("Authorization", notMatching("Bearer " + VALID_TOKEN))
                .willReturn(aResponse().withStatus(401)));
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  화이트리스트
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 임시 클라이언트는 경로를 enum 으로 받아 <b>잘못된 경로가 컴파일조차 되지 않았다.</b>
     * 본 구현은 String 을 받아 런타임 검사로 바꿨으므로, AGENTS.md 의
     * "주문 API 는 절대 호출하지 않는다" 규칙을 이제 이 그룹이 대신 지킨다.
     */
    @Nested
    @DisplayName("화이트리스트")
    class Whitelist {

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {
                "/api/v1/exchange-rate",            // 고정 경로
                "/api/v1/stocks/005930/warnings"    // {symbol} 패턴 경로
        })
        void 허용_경로는_그대로_요청된다(String path) {
            // 화이트리스트가 "막지 않는다"만 보는 게 아니라, 통과한 경로가 URI 로
            // 온전히 전달되는지까지 본다 — 경로를 문자열로 받는 구조라 여기서
            // 잘리거나 덧붙으면 엉뚱한 엔드포인트를 부르게 된다.
            stubFor(post(urlEqualTo(TOKEN_PATH)).willReturn(okJson(TOKEN_RESPONSE)));
            stubFor(get(urlPathEqualTo(path)).willReturn(okJson(RATE_BODY)));

            TestBody body = client.get(path, Map.of(), TestBody.class);

            assertThat(body.baseCurrency()).isEqualTo("USD");
            verify(1, getRequestedFor(urlPathEqualTo(path)));
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {
                "/api/v1/orders",
                "/api/v1/orders/12345",
                "/api/v1/stocks/005930/orders"
        })
        void 주문_계열_경로는_차단된다(String path) {
            assertThatThrownBy(() -> client.get(path, Map.of(), TestBody.class))
                    .as("주문 경로가 통과하면 실주문 위험: %s", path)
                    .isInstanceOf(BusinessException.class)
                    .extracting(TossSecuritiesClientTest::errorCodeOf)
                    .isEqualTo(ErrorCode.INTERNAL_ERROR);
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {
                "/api/v1/stocks/all",
                "/api/v1/price-limits"
        })
        void 미등록_경로는_차단된다(String path) {
            // 둘 다 docs/erd.md 에 있는 실제 Toss 경로지만 1주차 Port 범위 밖이라
            // 등록하지 않았다. "/api/v1/stocks" 가 등록됐다고 하위 경로가 새면 안 된다.
            assertThatThrownBy(() -> client.get(path, Map.of(), TestBody.class))
                    .isInstanceOf(BusinessException.class)
                    .extracting(TossSecuritiesClientTest::errorCodeOf)
                    .isEqualTo(ErrorCode.INTERNAL_ERROR);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  토큰 발급과 오류 처리
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("토큰 발급과 오류 처리")
    class TokenAndErrors {

        @Test
        void 토큰은_한번_발급받으면_재사용한다() {
            givenTokenIssued();
            stubFor(get(urlPathEqualTo(EXCHANGE_RATE))
                    .withHeader("Authorization", equalTo("Bearer " + VALID_TOKEN))
                    .willReturn(okJson(RATE_BODY)));

            client.get(EXCHANGE_RATE, Map.of(), TestBody.class);
            client.get(EXCHANGE_RATE, Map.of(), TestBody.class);
            client.get(EXCHANGE_RATE, Map.of(), TestBody.class);

            // 매 요청마다 발급받으면 AUTH 5 TPS 에 걸린다는 docs/erd.md 규칙 확인.
            verify(1, postRequestedFor(urlEqualTo(TOKEN_PATH)));
            // 첫 호출만 401 왕복(2회), 이후 두 번은 1회씩 = 4회
            verify(4, getRequestedFor(urlPathEqualTo(EXCHANGE_RATE)));
        }

        /**
         * 이 테스트가 직접 재현하는 건 <b>콜드 스타트</b>다 — {@code token} 필드가
         * {@code null} 인 채로 요청이 나가 401 을 받고, 발급 → 재시도한다.
         *
         * <p><b>만료 시나리오도 이 테스트가 함께 커버한다.</b> 들고 있던 토큰이 만료돼
         * 401 을 받는 경우와 콜드 스타트는 구현상 <b>같은 경로</b>를 지난다 —
         * {@code get()} 의 {@code catch (Unauthorized)} → {@code retryWithFreshToken()}
         * 하나뿐이고 이전 토큰 값에 따라 분기하지 않기 때문에, 만료용 테스트를 따로
         * 두어도 새로 커버되는 분기가 없다.
         *
         * <p>⚠️ 다만 <b>재시도가 재발급된 새 토큰으로 나가는지는 여기서 증명되지 않는다.</b>
         * 콜드 스타트에서는 직전 값이 {@code null} 이라 어떤 non-null 토큰이든 통과한다.
         * 그 검증까지 필요해지면 WireMock Scenario 로 토큰을 두 개 두고 분리해야 한다.
         */
        @Test
        void 토큰이_없거나_만료되면_재발급_후_재시도한다() {
            givenTokenIssued();
            stubFor(get(urlPathEqualTo(EXCHANGE_RATE))
                    .withHeader("Authorization", equalTo("Bearer " + VALID_TOKEN))
                    .willReturn(okJson(RATE_BODY)));

            TestBody body = client.get(EXCHANGE_RATE, Map.of(), TestBody.class);

            assertThat(body.baseCurrency()).isEqualTo("USD");
            assertThat(body.quoteCurrency()).isEqualTo("KRW");
            verify(1, postRequestedFor(urlEqualTo(TOKEN_PATH)));
            verify(2, getRequestedFor(urlPathEqualTo(EXCHANGE_RATE)));
        }

        @Test
        void 토큰_발급_요청_실패는_감싼_예외로_변환한다() {
            // 잘못된 Client ID/Secret 이거나 토큰 엔드포인트 장애인 상황.
            stubFor(get(urlPathEqualTo(EXCHANGE_RATE)).willReturn(aResponse().withStatus(401)));
            stubFor(post(urlEqualTo(TOKEN_PATH)).willReturn(aResponse().withStatus(500)));

            assertThatThrownBy(() -> client.get(EXCHANGE_RATE, Map.of(), TestBody.class))
                    .isInstanceOf(BusinessException.class)
                    .extracting(TossSecuritiesClientTest::errorCodeOf)
                    .isEqualTo(ErrorCode.INTERNAL_ERROR);

            verify(1, postRequestedFor(urlEqualTo(TOKEN_PATH)));
        }

        @Test
        void 첫_요청_실패는_감싼_예외로_변환한다() {
            // 401 이 아닌 실패(여기서는 5xx)는 재발급 경로를 타지 않고 바로 변환된다.
            stubFor(get(urlPathEqualTo(EXCHANGE_RATE)).willReturn(aResponse().withStatus(500)));

            assertThatThrownBy(() -> client.get(EXCHANGE_RATE, Map.of(), TestBody.class))
                    .isInstanceOf(BusinessException.class)
                    .extracting(TossSecuritiesClientTest::errorCodeOf)
                    .isEqualTo(ErrorCode.INTERNAL_ERROR);

            // 토큰 발급을 시도하지 않았다는 사실이 재시도 경로가 아님을 증명한다.
            verify(0, postRequestedFor(urlEqualTo(TOKEN_PATH)));
            verify(1, getRequestedFor(urlPathEqualTo(EXCHANGE_RATE)));
        }

        @Test
        void 재시도_실패는_감싼_예외로_변환하고_반복하지_않는다() {
            // 재시도 실패는 401 에 한정되지 않는다 — 401·429·5xx·네트워크 단절이 모두
            // 같은 catch 로 들어오므로, 401 이 아닌 실패를 대표값으로 쓴다.
            givenTokenIssued();
            stubFor(get(urlPathEqualTo(EXCHANGE_RATE))
                    .withHeader("Authorization", equalTo("Bearer " + VALID_TOKEN))
                    .willReturn(aResponse().withStatus(500)));

            assertThatThrownBy(() -> client.get(EXCHANGE_RATE, Map.of(), TestBody.class))
                    .isInstanceOf(BusinessException.class)
                    .extracting(TossSecuritiesClientTest::errorCodeOf)
                    .isEqualTo(ErrorCode.INTERNAL_ERROR);

            // 재발급 1회, 재시도 1회에서 멈춘다 — 무한 재시도 방지.
            verify(1, postRequestedFor(urlEqualTo(TOKEN_PATH)));
            verify(2, getRequestedFor(urlPathEqualTo(EXCHANGE_RATE)));
        }

        @ParameterizedTest(name = "본문 = {0}")
        @ValueSource(strings = {"", "{}"})
        void 토큰_없는_발급_응답은_감싼_예외로_변환한다(String tokenBody) {
            // 두 입력이 서로 다른 분기를 탄다 —
            //   ""   → 본문이 비어 응답 객체 자체가 null
            //   "{}" → 200 JSON 이지만 access_token 필드가 없음
            stubFor(get(urlPathEqualTo(EXCHANGE_RATE)).willReturn(aResponse().withStatus(401)));
            stubFor(post(urlEqualTo(TOKEN_PATH)).willReturn(aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(tokenBody)));

            assertThatThrownBy(() -> client.get(EXCHANGE_RATE, Map.of(), TestBody.class))
                    .isInstanceOf(BusinessException.class)
                    .extracting(TossSecuritiesClientTest::errorCodeOf)
                    .isEqualTo(ErrorCode.INTERNAL_ERROR);
        }
    }
}
