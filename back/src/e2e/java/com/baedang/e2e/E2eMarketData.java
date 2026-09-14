package com.baedang.e2e;

import com.baedang.market.port.Candle;
import com.baedang.market.port.CandleInterval;
import com.baedang.market.port.ExchangeRateQuote;
import com.baedang.market.port.MarketCalendarDay;
import com.baedang.market.port.MarketCalendarPort;
import com.baedang.market.port.MarketDataPort;
import com.baedang.market.port.PriceLimits;
import com.baedang.market.port.PriceQuote;
import com.baedang.stock.entity.MarketCountry;
import com.baedang.stock.port.StockInfo;
import com.baedang.stock.port.StockUniverseEntry;
import com.baedang.stock.port.StockWarnings;
import com.baedang.stock.port.SymbolInfoPort;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;

/** 외부 포트에서만 대체 응답을 제공하며 저장·검증 서비스는 그대로 실행합니다. */
@Component("marketCalendarDelegate")
public class E2eMarketData implements MarketDataPort, MarketCalendarPort, SymbolInfoPort {
    private final E2eClock clock;
    public volatile boolean limitsAvailable = true;
    public E2eMarketData(E2eClock clock) { this.clock = clock; }
    private MarketCountry country(String symbol) { return symbol.equals("AAPL") ? MarketCountry.US : MarketCountry.KR; }
    @Override public PriceLimits fetchPriceLimits(String symbol) {
        if (!limitsAvailable) throw new IllegalStateException("E2E 상하한가 응답 대기");
        boolean us = country(symbol) == MarketCountry.US;
        return new PriceLimits(clock.instant().atOffset(ZoneOffset.UTC), us ? null : new BigDecimal("11000"),
                us ? null : new BigDecimal("9000"), us ? "USD" : "KRW");
    }
    @Override public List<PriceQuote> fetchPrices(List<String> symbols) {
        return symbols.stream().map(symbol -> new PriceQuote(symbol,
                new BigDecimal(country(symbol) == MarketCountry.US ? "100" : "10000"),
                clock.instant().atOffset(ZoneOffset.UTC), country(symbol).defaultCurrency())).toList();
    }
    @Override public List<Candle> fetchCandles(String symbol, CandleInterval interval, int count) { return List.of(); }
    @Override public ExchangeRateQuote fetchExchangeRate() {
        return new ExchangeRateQuote("USD", "KRW", new BigDecimal("1400"), new BigDecimal("1400"),
                clock.instant().minusSeconds(60).atOffset(ZoneOffset.UTC), clock.instant().plusSeconds(3600).atOffset(ZoneOffset.UTC));
    }
    private MarketCalendarDay calendar(MarketCountry country, LocalDate date) {
        boolean weekday = date.getDayOfWeek().getValue() <= 5;
        return new MarketCalendarDay(country, date, weekday,
                weekday ? date.atTime(country == MarketCountry.KR ? LocalTime.of(9, 0) : LocalTime.of(9, 30)).atZone(country.zoneId()).toOffsetDateTime() : null,
                weekday ? date.atTime(country == MarketCountry.KR ? LocalTime.of(15, 30) : LocalTime.of(16, 0)).atZone(country.zoneId()).toOffsetDateTime() : null,
                null);
    }
    @Override public MarketCalendarDay fetchKrMarketCalendar(LocalDate date) { return calendar(MarketCountry.KR, date); }
    @Override public MarketCalendarDay fetchUsMarketCalendar(LocalDate date) { return calendar(MarketCountry.US, date); }
    @Override public List<StockInfo> fetchStocks(List<String> symbols) {
        return symbols.stream().map(symbol -> new StockInfo(symbol, symbol, symbol, null,
                country(symbol) == MarketCountry.US ? "NASDAQ" : "KOSPI", "STOCK", true, "ACTIVE",
                country(symbol).defaultCurrency(), null, null, null, null,
                country(symbol) == MarketCountry.KR ? new StockInfo.KrMarketDetail(false, false, false, false) : null)).toList();
    }
    @Override public StockWarnings fetchStockWarnings(String symbol) { return new StockWarnings(symbol, List.of()); }
    @Override public List<StockUniverseEntry> fetchAllStocks(String market) { return List.of(); }
}
