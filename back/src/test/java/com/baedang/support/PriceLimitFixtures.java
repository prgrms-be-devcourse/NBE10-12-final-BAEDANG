package com.baedang.support;

import com.baedang.market.entity.QuoteSnapshot;
import com.baedang.market.model.TradingPriceLimits;
import com.baedang.stock.entity.MarketCountry;
import org.springframework.test.util.ReflectionTestUtils;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import org.springframework.jdbc.core.JdbcTemplate;
import com.baedang.stock.entity.Stock;

/** 기존 거래 테스트에서 상하한가와 무관한 시나리오에 사용하는 검증된 입력입니다. */
public final class PriceLimitFixtures {
    private PriceLimitFixtures() { }
    public static TradingPriceLimits at(Instant at) {
        return new TradingPriceLimits(at.atZone(MarketCountry.KR.zoneId()).toLocalDate(),
                BigDecimal.ONE, new BigDecimal("999999999999000"));
    }
    public static void persist(JdbcTemplate jdbc, Stock stock, Instant now) {
        TradingPriceLimits limits = at(now);
        jdbc.update("""
                INSERT INTO quote_snapshot(stock_id,last_price,currency,quote_at,collected_at,price_limit_date,lower_limit,upper_limit)
                VALUES (?,70000,?,?,?,?,?,?) ON CONFLICT(stock_id) DO NOTHING
                """, stock.getStockId(), stock.getCurrency(), now.atOffset(ZoneOffset.UTC), now.atOffset(ZoneOffset.UTC),
                stock.getMarketCountry() == MarketCountry.KR ? limits.date() : null,
                stock.getMarketCountry() == MarketCountry.KR ? limits.lower() : null,
                stock.getMarketCountry() == MarketCountry.KR ? limits.upper() : null);
    }
    public static QuoteSnapshot verified(QuoteSnapshot quote) {
        if ("KRW".equals(quote.getCurrency())) {
            TradingPriceLimits limits = at(quote.getQuoteAt().toInstant());
            quote.updateLimits(limits.upper(), limits.lower());
            ReflectionTestUtils.setField(quote, "priceLimitDate", limits.date());
        }
        return quote;
    }
}
