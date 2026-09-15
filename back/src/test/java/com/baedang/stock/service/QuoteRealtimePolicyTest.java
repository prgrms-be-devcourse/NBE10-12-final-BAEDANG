package com.baedang.stock.service;

import com.baedang.market.entity.QuoteSnapshot;
import com.baedang.market.port.MarketSessionProvider;
import com.baedang.market.port.MarketSessionStatus;
import com.baedang.stock.entity.MarketCountry;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class QuoteRealtimePolicyTest {

    private static final Instant NOW = Instant.parse("2026-08-27T03:00:00Z");
    private final MarketSessionProvider marketSessionProvider = mock(MarketSessionProvider.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final QuoteRealtimePolicy policy = new QuoteRealtimePolicy(marketSessionProvider, clock);

    @Test
    void 현재와_시세가_같은_열린_세션이면_실시간이다() {
        QuoteSnapshot quote = quoteAt(NOW.minusSeconds(1));
        MarketSessionStatus session = new MarketSessionStatus(true, NOW.plusSeconds(3600));
        when(marketSessionProvider.currentSession(MarketCountry.KR, NOW)).thenReturn(session);
        when(marketSessionProvider.currentSession(MarketCountry.KR, NOW.minusSeconds(1))).thenReturn(session);

        assertThat(policy.isRealtime(MarketCountry.KR, quote)).isTrue();
    }

    @Test
    void 미래_시세는_세션을_조회하지_않고_실시간이_아니다() {
        assertThat(policy.isRealtime(MarketCountry.US, quoteAt(NOW.plusSeconds(1)))).isFalse();
        verifyNoInteractions(marketSessionProvider);
    }

    @Test
    void 이전_세션의_시세는_실시간이_아니다() {
        Instant quoteAt = NOW.minusSeconds(60);
        when(marketSessionProvider.currentSession(MarketCountry.US, NOW))
                .thenReturn(new MarketSessionStatus(true, NOW.plusSeconds(3600)));
        when(marketSessionProvider.currentSession(MarketCountry.US, quoteAt))
                .thenReturn(new MarketSessionStatus(true, NOW.minusSeconds(1)));

        assertThat(policy.isRealtime(MarketCountry.US, quoteAt(quoteAt))).isFalse();
    }

    private QuoteSnapshot quoteAt(Instant instant) {
        QuoteSnapshot quote = mock(QuoteSnapshot.class);
        when(quote.getQuoteAt()).thenReturn(OffsetDateTime.ofInstant(instant, ZoneOffset.UTC));
        return quote;
    }
}
