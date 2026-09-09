package com.baedang.orderbook.model;

import com.baedang.stock.entity.MarketCountry;
import com.baedang.stock.entity.Stock;
import com.baedang.stock.entity.StockCategory;

import java.util.Objects;

/**
 * 호가 생성이 종목 마스터에서 필요로 하는 최소 불변 스냅샷입니다.
 *
 * <p>생성기·가격 단위 정책은 {@code Stock} 엔티티 전체가 아니라 이 기술자만 봅니다 —
 * 생성기는 DB/Clock을 조회하지 않는 순수 함수여야 하고(설계서 §4.4), 트랜잭션 밖에서
 * 만들어진 입력으로 실행되어야 해서 엔티티 참조를 넘기지 않습니다.
 */
public record StockDescriptor(
        Long stockId,
        MarketCountry marketCountry,
        StockCategory stockCategory,
        String currency
) {
    public StockDescriptor {
        Objects.requireNonNull(stockId, "stockId는 필수입니다");
        Objects.requireNonNull(marketCountry, "marketCountry는 필수입니다");
        Objects.requireNonNull(stockCategory, "stockCategory는 필수입니다");
        Objects.requireNonNull(currency, "currency는 필수입니다");
    }

    public static StockDescriptor from(Stock stock) {
        return new StockDescriptor(
                stock.getStockId(),
                stock.getMarketCountry(),
                stock.getStockCategory(),
                stock.getCurrency()
        );
    }
}
