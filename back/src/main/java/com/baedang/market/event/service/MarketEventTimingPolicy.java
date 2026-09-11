package com.baedang.market.event.service;

import com.baedang.market.event.entity.MarketEventType;
import com.baedang.market.event.model.ConfirmedMarketEvent;
import com.baedang.market.port.MarketCalendarDay;
import com.baedang.market.port.MarketCalendarPort;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Objects;

@Component
public class MarketEventTimingPolicy {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final MarketCalendarPort calendar;

    public MarketEventTimingPolicy(MarketCalendarPort calendar) {
        this.calendar = Objects.requireNonNull(calendar, "calendar must not be null");
    }

    public Instant haltUntil(ConfirmedMarketEvent event) {
        Objects.requireNonNull(event, "event must not be null");

        if (event.eventType() == MarketEventType.SIDECAR) {
            return event.triggeredAt().plus(Duration.ofMinutes(5));
        }
        if (event.circuitBreakerStage() == 1 || event.circuitBreakerStage() == 2) {
            return event.triggeredAt().plus(Duration.ofMinutes(20));
        }
        if (event.circuitBreakerStage() == 3) {
            LocalDate tradeDate = event.triggeredAt().atZone(KST).toLocalDate();
            MarketCalendarDay day = calendar.fetchKrMarketCalendar(tradeDate);
            if (!day.isOpen() || day.regularCloseAt() == null
                    || !day.regularCloseAt().toInstant().isAfter(event.triggeredAt())) {
                throw new IllegalStateException("stage 3 market close is unavailable");
            }
            return day.regularCloseAt().toInstant();
        }
        throw new IllegalArgumentException("지원되지 않는 이벤트 유형 또는 단계입니다");
    }
}
