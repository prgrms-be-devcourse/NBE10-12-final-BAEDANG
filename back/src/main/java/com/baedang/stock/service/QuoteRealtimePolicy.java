package com.baedang.stock.service;

import com.baedang.market.entity.QuoteSnapshot;
import com.baedang.market.port.MarketSessionProvider;
import com.baedang.market.port.MarketSessionStatus;
import com.baedang.stock.entity.MarketCountry;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;

@Component
public class QuoteRealtimePolicy {

    private final MarketSessionProvider marketSessionProvider;
    private final Clock clock;

    public QuoteRealtimePolicy(MarketSessionProvider marketSessionProvider, Clock clock) {
        this.marketSessionProvider = marketSessionProvider;
        this.clock = clock;
    }

    public boolean isRealtime(MarketCountry marketCountry, QuoteSnapshot quote) {
        if (quote == null || quote.getQuoteAt() == null) return false;

        Instant now = Instant.now(clock);
        Instant quoteAt = quote.getQuoteAt().toInstant();
        if (quoteAt.isAfter(now)) return false;

        MarketSessionStatus currentSession = marketSessionProvider.currentSession(marketCountry, now);
        if (!currentSession.open()) return false;

        MarketSessionStatus quoteSession = marketSessionProvider.currentSession(marketCountry, quoteAt);
        return quoteSession.open() && currentSession.validUntil().equals(quoteSession.validUntil());
    }

    public boolean isMarketOpen(MarketCountry marketCountry) {
        return marketSessionProvider.currentSession(marketCountry, Instant.now(clock)).open();
    }
}
