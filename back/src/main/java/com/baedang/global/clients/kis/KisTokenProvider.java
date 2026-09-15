package com.baedang.global.clients.kis;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.baedang.global.error.BusinessException;
import com.baedang.global.error.ErrorCode;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

public class KisTokenProvider {

    private static final String TOKEN_PATH = "/oauth2/tokenP";
    private static final Duration FAILURE_COOLDOWN = Duration.ofSeconds(65);
    private static final Duration REFRESH_MARGIN = Duration.ofMinutes(5);
    private static final DateTimeFormatter KIS_EXPIRED_AT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final ZoneId KIS_ZONE = ZoneId.of("Asia/Seoul");

    private final RestClient restClient;
    private final KisProperties properties;
    private final Clock clock;
    private final Counter issuedSuccessCounter;
    private final Counter issuedErrorCounter;

    private volatile TokenState state;
    private Instant lastFailureAt;

    public KisTokenProvider(
            RestClient restClient,
            KisProperties properties,
            Clock clock,
            MeterRegistry meterRegistry
    ) {
        this.restClient = restClient;
        this.properties = properties;
        this.clock = clock;
        this.issuedSuccessCounter = Counter.builder("kis.token.issued")
                .tag("result", "success")
                .register(meterRegistry);
        this.issuedErrorCounter = Counter.builder("kis.token.issued")
                .tag("result", "error")
                .register(meterRegistry);
    }

    public synchronized String getToken() {
        if (usable(state)) {
            return state.value();
        }
        return issueAndStore();
    }

    public synchronized String refreshIfStillStale(String staleToken) {
        if (usable(state) && !state.value().equals(staleToken)) {
            return state.value();
        }
        return issueAndStore();
    }

    private String issueAndStore() {
        Instant now = clock.instant();
        if (lastFailureAt != null
                && now.isBefore(lastFailureAt.plus(FAILURE_COOLDOWN))) {
            throw new BusinessException(ErrorCode.KIS_API_ERROR);
        }

        try {
            TokenResponse response = restClient.post()
                    .uri(TOKEN_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "grant_type", "client_credentials",
                            "appkey", properties.appKey(),
                            "appsecret", properties.appSecret()))
                    .retrieve()
                    .body(TokenResponse.class);
            if (response == null || response.accessToken() == null
                    || response.accessToken().isBlank()) {
                throw new BusinessException(ErrorCode.KIS_API_ERROR);
            }

            Instant expiresAt = resolveExpiresAt(response, now);
            state = new TokenState(response.accessToken(), expiresAt);
            lastFailureAt = null;
            issuedSuccessCounter.increment();
            return response.accessToken();
        } catch (BusinessException exception) {
            issuedErrorCounter.increment();
            lastFailureAt = now;
            throw exception;
        } catch (RestClientException | DateTimeParseException exception) {
            issuedErrorCounter.increment();
            lastFailureAt = now;
            throw new BusinessException(ErrorCode.KIS_API_ERROR);
        }
    }

    private Instant resolveExpiresAt(TokenResponse response, Instant now) {
        if (response.accessTokenExpired() != null
                && !response.accessTokenExpired().isBlank()) {
            LocalDateTime localDateTime = LocalDateTime.parse(
                    response.accessTokenExpired(), KIS_EXPIRED_AT);
            return localDateTime.atZone(KIS_ZONE).toInstant();
        }
        if (response.expiresIn() == null || response.expiresIn() <= 0) {
            throw new BusinessException(ErrorCode.KIS_API_ERROR);
        }
        return now.plusSeconds(response.expiresIn());
    }

    private boolean usable(TokenState tokenState) {
        return tokenState != null
                && clock.instant().isBefore(tokenState.expiresAt().minus(REFRESH_MARGIN));
    }

    private record TokenState(String value, Instant expiresAt) {
    }

    public record TokenResponse(
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("token_type") String tokenType,
            @JsonProperty("expires_in") Long expiresIn,
            @JsonProperty("access_token_token_expired") String accessTokenExpired
    ) {
    }
}
