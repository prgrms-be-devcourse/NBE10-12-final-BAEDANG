package com.baedang.market.client.fake;

import com.baedang.market.port.ExchangeRateQuote;
import com.baedang.market.port.MarketCalendarDay;
import com.baedang.market.port.MarketCalendarPort;
import com.baedang.stock.entity.MarketCountry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * {@link MarketCalendarPort} 의 개발용 대역 구현체.
 *
 * <p><b>{@code toss.enabled=false}(기본값) 일 때만 등록됩니다.</b> Toss API 키가 없는
 * 팀원도 앱을 정상적으로 띄우고 화면을 확인할 수 있도록, 고정된 값을 그대로 돌려줍니다.
 *
 * <p>⚠️ <b>여기 있는 09:00/15:30, 22:30/05:00 은 실제 시장 시간이 아니라
 * 개발 편의용 임의값입니다.</b> 실제 서비스 로직(세션 판단 등)이 이 클래스의 값에
 * 의존해서는 안 됩니다 — {@code TossMarketCalendarAdapter} 로 교체됐을 때도 똑같이
 * 동작해야 하고, 여기 숫자는 언제든 바뀔 수 있습니다.
 *
 * <p>다른 팀원의 단위 테스트에서도 {@code new FakeMarketCalendarPort()} 로 바로
 * 가져다 쓸 수 있습니다 — Spring 컨텍스트나 Toss 호출이 전혀 필요 없습니다.
 */
@Component
@ConditionalOnProperty(prefix = "toss", name = "enabled", havingValue = "false", matchIfMissing = true)
public class FakeMarketCalendarPort implements MarketCalendarPort {

    @Override
    public ExchangeRateQuote fetchExchangeRate() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.ofHours(9));
        return new ExchangeRateQuote(
                "USD", "KRW",
                new BigDecimal("1398.50"),
                new BigDecimal("1385.20"),
                now.withMinute(0).withSecond(0).withNano(0),
                now.withMinute(0).withSecond(0).withNano(0).plusHours(1)
        );
    }

    @Override
    public MarketCalendarDay fetchKrMarketCalendar(LocalDate date) {
        return openAllWeekday(MarketCountry.KR, date, LocalTime.of(9, 0), LocalTime.of(15, 30), 9);
    }

    @Override
    public MarketCalendarDay fetchUsMarketCalendar(LocalDate date) {
        // 서머타임 등은 신경 쓰지 않는다 — 실제 계산은 TossMarketCalendarAdapter 의 몫.
        return openAllWeekday(MarketCountry.US, date, LocalTime.of(22, 30), LocalTime.of(5, 0), 9);
    }

    private MarketCalendarDay openAllWeekday(MarketCountry country, LocalDate date,
                                              LocalTime open, LocalTime close, int kstOffsetHours) {
        boolean isWeekend = date.getDayOfWeek().getValue() >= 6;
        if (isWeekend) {
            return new MarketCalendarDay(country, date, false, null, null);
        }
        ZoneOffset kst = ZoneOffset.ofHours(kstOffsetHours);
        return new MarketCalendarDay(
                country,
                date,
                true,
                OffsetDateTime.of(date, open, kst),
                OffsetDateTime.of(close.isBefore(open) ? date.plusDays(1) : date, close, kst)
        );
    }
}
