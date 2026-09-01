package com.baedang.global.clients.toss;

import com.baedang.global.error.BusinessException;
import com.baedang.global.error.ErrorCode;
import com.fasterxml.jackson.annotation.JsonProperty;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Map;
import java.util.Objects;

@Component
public class TossSecuritiesClient {

    private static final Logger log = LoggerFactory.getLogger(TossSecuritiesClient.class);

    private final RestClient restClient;
    private final TossRateLimiterRegistry rateLimiterRegistry;
    private final String clientId;
    private final String clientSecret;
<<<<<<< HEAD
    private final Object tokenLock = new Object();
=======

    // 여러 스케줄러(QuoteSnapshotScheduler, MinuteCandleCollectionScheduler 등)가
    // 이 클라이언트를 싱글톤으로 공유하며 서로 다른 스레드에서 동시에 get()을 부른다.
    // volatile이 없으면 한 스레드가 갱신한 토큰이 다른 스레드에 안 보일 수 있다(JMM
    // 가시성 문제) — 최악의 경우 만료된 토큰으로 계속 401을 받는다.
>>>>>>> origin/develop
    private volatile String token;

    public TossSecuritiesClient(
            RestClient.Builder builder,
            TossRateLimiterRegistry rateLimiterRegistry,
            @Value("${toss.base-url}") String baseUrl,
            @Value("${toss.client-id}") String clientId,
            @Value("${toss.client-secret}") String clientSecret
    ) {
        this.restClient = builder.baseUrl(baseUrl).build();
        this.rateLimiterRegistry = rateLimiterRegistry;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
    }

    public <T> T get(String path, Map<String, String> queryParams, Class<T> responseType) {
        MultiValueMap<String, String> multiValueMap = new LinkedMultiValueMap<>();
        multiValueMap.setAll(queryParams);
        return get(path, multiValueMap, responseType);
    }

    public <T> T get(String path, MultiValueMap<String, String> queryParams, Class<T> responseType) {
        TossApiGroup group = resolveGroupOrThrow(path);

        String requestToken = token;

        String currentToken = token;
        try {
<<<<<<< HEAD
            return request(path, queryParams, requestToken, responseType, group);
        } catch (HttpClientErrorException.Unauthorized exception) {
            return retryWithFreshToken(path, queryParams, responseType, group, requestToken);
        } catch (HttpClientErrorException.TooManyRequests e) {
            logger.warn("Toss rate limited: group={} path={}", group, path);
            throw new BusinessException(ErrorCode.TOSS_RATE_LIMITED);
        } catch (RestClientException e) {
            logger.warn("Toss request failed: group={} path={}", group, path, e);
=======
            return _get(path, queryParams, currentToken, responseType);
        } catch (HttpClientErrorException.Unauthorized exception) {
            return retryWithFreshToken(path, queryParams, currentToken, responseType);
        } catch (RestClientException e) {
            log.error("`get({}, ...)` error:", path, e);
>>>>>>> origin/develop
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }
    }

<<<<<<< HEAD
    private TossApiGroup resolveGroupOrThrow(String path) {
        Whitelist endPoint = Whitelist.resolve(path);
        if (endPoint == null) {
            logger.error("`{}` does not match white list.", path);
=======
    private void validatePathOrThrow(String path) {
        if (!Whitelist.match(path)) {
            log.error("`{}` does not match white list.", path);
>>>>>>> origin/develop
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }
        return endPoint.group();
    }
    /** 실제 HTTP 전송 직전에 permit 을 획득한다 — 재시도도 이 경로를 다시 지난다. */
    private <T> T request(
            String path,
            MultiValueMap<String, String> queryParams,
            String requestToken,
            Class<T> responseType,
            TossApiGroup group
    ) {
        rateLimiterRegistry.acquire(group);
        return restClient.get()
                .uri(uriBuilder -> uriBuilder.path(path).queryParams(queryParams).build())
                .header(HttpHeaders.AUTHORIZATION,"Bearer " + requestToken)
                .retrieve()
                .body(responseType);
    }

    private <T> T retryWithFreshToken(
<<<<<<< HEAD
            String path,
            MultiValueMap<String, String> queryParams,
            Class<T> responseType,
            TossApiGroup group,
            String failedToken
    ) {
        refreshTokenIfNeeded(failedToken);
        String retryToken = token;
        try {
            return request(path, queryParams, retryToken, responseType, group);
        } catch (HttpClientErrorException.TooManyRequests e) {
            logger.warn("Toss rate limited: group={} path={}", group, path);
            throw new BusinessException(ErrorCode.TOSS_RATE_LIMITED);
        } catch (RestClientException e) {
            logger.error("Toss retry failed: group={} path={}", group, path, e);
=======
            String path, MultiValueMap<String, String> queryParams, String staleToken, Class<T> responseType
    ) {
        String freshToken = refreshTokenIfStillStale(staleToken);
        try {
            return _get(path, queryParams, freshToken, responseType);
        } catch (RestClientException e) {
            log.error("`retryWithFreshToken({}, ...)` error:", path, e);
>>>>>>> origin/develop
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }
    }
    /** 동시에 여러 요청이 401 을 받아도 토큰 발급은 한 번만 수행한다. */
    private void refreshTokenIfNeeded(String failedToken) {
        if(!Objects.equals(token, failedToken)) return;
        synchronized (tokenLock) {
            if(!Objects.equals(token, failedToken)) return;
            token = issueToken();
        }
    }

    /**
     * 여러 스레드가 동시에 401을 만나도 실제 토큰 재발급은 한 번만 일어나게 한다.
     *
     * <p>{@code staleToken}은 그 스레드가 401을 받았을 때 들고 있던 토큰이다. 이 메서드에
     * 들어왔을 때 {@link #token}이 이미 그 값과 달라져 있다면, 그 사이 다른 스레드가
     * 먼저 재발급을 끝낸 것이므로 그 값을 그대로 재사용하고 새로 발급받지 않는다.
     * {@code synchronized}로 감싸 두 스레드가 동시에 "아직 안 바뀌었다"고 판단해
     * 토큰을 두 번 발급하는 경합을 막는다 — 토큰 발급도 Toss API 호출이라 불필요한
     * 중복 호출은 요청 한도를 그만큼 더 소모시킨다.
     */
    private synchronized String refreshTokenIfStillStale(String staleToken) {
        if (!Objects.equals(token, staleToken)) {
            return token;
        }
        token = issueToken();
        return token;
    }

    private record TokenResponse(
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("token_type") String tokenType,
            @JsonProperty("expires_in") Long expiresIn
    ) {
    }

    private String issueToken() {
        rateLimiterRegistry.acquire(TossApiGroup.AUTH);

        try {
            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("grant_type", "client_credentials");
            form.add("client_id", clientId);
            form.add("client_secret", clientSecret);

            TokenResponse response = restClient.post()
                    .uri("/oauth2/token")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(TokenResponse.class);

<<<<<<< HEAD
            if (response == null || response.accessToken() == null) {
                logger.error("`response` or `response.accessToken` is null.");
=======
            if (response == null || response.accessToken == null) {
                log.error("`response` or `response.accessToken` is null.");
>>>>>>> origin/develop
                throw new BusinessException(ErrorCode.INTERNAL_ERROR);
            }
            return response.accessToken();
        } catch (HttpClientErrorException.TooManyRequests e) {
            logger.warn("Toss rate limited: group={} path={}",TossApiGroup.AUTH, "/oauth2/token");
            throw new BusinessException(ErrorCode.TOSS_RATE_LIMITED);
        } catch (RestClientException e) {
            log.error("`issueToken()` error:", e);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }
    }
}
