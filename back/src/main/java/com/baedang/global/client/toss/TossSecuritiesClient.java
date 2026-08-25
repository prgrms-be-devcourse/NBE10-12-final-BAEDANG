package com.baedang.global.client.toss;

import com.baedang.global.error.BusinessException;
import com.baedang.global.error.ErrorCode;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.Map;

/**
 * Toss Securities Open API 전용 저수준 클라이언트.
 *
 * <p><b>모든 Toss 어댑터는 이 클래스를 통해서만 Toss 를 호출해야 합니다</b> — 직접
 * {@code RestClient}/{@code HttpClient} 를 새로 만들지 마세요. 이 클래스가 하는 일은
 * 딱 두 가지뿐이고, 어떤 데이터인지는 전혀 모릅니다:
 * <ol>
 *   <li>OAuth2 client_credentials 토큰 발급 + 메모리 캐싱 (만료 전 자동 재발급)</li>
 *   <li>{@link TossPathWhitelist} 에 등록된 경로만 호출 허용</li>
 * </ol>
 *
 * <p>Toss 응답 DTO 로의 매핑, 도메인 모델 변환은 전부 각 Adapter( {@code TossMarketCalendarAdapter}
 * 등) 의 몫입니다 — 이 클래스는 그 형태를 몰라야 합니다.
 *
 * <p><b>임시 구현 안내</b> — 이 클래스는 팀 회의(08/24)에서 초안이 나온 공용 인프라입니다.
 * 선행 작업 담당자(호영)가 실제 버전을 완성해서 병합하면 이 파일을 교체/흡수하면 됩니다.
 * 지금은 {@code MarketCalendarPort} 구현을 막지 않기 위한 임시 버전입니다.
 */
@Component
@EnableConfigurationProperties(TossApiProperties.class)
public class TossSecuritiesClient {

    /** 토큰 만료 시각보다 이만큼 일찍 재발급한다 — 요청 도중 만료되는 걸 방지. */
    private static final long REFRESH_BUFFER_SECONDS = 60;

    private final RestClient restClient;
    private final TossApiProperties properties;

    private volatile CachedToken cachedToken;

    public TossSecuritiesClient(RestClient.Builder builder, TossApiProperties properties) {
        this.properties = properties;
        this.restClient = builder
                .baseUrl(properties.baseUrl())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    /**
     * GET 요청. {@code endpoint} 는 반드시 {@link TossPathWhitelist} 상수여야 하므로,
     * 화이트리스트에 없는 경로를 호출하는 코드는 애초에 컴파일되지 않습니다.
     *
     * @param endpoint     호출할 경로 (화이트리스트)
     * @param pathVariables {@code {symbol}} 같은 경로 변수 (없으면 {@code Map.of()})
     * @param queryParams  쿼리 파라미터
     * @param responseType 응답을 매핑할 DTO 타입 — Toss 원본 응답 형태 그대로, Adapter 가 정의
     */
    public <T> T get(TossPathWhitelist endpoint, Map<String, String> pathVariables,
                      Map<String, String> queryParams, Class<T> responseType) {
        String accessToken = getAccessToken();

        return restClient.get()
                .uri(uriBuilder -> {
                    var b = uriBuilder.path(endpoint.path());
                    queryParams.forEach(b::queryParam);
                    return b.build(pathVariables);
                })
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .retrieve()
                .onStatus(status -> status.value() == 429,
                        (req, res) -> {
                            throw new BusinessException(ErrorCode.TOSS_RATE_LIMITED,
                                    endpoint.path() + " rate limited");
                        })
                .onStatus(HttpStatusCode::isError,
                        (req, res) -> {
                            throw new BusinessException(ErrorCode.TOSS_API_ERROR,
                                    endpoint.path() + " → " + res.getStatusCode());
                        })
                .body(responseType);
    }

    /** 경로 변수가 없는 경로용 오버로드. */
    public <T> T get(TossPathWhitelist endpoint, Map<String, String> queryParams, Class<T> responseType) {
        return get(endpoint, Map.of(), queryParams, responseType);
    }

    /**
     * 캐시된 access token 을 반환한다. 만료 임박(60초 이내)이면 재발급한다.
     *
     * <p>{@code synchronized} 인 이유 — 토큰은 24시간 정도 유효해서 경합이 사실상
     * 없다시피 하지만, 여러 스케줄러가 동시에 만료 시점에 걸리면 각자 재발급을
     * 시도해 {@code /oauth2/token} 을 불필요하게 여러 번 부를 수 있다. 단순
     * synchronized 로도 충분하다 — 호출 빈도가 하루 한두 번 수준이라 경합 비용이 없다.
     */
    private synchronized String getAccessToken() {
        Instant now = Instant.now();
        if (cachedToken != null && now.isBefore(cachedToken.expiresAt().minusSeconds(REFRESH_BUFFER_SECONDS))) {
            return cachedToken.accessToken();
        }

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", properties.clientId());
        form.add("client_secret", properties.clientSecret());

        TossTokenResponse response;
        try {
            response = restClient.post()
                    .uri("/oauth2/token")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(TossTokenResponse.class);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.TOSS_API_ERROR, "토큰 발급 실패: " + e.getMessage());
        }

        if (response == null || response.accessToken() == null) {
            throw new BusinessException(ErrorCode.TOSS_API_ERROR, "토큰 발급 응답이 비어 있음");
        }

        cachedToken = new CachedToken(response.accessToken(), now.plusSeconds(response.expiresIn()));
        return cachedToken.accessToken();
    }

    private record CachedToken(String accessToken, Instant expiresAt) {
    }

    /** OAuth2 표준(RFC 6749) 이라 JSON 필드가 snake_case 로 온다 — 명시적으로 매핑. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record TossTokenResponse(
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("token_type") String tokenType,
            @JsonProperty("expires_in") long expiresIn
    ) {
    }
}
