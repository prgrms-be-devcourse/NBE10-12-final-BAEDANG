package com.baedang.trading.model;

import com.baedang.stock.entity.MarketCountry;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 시장가 주문 트랜잭션에 전달할 외부 시장 데이터 스냅샷입니다.
 *
 * <p>계좌 행을 잠근 뒤 Toss API 또는 캐시를 조회하지 않도록 트랜잭션 시작 전에 준비합니다.
 */
public record MarketOrderExecutionContext(
        MarketCountry marketCountry,
        boolean marketOpen,
        BigDecimal usdKrwRate,
        Instant checkedAt
) {
}
