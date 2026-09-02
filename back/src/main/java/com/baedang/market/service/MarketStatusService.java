package com.baedang.market.service;

import com.baedang.market.dto.MarketStatusResponse;
import com.baedang.market.dto.MarketStatusResponse.Market;
import com.baedang.market.port.MarketCalendarDay;
import com.baedang.market.port.MarketCalendarPort;
import com.baedang.stock.entity.MarketCountry;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code GET /api/market/status} 서비스. KR·US 정규장의 <b>지금 개장 여부</b>와
 * <b>다음 개장 시각</b>을 계산한다. 데이터는 {@link MarketCalendarPort}(Toss) 에서 오며,
 * 폴링마다 live Toss 를 때리지 않도록 {@code (country, tradeDate)} 키 메모리 캐시로 하루 1회만 fetch 한다.
 *
 * <h2>핵심: open-NOW ≠ open-DAY</h2>
 * {@link MarketCalendarDay#isOpen()} 는 그 <b>날짜가 거래일인지</b>(open-DAY) 이고,
 * api-spec 의 {@code open} 은 <b>지금 이 순간 정규장 운영 중인지</b>(open-NOW) 다.
 * 그래서 서비스가 {@code now} 와 {@code [openAt, closeAt)} 를 직접 비교한다. 시각 비교는 전부 {@link Instant}.
 *
 * <h2>상태 계산 = 2단계</h2>
 * <ol>
 *   <li><b>open 판정</b>: {@code now} 가 활성일의 {@code [openAt, closeAt)} 안이면 {@code open=true}.
 *       US 정규장은 KST 자정을 넘기므로(예: 22:30~익일 05:00) 오늘→전날 <b>2일 lookback</b> 으로 활성일을 찾는다.</li>
 *   <li><b>open 이 아니면 정방향 스캔</b>: {@code today} 부터 미래로 조회하며
 *       {@code isOpen && openAt > now} 인 첫 날의 {@code openAt} 을 {@code nextOpensAt} 으로 채운다.
 *       이 한 스캔이 "거래일·개장 전 / 휴장일 / 거래일·마감 후" 를 모두 포섭하고,
 *       Port 가 {@code isOpen=true} 일 때 {@code nextOpensAt=null} 을 주는 함정도 우회한다.</li>
 * </ol>
 * 정규장(regular session) 만 다룬다 — 시간외/프리마켓 필드는 Port 에 없고 MVP 범위 밖이다.
 */
@Service
public class MarketStatusService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final ZoneOffset KST_OFFSET = ZoneOffset.ofHours(9);

    /** 연휴가 아무리 길어도 이 안에 다음 거래일이 있다. 무한 루프·Port 폭주 안전장치. */
    private static final int MAX_SCAN_DAYS = 14;

    private final MarketCalendarPort marketCalendarPort;
    private final Clock clock;

    /** {@code (country, tradeDate)} → 그 날의 정규장 정보. 하루 1회 fetch(캐시 날짜 롤오버 시 clear). */
    private final Map<CacheKey, MarketCalendarDay> cache = new HashMap<>();
    private LocalDate cacheDate;

    public MarketStatusService(MarketCalendarPort marketCalendarPort, Clock clock) {
        this.marketCalendarPort = marketCalendarPort;
        this.clock = clock;
    }

    public MarketStatusResponse getStatus() {
        Instant now = clock.instant();
        LocalDate today = LocalDate.ofInstant(now, KST);
        List<Market> markets = List.of(
                statusOf(MarketCountry.KR, now, today),
                statusOf(MarketCountry.US, now, today)
        );
        OffsetDateTime serverTime = OffsetDateTime.ofInstant(now, KST_OFFSET);
        return new MarketStatusResponse(markets, serverTime);
    }

    private Market statusOf(MarketCountry country, Instant now, LocalDate today) {
        MarketCalendarDay active = activeOpenDay(country, today, now);
        if (active != null) {
            return new Market(country, true, active.regularOpenAt(), active.regularCloseAt(), null);
        }
        OffsetDateTime nextOpensAt = scanNextOpen(country, today, now);
        return new Market(country, false, null, null, nextOpensAt);
    }

    /**
     * 지금 정규장이 열려 있는 활성일을 찾는다. 없으면 {@code null}.
     * US 는 자정 교차 때문에 오늘 창에 없으면 전날 창(전날 22:30~오늘 05:00) 도 확인한다.
     * KR 은 자정을 넘기지 않으므로 오늘만 본다.
     */
    private MarketCalendarDay activeOpenDay(MarketCountry country, LocalDate today, Instant now) {
        MarketCalendarDay todayDay = dayFor(country, today, today);
        if (isNowWithin(todayDay, now)) {
            return todayDay;
        }
        if (country == MarketCountry.US) {
            MarketCalendarDay yesterday = dayFor(country, today.minusDays(1), today);
            if (isNowWithin(yesterday, now)) {
                return yesterday;
            }
        }
        return null;
    }

    /**
     * {@code today} 부터 미래로 첫 개장 시각을 찾는다. {@code openAt > now} 라서 오늘 이미 지난
     * 세션이나 개장 후 시각은 자동으로 건너뛴다 → 개장 전/휴장/마감 후를 한 번에 처리.
     */
    private OffsetDateTime scanNextOpen(MarketCountry country, LocalDate today, Instant now) {
        for (int i = 0; i <= MAX_SCAN_DAYS; i++) {
            MarketCalendarDay day = dayFor(country, today.plusDays(i), today);
            if (day.isOpen()
                    && day.regularOpenAt() != null
                    && day.regularOpenAt().toInstant().isAfter(now)) {
                return day.regularOpenAt();
            }
        }
        return null;
    }

    private boolean isNowWithin(MarketCalendarDay day, Instant now) {
        if (!day.isOpen() || day.regularOpenAt() == null || day.regularCloseAt() == null) {
            return false;
        }
        Instant openAt = day.regularOpenAt().toInstant();
        Instant closeAt = day.regularCloseAt().toInstant();
        return !now.isBefore(openAt) && now.isBefore(closeAt);
    }

    /**
     * {@code (country, date)} 정규장 정보를 캐시에서 꺼내거나, 미스면 Port 로 한 번 fetch 해 저장한다.
     * 캐시 날짜(오늘 KST) 가 바뀌면 통째로 비워 하루 1회 fetch 를 보장한다.
     *
     * <p>{@code today} 는 {@code getStatus()} 시작 시점에 한 번 캡처한 오늘(KST) 을 그대로 받는다.
     * 내부에서 {@code clock.instant()} 를 다시 읽지 않으므로, 자정 경계에서 한 요청이 KR·US 를 처리하는
     * 도중 날짜가 바뀌어 캐시가 중간에 비워지거나 KR/US 기준일이 어긋나는 일이 없다.
     *
     * <p>⚠️ 콜드 캐시(그날 첫 요청, 또는 긴 연휴를 넘는 스캔)에서는 이 {@code synchronized} 가
     * Port I/O(=live Toss 호출) 를 문 안에서 붙들어, 한 요청이 N번의 순차 호출을 도는 동안
     * 동시 폴러가 뒤에서 대기한다. 하루 1회 캐시라 MVP 규모에선 수용 가능 — 부하가 커지면
     * 날짜별 락 분리나 Port 레벨 캐시 데코레이터로 옮긴다.
     */
    private synchronized MarketCalendarDay dayFor(MarketCountry country, LocalDate date, LocalDate today) {
        if (!today.equals(cacheDate)) {
            cache.clear();
            cacheDate = today;
        }
        CacheKey key = new CacheKey(country, date);
        MarketCalendarDay cached = cache.get(key);
        if (cached != null) {
            return cached;
        }
        MarketCalendarDay fetched = fetch(country, date);
        cache.put(key, fetched);
        return fetched;
    }

    private MarketCalendarDay fetch(MarketCountry country, LocalDate date) {
        return country == MarketCountry.KR
                ? marketCalendarPort.fetchKrMarketCalendar(date)
                : marketCalendarPort.fetchUsMarketCalendar(date);
    }

    private record CacheKey(MarketCountry country, LocalDate date) {
    }
}
