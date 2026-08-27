package com.baedang.market.client.toss;

import com.baedang.global.clients.toss.TossSecuritiesClient;
import com.baedang.global.error.BusinessException;
import com.baedang.global.error.ErrorCode;
import com.baedang.market.client.toss.dto.TossExchangeRateResponse;
import com.baedang.market.client.toss.dto.TossKrMarketCalendarResponse;
import com.baedang.market.client.toss.dto.TossUsMarketCalendarResponse;
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
 *
 * <p><b>KR/US 파싱 로직을 공유하지 않습니다.</b> 응답 구조 자체가 달라서
 * ({@code TossKrMarketCalendarResponse}/{@code TossUsMarketCalendarResponse} 참고)
 * 각자 자기 DTO를 파싱합니다.
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
        TossExchangeRateResponse.Result result = requireBody(response.result(), "/api/v1/exchange-rate");

        return new ExchangeRateQuote(
                result.baseCurrency(),
                result.quoteCurrency(),
                result.rate(),
                result.midRate(),
                result.validFrom(),
                result.validUntil()
        );
    }

    @Override
    public MarketCalendarDay fetchKrMarketCalendar(LocalDate date) {
        TossKrMarketCalendarResponse response = tossSecuritiesClient.get(
                "/api/v1/market-calendar/KR",
                Map.of("date", date.toString()),
                TossKrMarketCalendarResponse.class
        );
        TossKrMarketCalendarResponse.Result result = requireBody(response.result(), "/api/v1/market-calendar/KR");
        TossKrMarketCalendarResponse.Today today = requireBody(result.today(), "/api/v1/market-calendar/KR (today)");
        TossKrMarketCalendarResponse.IntegratedSession integrated = today.integrated();

        // integrated 가 null 이거나 regularMarket 이 없으면 휴장일 — 하드코딩 없이 응답만으로 판단.
        boolean isOpen = integrated != null && integrated.regularMarket() != null;

        return new MarketCalendarDay(
                MarketCountry.KR,
                today.date(),
                isOpen,
                isOpen ? integrated.regularMarket().startTime() : null,
                isOpen ? integrated.regularMarket().endTime() : null,
                isOpen ? null : krNextOpensAt(result.nextBusinessDay())
        );
    }

    private static OffsetDateTime krNextOpensAt(TossKrMarketCalendarResponse.Today nextBusinessDay) {
        if (nextBusinessDay == null || nextBusinessDay.integrated() == null
                || nextBusinessDay.integrated().regularMarket() == null) {
            return null;
        }
        return nextBusinessDay.integrated().regularMarket().startTime();
    }

    @Override
    public MarketCalendarDay fetchUsMarketCalendar(LocalDate date) {
        TossUsMarketCalendarResponse response = tossSecuritiesClient.get(
                "/api/v1/market-calendar/US",
                Map.of("date", date.toString()),
                TossUsMarketCalendarResponse.class
        );
        TossUsMarketCalendarResponse.Result result = requireBody(response.result(), "/api/v1/market-calendar/US");
        TossUsMarketCalendarResponse.Today today = requireBody(result.today(), "/api/v1/market-calendar/US (today)");

        // US는 KR과 달리 integrated로 감싸지 않고 today 바로 아래 regularMarket이 온다.
        // 휴장일엔 필드가 사라지는 게 아니라 "regularMarket": null 로 명시적으로 온다
        // (실제 캡처로 확인됨 — TossUsMarketCalendarResponse 참고). null 체크 하나로 충분하다.
        boolean isOpen = today.regularMarket() != null;

        return new MarketCalendarDay(
                MarketCountry.US,
                today.date(),
                isOpen,
                isOpen ? today.regularMarket().startTime() : null,
                isOpen ? today.regularMarket().endTime() : null,
                isOpen ? null : usNextOpensAt(result.nextBusinessDay())
        );
    }

    private static OffsetDateTime usNextOpensAt(TossUsMarketCalendarResponse.Today nextBusinessDay) {
        if (nextBusinessDay == null || nextBusinessDay.regularMarket() == null) {
            return null;
        }
        return nextBusinessDay.regularMarket().startTime();
    }

    /**
     * {@code result}/{@code today} 처럼 응답 본문 필수 부분이 {@code null}이면 여기서
     * 끊는다. (민호님 리뷰, PR #21) — 이게 없으면 다음 줄에서 NPE가 나고, NPE는
     * {@code GlobalExceptionHandler}의 마지막 {@code catch(Exception)}으로 떨어져
     * 500(INTERNAL_ERROR)으로 나간다. Toss 응답이 예상과 다른 건 "예상 가능한 실패"이지
     * 우리 쪽 버그가 아니므로, {@link BusinessException}으로 명시적으로 변환해
     * 502(TOSS_API_ERROR)로 응답하는 게 맞다.
     */
    private static <T> T requireBody(T value, String context) {
        if (value == null) {
            throw new BusinessException(ErrorCode.TOSS_API_ERROR, context + " 응답 본문이 비어 있음");
        }
        return value;
    }
}
