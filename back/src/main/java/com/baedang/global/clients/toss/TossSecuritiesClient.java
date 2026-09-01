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

    private static final Logger logger = LoggerFactory.getLogger(TossSecuritiesClient.class);

    private final RestClient restClient;
    private final TossRateLimiterRegistry rateLimiterRegistry;
    private final String clientId;
    private final String clientSecret;
    private final Object tokenLock = new Object();
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

        try {
            return request(path, queryParams, token, responseType, group);
        } catch (HttpClientErrorException.Unauthorized exception) {
            return retryWithFreshToken(path, queryParams, responseType, group, token);
        } catch (HttpClientErrorException.TooManyRequests e) {
            logger.warn("Toss rate limited: group={} path={}", group, path);
            throw new BusinessException(ErrorCode.TOSS_RATE_LIMITED);
        } catch (RestClientException e) {
            logger.warn("Toss request failed: group={} path={}", group, path, e);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }
    }

    private TossApiGroup resolveGroupOrThrow(String path) {
        Whitelist endPoint = Whitelist.resolve(path);
        if (endPoint == null) {
            logger.error("`{}` does not match white list.", path);
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
            String path,
            MultiValueMap<String, String> queryParams,
            Class<T> responseType,
            TossApiGroup group,
            String failedToken
    ) {
        refreshTokenIfNeeded(failedToken);
        try {
            return request(path, queryParams, token, responseType, group);
        } catch (HttpClientErrorException.TooManyRequests e) {
            logger.warn("Toss rate limited: group={} path={}", group, path);
            throw new BusinessException(ErrorCode.TOSS_RATE_LIMITED);
        } catch (RestClientException e) {
            logger.error("Toss retry failed: group={} path={}", group, path, e);
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

    private BusinessException convertException(String path, TossApiGroup group, RestClientException e) {
        if(e instanceof HttpClientErrorException.TooManyRequests) {
            logger.warn("Toss rate limited: group={} path={}", group, path);
            throw new BusinessException(ErrorCode.TOSS_RATE_LIMITED);
        }
        logger.error("Toss request failed: group={} path={}", group, path, e);
        throw new BusinessException(ErrorCode.INTERNAL_ERROR);
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

            if (response == null || response.accessToken == null) {
                logger.error("`response` or `response.accessToken` is null.");
                throw new BusinessException(ErrorCode.INTERNAL_ERROR);
            }
            return response.accessToken;
        } catch (RestClientException e) {
            logger.error("`issueToken()` error:", e);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }
    }
}
