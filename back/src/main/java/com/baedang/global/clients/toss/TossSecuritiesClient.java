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

@Component
public class TossSecuritiesClient {

    private static final Logger log = LoggerFactory.getLogger(TossSecuritiesClient.class);

    private final RestClient restClient;
    private final String clientId;
    private final String clientSecret;
    private String token;

    public TossSecuritiesClient(
            RestClient.Builder builder,
            @Value("${toss.base-url}") String baseUrl,
            @Value("${toss.client-id}") String clientId,
            @Value("${toss.client-secret}") String clientSecret
    ) {
        this.restClient = builder.baseUrl(baseUrl).build();
        this.clientId = clientId;
        this.clientSecret = clientSecret;
    }

    public <T> T get(String path, Map<String, String> queryParams, Class<T> responseType) {
        MultiValueMap<String, String> multiValueMap = new LinkedMultiValueMap<>();
        multiValueMap.setAll(queryParams);
        return get(path, multiValueMap, responseType);
    }

    public <T> T get(String path, MultiValueMap<String, String> queryParams, Class<T> responseType) {
        validatePathOrThrow(path);

        try {
            return _get(path, queryParams, token, responseType);
        } catch (HttpClientErrorException.Unauthorized exception) {
            return retryWithFreshToken(path, queryParams, responseType);
        } catch (RestClientException e) {
            log.error("`get({}, ...)` error:", path, e);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }
    }

    private void validatePathOrThrow(String path) {
        if (!Whitelist.match(path)) {
            log.error("`{}` does not match white list.", path);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }
    }

    private <T> T _get(String path, MultiValueMap<String, String> queryParams, String token, Class<T> responseType) {
        return restClient.get()
                .uri(uriBuilder -> uriBuilder.path(path).queryParams(queryParams).build())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .retrieve()
                .body(responseType);
    }

    private <T> T retryWithFreshToken(String path, MultiValueMap<String, String> queryParams, Class<T> responseType) {
        token = issueToken();
        try {
            return _get(path, queryParams, token, responseType);
        } catch (RestClientException e) {
            log.error("`retryWithFreshToken({}, ...)` error:", path, e);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }
    }

    private record TokenResponse(
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("token_type") String tokenType,
            @JsonProperty("expires_in") Long expiresIn
    ) {
    }

    private String issueToken() {
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
                log.error("`response` or `response.accessToken` is null.");
                throw new BusinessException(ErrorCode.INTERNAL_ERROR);
            }
            return response.accessToken;
        } catch (RestClientException e) {
            log.error("`issueToken()` error:", e);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }
    }
}
