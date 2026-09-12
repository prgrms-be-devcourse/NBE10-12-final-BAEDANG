package com.baedang.market.event.service;

import com.baedang.global.error.BusinessException;
import com.baedang.global.error.ErrorCode;
import com.baedang.market.event.entity.KrMarket;
import com.baedang.market.event.model.ActiveMarketHalt;
import com.baedang.market.event.repository.MarketEventRepository;
import com.baedang.stock.entity.MarketCountry;
import com.baedang.stock.entity.Stock;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 주문·체결이 지금 허용되는지 판정한다.
 *
 * <p>대상은 KR 시장 중 KOSPI·KOSDAQ뿐이다. {@code KR_ETC}와 미국 주식은 #102 범위 밖이라 차단하지
 * 않는다. 사이드카는 프로그램 매매 호가만 정지시키는 제도이므로 일반 사용자 주문 차단에 쓰지 않는다 —
 * 저장소에서도 {@code CIRCUIT_BREAKER}만 조회한다.
 *
 * <p>권위 있는 판정은 거래 트랜잭션 내부, account 행 잠금 이후에만 수행한다. 그래서 결과를 메모리에
 * 캐시하지 않고 호출마다 DB를 읽는다. 이벤트가 극히 드물고 {@code ix_market_event_active}가 있어
 * 주문마다 최신 상태를 읽는 편이 더 단순하고 안전하다.
 */
@Component
public class MarketTradingHaltPolicy {

    private final MarketEventRepository repository;

    public MarketTradingHaltPolicy(MarketEventRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
    }

    /**
     * @param at 판정 기준 시각. 주문 트랜잭션은 {@code clock.instant()}를 넘긴다.
     * @return 활성 CB가 있으면 그 이벤트, 대상 시장이 아니거나 활성 CB가 없으면 비어 있음
     */
    public Optional<ActiveMarketHalt> activeFor(Stock stock, Instant at) {
        Objects.requireNonNull(at, "at must not be null");
        return krMarketOf(stock)
                .flatMap(market -> repository.findActiveCircuitBreaker(market, at))
                .map(ActiveMarketHalt::from);
    }

    /**
     * Part 5 체결 워커가 쓰는 강제 판정. 활성 CB가 있으면 그 이벤트 데이터를 담아 거절한다.
     * 주문 접수 경로는 {@link #activeFor}로 직접 분기해 REJECTED를 저장한다.
     */
    public void requireTradingAllowed(Stock stock, Instant at) {
        activeFor(stock, at).ifPresent(halt -> {
            Map<String, Object> data = halt.asErrorData();
            throw new BusinessException(ErrorCode.MARKET_TRADING_HALTED, data);
        });
    }

    /**
     * {@code Stock.market}은 {@code KOSPI}·{@code KOSDAQ}·{@code KR_ETC}·미국 거래소 등이다.
     * #102가 다루는 두 시장만 매핑하고 나머지는 비적용으로 둔다.
     */
    private Optional<KrMarket> krMarketOf(Stock stock) {
        if (stock == null || stock.getMarketCountry() != MarketCountry.KR) {
            return Optional.empty();
        }
        return KrMarket.fromStockMarket(stock.getMarket());
    }
}
