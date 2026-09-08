package com.baedang.global.clients.kis;

import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.baedang.global.error.BusinessException;
import com.baedang.global.error.ErrorCode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public class KisSecuritiesClient {

    private static final long RATE_LIMIT_RETRY_MILLIS = 1_000L;

    private final RestClient restClient;
    private final KisProperties properties;
    private final KisRateLimiter rateLimiter;
    private final KisTokenProvider tokenProvider;
    private final ObjectMapper objectMapper;
    private final RetrySleeper retrySleeper;

    public KisSecuritiesClient(
            RestClient restClient,
            KisProperties properties,
            KisRateLimiter rateLimiter,
            KisTokenProvider tokenProvider,
            ObjectMapper objectMapper
    ) {
        this(restClient, properties, rateLimiter, tokenProvider, objectMapper,
                KisSecuritiesClient::sleep);
    }

    KisSecuritiesClient(
            RestClient restClient,
            KisProperties properties,
            KisRateLimiter rateLimiter,
            KisTokenProvider tokenProvider,
            ObjectMapper objectMapper,
            RetrySleeper retrySleeper
    ) {
        this.restClient = restClient;
        this.properties = properties;
        this.rateLimiter = rateLimiter;
        this.tokenProvider = tokenProvider;
        this.objectMapper = objectMapper;
        this.retrySleeper = retrySleeper;
    }

    public <T> T get(String path, Map<String, String> queryParams, Class<T> responseType) {
        KisWhitelist endpoint = KisWhitelist.resolve(path);
        if (endpoint == null) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }

        String requestToken = tokenProvider.getToken();
        boolean authenticationRetried = false;
        boolean rateLimitRetried = false;

        while (true) {
            try {
                JsonNode body = request(endpoint, queryParams, requestToken);
                validate(body);
                return objectMapper.treeToValue(body, responseType);
            } catch (HttpClientErrorException.Unauthorized
                     | HttpClientErrorException.Forbidden exception) {
                if (authenticationRetried) {
                    throw new BusinessException(ErrorCode.KIS_API_ERROR);
                }
                authenticationRetried = true;
                requestToken = tokenProvider.refreshIfStillStale(requestToken);
            } catch (HttpClientErrorException.TooManyRequests exception) {
                if (rateLimitRetried) {
                    throw new BusinessException(ErrorCode.KIS_RATE_LIMITED);
                }
                rateLimitRetried = true;
                sleepBeforeRetry();
            } catch (BusinessException exception) {
                if (exception.getErrorCode() != ErrorCode.KIS_RATE_LIMITED
                        || rateLimitRetried) {
                    throw exception;
                }
                rateLimitRetried = true;
                sleepBeforeRetry();
            } catch (JsonProcessingException exception) {
                throw new BusinessException(ErrorCode.KIS_API_ERROR);
            } catch (RestClientException exception) {
                throw new BusinessException(ErrorCode.KIS_API_ERROR);
            }
        }
    }

    private JsonNode request(
            KisWhitelist endpoint,
            Map<String, String> queryParams,
            String requestToken
    ) {
        MultiValueMap<String, String> query = new LinkedMultiValueMap<>();
        query.setAll(queryParams);
        rateLimiter.acquire();
        return restClient.get()
                .uri(uriBuilder -> uriBuilder.path(endpoint.path()).queryParams(query).build())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + requestToken)
                .header("appkey", properties.appKey())
                .header("appsecret", properties.appSecret())
                .header("tr_id", endpoint.trId())
                .header("custtype", "P")
                .retrieve()
                .body(JsonNode.class);
    }

    private void validate(JsonNode body) throws JsonProcessingException {
        if (body == null) {
            throw new BusinessException(ErrorCode.KIS_API_ERROR);
        }
        KisApiResponse response = objectMapper.treeToValue(body, KisApiResponse.class);
        if ("EGW00201".equals(response.msgCd())) {
            throw new BusinessException(ErrorCode.KIS_RATE_LIMITED);
        }
        if (!"0".equals(response.rtCd())) {
            throw new BusinessException(ErrorCode.KIS_API_ERROR);
        }
    }

    private void sleepBeforeRetry() {
        try {
            retrySleeper.sleep(RATE_LIMIT_RETRY_MILLIS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.KIS_API_ERROR);
        }
    }

    private static void sleep(long millis) throws InterruptedException {
        Thread.sleep(millis);
    }

    @FunctionalInterface
    interface RetrySleeper {
        void sleep(long millis) throws InterruptedException;
    }
}
