package com.baedang.market.client.toss;

import com.baedang.global.clients.toss.TossSecuritiesClient;
import com.baedang.market.client.toss.dto.TossExchangeRateResponse;
import com.baedang.market.client.toss.dto.TossMarketCalendarResponse;
import com.baedang.market.port.ExchangeRateQuote;
import com.baedang.market.port.MarketCalendarDay;
import com.baedang.market.port.MarketCalendarPort;
import com.baedang.stock.entity.MarketCountry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Map;

/**
 * {@link MarketCalendarPort} 의 Toss 구현체.
 *
 * <p>{@code toss.enabled=true} 일 때만 빈으로 등록됩니다. 기본값({@code false}) 에서는
 * {@code FakeMarketCalendarPort} 가 대신 등록되므로, Toss 키가 없는 팀원도 앱을 정상
 * 구동할 수 있습니다.
 */
@Component
@ConditionalOnProperty(prefix = "toss", name = "enabled", havingValue = "true")
public class TossMarketCalendarAdapter implements MarketCalendarPort {

    /** MVP 는 이 통화쌍만 다룬다 (docs/erd.md — "MVP has only USD → KRW"). */
    private static final String BASE_CURRENCY = "USD";
    private static final String QUOTE_CURRENCY = "KRW";

    private final TossSecuritiesClient tossSecuritiesClient;

    public TossMarketCalendarAdapter(TossSecuritiesClient tossSecuritiesClient) {
        this.tossSecuritiesClient = tossSecuritiesClient;
    }

    @Override
    public ExchangeRateQuote fetchExchangeRate() {
        TossExchangeRateResponse response = tossSecuritiesClient.get(
                "/api/v1/exchange-rate",
                Map.of("baseCurrency", BASE_CURRENCY, "quoteCurrency", QUOTE_CURRENCY),
                TossExchangeRateResponse.class
        );

        return new ExchangeRateQuote(
                response.baseCurrency(),
                response.quoteCurrency(),
                response.rate(),
                response.midRate(),
                response.validFrom(),
                response.validUntil()
        );
    }

    @Override
    public MarketCalendarDay fetchKrMarketCalendar(LocalDate date) {
        return fetchCalendar(MarketCountry.KR, "/api/v1/market-calendar/KR", date);
    }

    @Override
    public MarketCalendarDay fetchUsMarketCalendar(LocalDate date) {
        return fetchCalendar(MarketCountry.US, "/api/v1/market-calendar/US", date);
    }

    private MarketCalendarDay fetchCalendar(MarketCountry marketCountry, String path, LocalDate date) {
        TossMarketCalendarResponse response = tossSecuritiesClient.get(
                path,
                Map.of("date", date.toString()),
                TossMarketCalendarResponse.class
        );

        TossMarketCalendarResponse.Today today = response.today();
        TossMarketCalendarResponse.IntegratedSession integrated = today.integrated();

        // integrated 가 null 이거나 regularMarket 이 없으면 휴장일 — 하드코딩 없이 응답만으로 판단.
        boolean isOpen = integrated != null && integrated.regularMarket() != null;

        return new MarketCalendarDay(
                marketCountry,
                today.date(),
                isOpen,
                isOpen ? integrated.regularMarket().startTime() : null,
                isOpen ? integrated.regularMarket().endTime() : null,
                isOpen ? null : nextOpensAt(response.nextBusinessDay())
        );
    }

    /**
     * 휴장일일 때만 쓰는 값 — 다음 영업일의 정규장 시작 시각. Toss가 {@code nextBusinessDay}로
     * 이미 계산해서 주는 값을 그대로 옮길 뿐, 여기서 날짜 계산을 하지 않는다.
     */
    private static OffsetDateTime nextOpensAt(TossMarketCalendarResponse.Today nextBusinessDay) {
        if (nextBusinessDay == null || nextBusinessDay.integrated() == null
                || nextBusinessDay.integrated().regularMarket() == null) {
            return null;
        }
        return nextBusinessDay.integrated().regularMarket().startTime();
    }
}
