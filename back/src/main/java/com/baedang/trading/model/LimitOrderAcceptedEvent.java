package com.baedang.trading.model;

import com.baedang.trading.entity.OrderSide;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** 접수 커밋 후에만 해당 방향의 체결 후보 순서를 다시 평가합니다. */
public record LimitOrderAcceptedEvent(Long stockId, OrderSide side, Long orderId, BigDecimal price, OffsetDateTime orderedAt) {}
