package com.baedang.global.clients.kis;

import java.util.Map;
import java.util.EnumMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

public class KisSecuritiesClient {

    private static final Logger log = LoggerFactory.getLogger(KisSecuritiesClient.class);

    private static final long RATE_LIMIT_RETRY_MILLIS = 1_000L;

    private final RestClient restClient;
    private final KisProperties properties;
    private final KisRateLimiter rateLimiter;
    private final KisTokenProvider tokenProvider;
    private final ObjectMapper objectMapper;
    private final RetrySleeper retrySleeper;
    private final EnumMap<KisWhitelist, EnumMap<RequestResult, Counter>> requestCounters;

    public KisSecuritiesClient(
            RestClient restClient,
            KisProperties properties,
            KisRateLimiter rateLimiter,
            KisTokenProvider tokenProvider,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry
    ) {
        this(restClient, properties, rateLimiter, tokenProvider, objectMapper, meterRegistry,
                KisSecuritiesClient::sleep);
    }

    KisSecuritiesClient(
            RestClient restClient,
            KisProperties properties,
            KisRateLimiter rateLimiter,
            KisTokenProvider tokenProvider,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry,
            RetrySleeper retrySleeper
    ) {
        this.restClient = restClient;
        this.properties = properties;
        this.rateLimiter = rateLimiter;
        this.tokenProvider = tokenProvider;
        this.objectMapper = objectMapper;
        this.retrySleeper = retrySleeper;
        this.requestCounters = counters(meterRegistry);
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
                validate(endpoint, body);
                T response = objectMapper.treeToValue(body, responseType);
                record(endpoint, RequestResult.SUCCESS);
                return response;
            } catch (HttpClientErrorException.Unauthorized
                     | HttpClientErrorException.Forbidden exception) {
                record(endpoint, RequestResult.ERROR);
                if (authenticationRetried) {
                    throw failure(ErrorCode.KIS_API_ERROR, endpoint,
                            "HTTP_" + exception.getStatusCode().value());
                }
                authenticationRetried = true;
                requestToken = tokenProvider.refreshIfStillStale(requestToken);
            } catch (HttpClientErrorException.TooManyRequests exception) {
                record(endpoint, RequestResult.RATE_LIMITED);
                if (rateLimitRetried) {
                    throw failure(ErrorCode.KIS_RATE_LIMITED, endpoint, "HTTP_429");
                }
                rateLimitRetried = true;
                sleepBeforeRetry();
            } catch (BusinessException exception) {
                record(endpoint, exception.getErrorCode() == ErrorCode.KIS_RATE_LIMITED
                        ? RequestResult.RATE_LIMITED
                        : RequestResult.ERROR);
                if (exception.getErrorCode() != ErrorCode.KIS_RATE_LIMITED
                        || rateLimitRetried) {
                    throw exception;
                }
                rateLimitRetried = true;
                sleepBeforeRetry();
            } catch (JsonProcessingException exception) {
                record(endpoint, RequestResult.ERROR);
                throw failure(ErrorCode.KIS_API_ERROR, endpoint, "INVALID_RESPONSE");
            } catch (RestClientException exception) {
                record(endpoint, RequestResult.ERROR);
                throw failure(ErrorCode.KIS_API_ERROR, endpoint, "TRANSPORT_ERROR");
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

    private void validate(KisWhitelist endpoint, JsonNode body) throws JsonProcessingException {
        if (body == null) {
            throw failure(ErrorCode.KIS_API_ERROR, endpoint, "EMPTY_RESPONSE");
        }
        KisApiResponse response = objectMapper.treeToValue(body, KisApiResponse.class);
        if ("EGW00201".equals(response.msgCd())) {
            throw failure(ErrorCode.KIS_RATE_LIMITED, endpoint, response.msgCd());
        }
        if (!"0".equals(response.rtCd())) {
            throw failure(ErrorCode.KIS_API_ERROR, endpoint, response.msgCd());
        }
    }

    private static BusinessException failure(
            ErrorCode errorCode, KisWhitelist endpoint, String msgCode) {
        String detail = "endpoint=" + endpoint.name()
                + " trId=" + endpoint.trId()
                + " msgCd=" + safeMsgCode(msgCode);
        return new BusinessException(errorCode, detail);
    }

    private static String safeMsgCode(String msgCode) {
        if (msgCode == null || msgCode.isBlank()) {
            return "NONE";
        }
        if (msgCode.length() > 64 || !msgCode.matches("[A-Za-z0-9_-]+")) {
            log.warn("KIS 응답의 msg_cd 형식이 올바르지 않습니다: endpointContext=redacted");
            return "INVALID";
        }
        return msgCode;
    }

    private void sleepBeforeRetry() {
        try {
            retrySleeper.sleep(RATE_LIMIT_RETRY_MILLIS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.KIS_API_ERROR);
        }
    }

    private void record(KisWhitelist endpoint, RequestResult result) {
        requestCounters.get(endpoint).get(result).increment();
    }

    private static EnumMap<KisWhitelist, EnumMap<RequestResult, Counter>> counters(
            MeterRegistry meterRegistry
    ) {
        EnumMap<KisWhitelist, EnumMap<RequestResult, Counter>> counters =
                new EnumMap<>(KisWhitelist.class);
        for (KisWhitelist endpoint : KisWhitelist.values()) {
            EnumMap<RequestResult, Counter> results = new EnumMap<>(RequestResult.class);
            for (RequestResult result : RequestResult.values()) {
                results.put(result, Counter.builder("kis.api.requests")
                        .tag("endpoint", endpoint.name())
                        .tag("result", result.tag)
                        .register(meterRegistry));
            }
            counters.put(endpoint, results);
        }
        return counters;
    }

    private enum RequestResult {
        SUCCESS("success"),
        ERROR("error"),
        RATE_LIMITED("rate_limited");

        private final String tag;

        RequestResult(String tag) {
            this.tag = tag;
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
