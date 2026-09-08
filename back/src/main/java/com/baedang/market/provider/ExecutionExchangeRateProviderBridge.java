package com.baedang.market.provider;

import com.baedang.market.port.ExchangeRateQuote;
import com.baedang.market.port.ExecutionExchangeRateProvider;
import com.baedang.market.port.ExecutionExchangeRateSnapshot;
import com.baedang.market.port.MarketCalendarPort;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.ZoneOffset;

/**
 * {@link ExecutionExchangeRateProvider}의 구현체.
 *
 * <p>거래 모듈이 필요로 하는 "현재 체결 환율 하나"와, 시장 데이터 모듈이 제공하는
 * {@link MarketCalendarPort#fetchExchangeRate()}(라이브 조회)를 잇는 변환 계층입니다.
 * {@link MarketSessionProviderBridge}와 같은 위치의 클래스입니다.
 *
 * <p>수신 완료 후 1분 TTL과 Toss 원본 유효기간을 함께 적용합니다. 동시 요청은
 * 같은 캐시를 공유하며, 만료/잘못된 환율이나 조회 실패는 캐시하지 않습니다.
 * 차트 이력용 {@code exchange_rate} 테이블은 전혀 쓰지 않고, 항상
 * {@link MarketCalendarPort#fetchExchangeRate()}(라이브 조회)만 캐싱합니다.
 */
@Component
public class ExecutionExchangeRateProviderBridge implements ExecutionExchangeRateProvider {

    private final MarketCalendarPort marketCalendarPort;
    private final Clock clock;

    private ExecutionExchangeRateSnapshot cached;

    public ExecutionExchangeRateProviderBridge(MarketCalendarPort marketCalendarPort, Clock clock) {
        this.marketCalendarPort = marketCalendarPort;
        this.clock = clock;
    }

    @Override
    public BigDecimal currentUsdKrwRate() {
        return currentUsdKrwSnapshot().rate();
    }

    @Override
    public synchronized ExecutionExchangeRateSnapshot currentUsdKrwSnapshot() {
        if (cached != null && cached.isValidAt(clock.instant().atOffset(ZoneOffset.UTC))) {
            return cached;
        }
        ExchangeRateQuote quote = marketCalendarPort.fetchExchangeRate();
        // 요청 시작이 아닌 수신 완료 시각을 보존합니다. 실패/만료 응답은 캐시에 넣지 않습니다.
        ExecutionExchangeRateSnapshot snapshot = ExecutionExchangeRateSnapshot.from(quote, clock.instant().atOffset(ZoneOffset.UTC));
        cached = snapshot;
        return snapshot;
    }
}
