package com.baedang.global.clients.toss;

import org.springframework.util.AntPathMatcher;

// 주문(order) 계열은 절대 추가하지 않습니다.
public enum Whitelist {

    // ── Market Info ─────────────────────────────────────────────────────────
    EXCHANGE_RATE("/api/v1/exchange-rate", TossApiGroup.MARKET_INFO),
    MARKET_CALENDAR_KR("/api/v1/market-calendar/KR", TossApiGroup.MARKET_INFO),
    MARKET_CALENDAR_US("/api/v1/market-calendar/US", TossApiGroup.MARKET_INFO),

    // ── Market Data ─────────────────────────────────────────────────────────
    PRICES("/api/v1/prices", TossApiGroup.MARKET_DATA),
    CANDLES("/api/v1/candles", TossApiGroup.MARKET_DATA_CHART),

    // ── Stock Info ──────────────────────────────────────────────────────────
    STOCKS("/api/v1/stocks", TossApiGroup.STOCK),
    STOCK_WARNINGS("/api/v1/stocks/{symbol}/warnings", TossApiGroup.STOCK),
    STOCKS_ALL("/api/v1/stocks/all", TossApiGroup.STOCK_ALL),

    // ── Ranking ─────────────────────────────────────────────────────────────
    RANKINGS("/api/v1/rankings", TossApiGroup.RANKING);

    private static final AntPathMatcher pathMatcher = new AntPathMatcher();

    private final String pattern;
    private final TossApiGroup group;

    Whitelist(String pattern, TossApiGroup group) {
        this.pattern = pattern;
        this.group = group;
    }

    public TossApiGroup group() {
        return group;
    }

    public static Whitelist resolve(String path) {
        for (Whitelist value : values()) {
            if (pathMatcher.match(value.pattern, path)) return value;
        }
        return null;
    }
}
