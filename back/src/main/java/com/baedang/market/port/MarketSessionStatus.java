package com.baedang.market.port;

import java.time.Instant;

/** 거래 모듈이 장 운영 여부와 해당 판단의 유효 종료 시각을 함께 받는 값입니다. */
public record MarketSessionStatus(boolean open, Instant validUntil) {

    public MarketSessionStatus {
        if (open && validUntil == null) {
            throw new IllegalArgumentException("열린 시장 세션에는 종료 시각이 필요합니다");
        }
    }

    public static MarketSessionStatus closed() {
        return new MarketSessionStatus(false, null);
    }
}
