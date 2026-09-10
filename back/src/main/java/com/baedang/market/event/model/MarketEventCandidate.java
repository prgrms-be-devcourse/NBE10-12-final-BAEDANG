package com.baedang.market.event.model;

import com.baedang.market.event.entity.KrMarket;
import com.baedang.market.event.entity.MarketEventType;
import com.baedang.market.event.entity.SidecarDirection;

import java.net.URI;
import java.time.Instant;
import java.util.Objects;

public record MarketEventCandidate(
        KrMarket market,
        String sourceEventId,
        MarketEventType eventType,
        Integer circuitBreakerStage,
        SidecarDirection sidecarDirection,
        Instant publishedAt,
        String title,
        URI viewerUrl
) {
    public MarketEventCandidate {
        Objects.requireNonNull(market, "market must not be null");
        Objects.requireNonNull(sourceEventId, "sourceEventId must not be null");
        Objects.requireNonNull(title, "title must not be null");
        if (title.isBlank() || title.length() > 300) {
            throw new IllegalArgumentException("title must be nonblank and at most 300 characters");
        }
        Objects.requireNonNull(publishedAt, "publishedAt must not be null");
        Objects.requireNonNull(viewerUrl, "viewerUrl must not be null");
        if (!sourceEventId.matches("\\d{14}")) {
            throw new IllegalArgumentException("invalid acptNo: " + sourceEventId);
        }
        if (eventType == MarketEventType.CIRCUIT_BREAKER
                && (circuitBreakerStage == null || sidecarDirection != null)) {
            throw new IllegalArgumentException("invalid circuit breaker candidate");
        }
        if (eventType == MarketEventType.SIDECAR
                && (circuitBreakerStage != null || sidecarDirection == null)) {
            throw new IllegalArgumentException("invalid sidecar candidate");
        }
    }
}
