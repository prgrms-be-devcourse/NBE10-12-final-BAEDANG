package com.baedang.trading.model;

import com.baedang.stock.entity.MarketCountry;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 시장가 체결과 지정가 접수 트랜잭션에 전달할 공통 외부 시장 데이터 스냅샷입니다.
 *
 * <p>계좌 행을 잠근 뒤 Toss API 또는 캐시를 조회하지 않도록 트랜잭션 시작 전에 준비합니다.
 */
public record OrderMarketContext(
        MarketCountry marketCountry,
        boolean marketOpen,
        Instant marketOpenUntil,
        ExecutionRateEvidence executionRateEvidence,
        Instant checkedAt
) {

    public BigDecimal executionRate() {
        return executionRateEvidence.rate();
    }

    public boolean isMarketOpenAt(Instant now) {
        return marketOpen
                && marketOpenUntil != null
                && now.isBefore(marketOpenUntil);
    }
}
