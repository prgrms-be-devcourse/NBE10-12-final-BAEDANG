package com.baedang.market.event.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.net.URI;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;

@Entity
@Table(name = "market_event")
public class MarketEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "market_event_id")
    private Long marketEventId;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 20)
    private MarketEventSource source;

    @Column(name = "source_event_id", nullable = false, length = 20)
    private String sourceEventId;

    @Enumerated(EnumType.STRING)
    @Column(name = "market", nullable = false, length = 10)
    private KrMarket market;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 30)
    private MarketEventType eventType;

    private Short circuitBreakerStage;

    @Enumerated(EnumType.STRING)
    @Column(name = "sidecar_direction", length = 4)
    private SidecarDirection sidecarDirection;

    @Column(name = "triggered_at", nullable = false)
    private OffsetDateTime triggeredAt;

    @Column(name = "halt_until", nullable = false)
    private OffsetDateTime haltUntil;

    @Column(name = "published_at", nullable = false)
    private OffsetDateTime publishedAt;

    @Column(name = "received_at", nullable = false)
    private OffsetDateTime receivedAt;

    @Column(name = "title", nullable = false, length = 300)
    private String title;

    @Column(name = "source_url", nullable = false, length = 1000)
    private String sourceUrl;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected MarketEvent() {
    }

    private MarketEvent(
            MarketEventSource source,
            String sourceEventId,
            KrMarket market,
            MarketEventType eventType,
            Short circuitBreakerStage,
            SidecarDirection sidecarDirection,
            Instant triggeredAt,
            Instant haltUntil,
            Instant publishedAt,
            Instant receivedAt,
            String title,
            URI sourceUrl
    ) {
        this.source = requireNonNull(source, "source");
        this.sourceEventId = validateSourceEventId(sourceEventId);
        this.market = requireNonNull(market, "market");
        this.eventType = requireNonNull(eventType, "eventType");
        this.circuitBreakerStage = circuitBreakerStage;
        this.sidecarDirection = sidecarDirection;
        this.triggeredAt = utc(triggeredAt, "triggeredAt");
        this.haltUntil = utc(haltUntil, "haltUntil");
        this.publishedAt = utc(publishedAt, "publishedAt");
        this.receivedAt = utc(receivedAt, "receivedAt");
        if (!this.triggeredAt.isBefore(this.haltUntil)) {
            throw new IllegalArgumentException("triggeredAt must be before haltUntil");
        }
        this.title = validateTitle(title);
        this.sourceUrl = validateSourceUrl(sourceUrl).toString();
    }

    public static MarketEvent circuitBreaker(
            MarketEventSource source,
            String sourceEventId,
            KrMarket market,
            int stage,
            Instant triggeredAt,
            Instant haltUntil,
            Instant publishedAt,
            Instant receivedAt,
            String title,
            URI sourceUrl
    ) {
        if (stage < 1 || stage > 3) {
            throw new IllegalArgumentException("circuit breaker stage must be between 1 and 3");
        }
        return new MarketEvent(source, sourceEventId, market, MarketEventType.CIRCUIT_BREAKER,
                (short) stage, null, triggeredAt, haltUntil, publishedAt, receivedAt, title, sourceUrl);
    }

    public static MarketEvent sidecar(
            MarketEventSource source,
            String sourceEventId,
            KrMarket market,
            SidecarDirection direction,
            Instant triggeredAt,
            Instant haltUntil,
            Instant publishedAt,
            Instant receivedAt,
            String title,
            URI sourceUrl
    ) {
        return new MarketEvent(source, sourceEventId, market, MarketEventType.SIDECAR,
                null, requireNonNull(direction, "direction"), triggeredAt, haltUntil,
                publishedAt, receivedAt, title, sourceUrl);
    }

    private static <T> T requireNonNull(T value, String name) {
        return Objects.requireNonNull(value, name + " must not be null");
    }

    private static String validateSourceEventId(String value) {
        if (value == null || !value.matches("\\d{14}")) {
            throw new IllegalArgumentException("sourceEventId must be a 14-digit number");
        }
        return value;
    }

    private static String validateTitle(String value) {
        if (value == null || value.isBlank() || value.length() > 300) {
            throw new IllegalArgumentException("title must be nonblank and at most 300 characters");
        }
        return value;
    }

    private static URI validateSourceUrl(URI value) {
        requireNonNull(value, "sourceUrl");
        if (!"https".equalsIgnoreCase(value.getScheme()) || value.getHost() == null) {
            throw new IllegalArgumentException("sourceUrl must be an HTTPS URL");
        }
        return value;
    }

    private static OffsetDateTime utc(Instant value, String name) {
        return requireNonNull(value, name).atOffset(ZoneOffset.UTC);
    }

    public Long getMarketEventId() { return marketEventId; }
    public MarketEventSource getSource() { return source; }
    public String getSourceEventId() { return sourceEventId; }
    public KrMarket getMarket() { return market; }
    public MarketEventType getEventType() { return eventType; }
    public Short getCircuitBreakerStage() { return circuitBreakerStage; }
    public SidecarDirection getSidecarDirection() { return sidecarDirection; }
    public OffsetDateTime getTriggeredAt() { return triggeredAt; }
    public OffsetDateTime getHaltUntil() { return haltUntil; }
    public OffsetDateTime getPublishedAt() { return publishedAt; }
    public OffsetDateTime getReceivedAt() { return receivedAt; }
    public String getTitle() { return title; }
    public URI getSourceUrl() { return URI.create(sourceUrl); }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
