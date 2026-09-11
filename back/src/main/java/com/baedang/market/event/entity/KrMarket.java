package com.baedang.market.event.entity;

import java.util.Locale;
import java.util.Optional;

public enum KrMarket {
    KOSPI("1"),
    KOSDAQ("2");

    private final String kindCode;

    KrMarket(String kindCode) {
        this.kindCode = kindCode;
    }

    public String kindCode() {
        return kindCode;
    }

    public static Optional<KrMarket> fromStockMarket(String value) {
        if (value == null) {
            return Optional.empty();
        }
        return switch (value.trim().toUpperCase(Locale.ROOT)) {
            case "KOSPI" -> Optional.of(KOSPI);
            case "KOSDAQ" -> Optional.of(KOSDAQ);
            default -> Optional.empty();
        };
    }
}
