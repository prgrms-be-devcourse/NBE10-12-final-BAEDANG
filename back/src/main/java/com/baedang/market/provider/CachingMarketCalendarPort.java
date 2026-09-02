package com.baedang.market.provider;

import com.baedang.market.port.ExchangeRateQuote;
import com.baedang.market.port.MarketCalendarDay;
import com.baedang.market.port.MarketCalendarPort;
import com.baedang.stock.entity.MarketCountry;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 모든 캘린더 사용처가 같은 (시장, 거래일) 조회 결과를 공유하게 하는 Port 데코레이터.
 * 실패하거나 {@code null}인 응답은 저장하지 않으므로 다음 호출에서 다시 조회한다.
 * 환율은 별도 TTL 정책을 사용하므로 이 데코레이터에서 캐싱하지 않는다.
 */
@Primary
@Component
public class CachingMarketCalendarPort implements MarketCalendarPort {

    private final MarketCalendarPort delegate;
    private final Map<CacheKey, MarketCalendarDay> calendarCache = new ConcurrentHashMap<>();

    public CachingMarketCalendarPort(
            @Qualifier("marketCalendarDelegate") MarketCalendarPort delegate
    ) {
        this.delegate = delegate;
    }

    @Override
    public ExchangeRateQuote fetchExchangeRate() {
        return delegate.fetchExchangeRate();
    }

    @Override
    public MarketCalendarDay fetchKrMarketCalendar(LocalDate date) {
        return calendarCache.computeIfAbsent(
                new CacheKey(MarketCountry.KR, date),
                key -> delegate.fetchKrMarketCalendar(key.tradeDate())
        );
    }

    @Override
    public MarketCalendarDay fetchUsMarketCalendar(LocalDate date) {
        return calendarCache.computeIfAbsent(
                new CacheKey(MarketCountry.US, date),
                key -> delegate.fetchUsMarketCalendar(key.tradeDate())
        );
    }

    int entryCount() {
        return calendarCache.size();
    }

    private record CacheKey(MarketCountry marketCountry, LocalDate tradeDate) {
    }
}
