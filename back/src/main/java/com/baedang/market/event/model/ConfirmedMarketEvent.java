package com.baedang.market.event.model;

import com.baedang.market.event.entity.KrMarket;
import com.baedang.market.event.entity.MarketEventType;
import com.baedang.market.event.entity.SidecarDirection;

import java.net.URI;
import java.time.Instant;
import java.util.Objects;

public record ConfirmedMarketEvent(
        String sourceEventId,
        KrMarket market,
        MarketEventType eventType,
        Integer circuitBreakerStage,
        SidecarDirection sidecarDirection,
        Instant triggeredAt,
        Instant publishedAt,
        Instant receivedAt,
        String title,
        URI sourceUrl
) {
    public ConfirmedMarketEvent {
        Objects.requireNonNull(sourceEventId, "sourceEventId must not be null");
        Objects.requireNonNull(market, "market must not be null");
        Objects.requireNonNull(eventType, "eventType must not be null");
        Objects.requireNonNull(triggeredAt, "triggeredAt must not be null");
        Objects.requireNonNull(publishedAt, "publishedAt must not be null");
        Objects.requireNonNull(receivedAt, "receivedAt must not be null");
        Objects.requireNonNull(title, "title must not be null");
        Objects.requireNonNull(sourceUrl, "sourceUrl must not be null");

        if (!sourceEventId.matches("\\d{14}")) {
            throw new IllegalArgumentException("sourceEventId는 14자리 숫자여야 합니다: " + sourceEventId);
        }
        if (title.isBlank() || title.length() > 300) {
            throw new IllegalArgumentException("title은 1~300자여야 합니다");
        }
        if (!"https".equalsIgnoreCase(sourceUrl.getScheme()) || sourceUrl.getHost() == null) {
            throw new IllegalArgumentException("sourceUrl은 HTTPS URL이어야 합니다: " + sourceUrl);
        }
        if (eventType == MarketEventType.CIRCUIT_BREAKER) {
            if (circuitBreakerStage == null || circuitBreakerStage < 1 || circuitBreakerStage > 3 || sidecarDirection != null) {
                throw new IllegalArgumentException("유효하지 않은 서킷브레이커 이벤트 페이로드입니다");
            }
        } else if (eventType == MarketEventType.SIDECAR) {
            if (circuitBreakerStage != null || sidecarDirection == null) {
                throw new IllegalArgumentException("유효하지 않은 사이드카 이벤트 페이로드입니다");
            }
        }
    }
}
