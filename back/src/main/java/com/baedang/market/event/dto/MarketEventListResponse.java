package com.baedang.market.event.dto;

import com.baedang.market.event.entity.KrMarket;
import com.baedang.market.event.entity.MarketEvent;
import com.baedang.market.event.entity.MarketEventType;
import com.baedang.market.event.entity.SidecarDirection;

import java.net.URI;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

/**
 * 시장·KST 날짜별 시장조치 이력.
 *
 * <p>공개 응답의 모든 시각은 {@code +09:00}이다. 저장·계산은 UTC로 하지만 이 API의 소비자는
 * "그날 무슨 일이 있었나"를 한국 시간으로 읽으므로, 변환을 응답 경계에서 한 번만 한다.
 */
public record MarketEventListResponse(
        KrMarket market,
        LocalDate date,
        List<Item> items
) {
    public MarketEventListResponse {
        items = List.copyOf(items);
    }

    public record Item(
            Long eventId,
            MarketEventType eventType,
            Integer stage,
            SidecarDirection direction,
            OffsetDateTime triggeredAt,
            OffsetDateTime haltUntil,
            OffsetDateTime publishedAt,
            OffsetDateTime receivedAt,
            boolean active,
            String title,
            URI sourceUrl
    ) {
        public static Item from(MarketEvent event, boolean active, ZoneId displayZone) {
            return new Item(
                    event.getMarketEventId(),
                    event.getEventType(),
                    event.getCircuitBreakerStage() == null ? null : event.getCircuitBreakerStage().intValue(),
                    event.getSidecarDirection(),
                    toDisplay(event.getTriggeredAt(), displayZone),
                    toDisplay(event.getHaltUntil(), displayZone),
                    toDisplay(event.getPublishedAt(), displayZone),
                    toDisplay(event.getReceivedAt(), displayZone),
                    active,
                    event.getTitle(),
                    event.getSourceUrl());
        }

        /** 같은 순간을 다른 오프셋으로 표현한다. 저장 값(UTC)은 그대로 두고 응답만 KST로 바꾼다. */
        private static OffsetDateTime toDisplay(OffsetDateTime value, ZoneId displayZone) {
            return value.atZoneSameInstant(displayZone).toOffsetDateTime();
        }
    }
}
