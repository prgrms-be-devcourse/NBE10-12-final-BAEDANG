package com.baedang.global.clients.toss;

import org.springframework.util.AntPathMatcher;

// 주문(order) 계열은 절대 추가하지 않습니다.
public enum Whitelist {

    // ── Market Info ─────────────────────────────────────────────────────────
    EXCHANGE_RATE("/api/v1/exchange-rate"),
    MARKET_CALENDAR_KR("/api/v1/market-calendar/KR"),
    MARKET_CALENDAR_US("/api/v1/market-calendar/US"),

    // ── Market Data ─────────────────────────────────────────────────────────
    PRICES("/api/v1/prices"),
    CANDLES("/api/v1/candles"),

    // ── Stock Info ──────────────────────────────────────────────────────────
    STOCKS("/api/v1/stocks"),
    STOCKS_ALL("/api/v1/stocks/all"),
    STOCK_WARNINGS("/api/v1/stocks/{symbol}/warnings"),

    // ── Ranking ─────────────────────────────────────────────────────────────
    RANKINGS("/api/v1/rankings");

    private static final AntPathMatcher pathMatcher = new AntPathMatcher();

    private final String pattern;

    Whitelist(String pattern) {
        this.pattern = pattern;
    }

    public static boolean match(String path) {
        for (Whitelist value : values()) {
            if (pathMatcher.match(value.pattern, path)) return true;
        }
        return false;
    }
}
