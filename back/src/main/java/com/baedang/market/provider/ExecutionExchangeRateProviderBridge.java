package com.baedang.market.provider;

import com.baedang.market.port.ExecutionExchangeRateProvider;
import com.baedang.market.port.MarketCalendarPort;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * {@link ExecutionExchangeRateProvider}의 구현체.
 *
 * <p>거래 모듈이 필요로 하는 "현재 체결 환율 하나"와, 시장 데이터 모듈이 제공하는
 * {@link MarketCalendarPort#fetchExchangeRate()}(라이브 조회)를 잇는 변환 계층입니다.
 * {@link MarketSessionProviderBridge}와 같은 위치의 클래스입니다.
 *
 * <p>인터페이스 주석에 명시된 "1분 TTL 캐싱" 요구사항은 지켰습니다 — 매 호출마다
 * Toss를 부르면 체결 환율 조회가 몰릴 때 rate limit을 소모하기 때문입니다.
 * 차트 이력용 {@code exchange_rate} 테이블은 전혀 쓰지 않고, 항상
 * {@link MarketCalendarPort#fetchExchangeRate()}(라이브 조회)만 캐싱합니다.
 */
@Component
public class ExecutionExchangeRateProviderBridge implements ExecutionExchangeRateProvider {

    private static final long TTL_SECONDS = 60;

    private final MarketCalendarPort marketCalendarPort;

    private volatile CachedRate cached;

    public ExecutionExchangeRateProviderBridge(MarketCalendarPort marketCalendarPort) {
        this.marketCalendarPort = marketCalendarPort;
    }

    @Override
    public synchronized BigDecimal currentUsdKrwRate() {
        Instant now = Instant.now();
        if (cached != null && now.isBefore(cached.fetchedAt().plusSeconds(TTL_SECONDS))) {
            return cached.rate();
        }
        BigDecimal rate = marketCalendarPort.fetchExchangeRate().rate();
        cached = new CachedRate(rate, now);
        return rate;
    }

    private record CachedRate(BigDecimal rate, Instant fetchedAt) {
    }
}
