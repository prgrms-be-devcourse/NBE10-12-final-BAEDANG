package com.baedang.market.event.model;

import com.baedang.market.event.entity.KrMarket;
import com.baedang.market.event.entity.MarketEvent;
import com.baedang.market.event.entity.MarketEventType;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Objects;

/**
 * 주문을 거절시킨 활성 서킷브레이커 한 건.
 *
 * <p>{@code eventId}는 그 판정에 사용한 {@code market_event} 행의 식별자다. 거절 주문은 이 값을
 * 저장하고, 같은 {@code clientOrderId}의 멱등 재요청은 이 ID로 이벤트를 다시 읽는다. {@code orderedAt}
 * 시점의 활성 이벤트를 재검색하면, 최초 거절 뒤 늦게 수집된 더 긴 CB가 선택돼 응답 데이터가 바뀔 수 있다.
 *
 * <p>{@code eventId}는 감사 연결용 내부 값이므로 {@link #asErrorData()}에 넣지 않는다. 공개 응답은
 * {@code market}, {@code eventType}, {@code stage}, {@code triggeredAt}, {@code haltUntil}만 담는다.
 */
public record ActiveMarketHalt(
        Long eventId,
        KrMarket market,
        int stage,
        OffsetDateTime triggeredAt,
        OffsetDateTime haltUntil
) {

    /** 공개 시각은 {@code +09:00}으로 통일한다. 저장·비교는 UTC 그대로 사용한다. */
    private static final ZoneOffset KST_OFFSET = ZoneOffset.ofHours(9);

    public ActiveMarketHalt {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(market, "market must not be null");
        Objects.requireNonNull(triggeredAt, "triggeredAt must not be null");
        Objects.requireNonNull(haltUntil, "haltUntil must not be null");
        if (stage < 1 || stage > 3) {
            throw new IllegalArgumentException("circuit breaker stage must be between 1 and 3: " + stage);
        }
    }

    /**
     * 저장된 CB 이벤트에서 만든다. 아직 저장되지 않아 identity가 없는 이벤트는 감사 FK를 만들 수 없으므로
     * 거절한다 — 그런 거절은 멱등 재생 계약을 만족하지 못한다.
     */
    public static ActiveMarketHalt from(MarketEvent event) {
        Objects.requireNonNull(event, "event must not be null");
        if (event.getEventType() != MarketEventType.CIRCUIT_BREAKER) {
            throw new IllegalArgumentException("활성 CB 판정에는 CIRCUIT_BREAKER 이벤트가 필요합니다");
        }
        return new ActiveMarketHalt(
                Objects.requireNonNull(event.getMarketEventId(), "저장된 이벤트만 활성 CB가 될 수 있습니다"),
                event.getMarket(),
                Objects.requireNonNull(event.getCircuitBreakerStage(), "circuitBreakerStage").intValue(),
                event.getTriggeredAt().withOffsetSameInstant(KST_OFFSET),
                event.getHaltUntil().withOffsetSameInstant(KST_OFFSET));
    }

    /** HTTP 오류 {@code data}에 들어가는 공개 필드. 내부 {@code eventId}는 노출하지 않는다. */
    public Map<String, Object> asErrorData() {
        return Map.of(
                "market", market.name(),
                "eventType", MarketEventType.CIRCUIT_BREAKER.name(),
                "stage", stage,
                "triggeredAt", triggeredAt,
                "haltUntil", haltUntil);
    }
}
