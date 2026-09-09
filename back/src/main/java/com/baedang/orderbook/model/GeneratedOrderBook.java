package com.baedang.orderbook.model;

import com.baedang.orderbook.entity.OrderBookSide;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 생성기가 만든 호가 세트 전체. publisher가 이를 {@code OrderBookVersion}과
 * 최대 20개 {@code OrderBookLevel} 엔티티로 변환해 게시한다(ASK 10개, BID는
 * 국내 10개·미국 1~10개).
 *
 * <p>레벨은 ASK 1..10 → BID 1..N 순서로 담겨 있다.
 */
public record GeneratedOrderBook(
        Long stockId,
        BigDecimal basePrice,
        String currency,
        Instant quoteAt,
        Instant generatedAt,
        String policyVersion,
        long seed,
        List<GeneratedOrderBookLevel> levels
) {
    public GeneratedOrderBook {
        Objects.requireNonNull(stockId, "stockId는 필수입니다");
        Objects.requireNonNull(basePrice, "basePrice는 필수입니다");
        Objects.requireNonNull(currency, "currency는 필수입니다");
        Objects.requireNonNull(quoteAt, "quoteAt은 필수입니다");
        Objects.requireNonNull(generatedAt, "generatedAt은 필수입니다");
        Objects.requireNonNull(policyVersion, "policyVersion은 필수입니다");
        levels = List.copyOf(Objects.requireNonNull(levels, "levels는 필수입니다"));
    }

    public List<GeneratedOrderBookLevel> levelsBySide(OrderBookSide side) {
        return levels.stream()
                .filter(level -> level.side() == side)
                .toList();
    }

    /** 최우선 매도(ASK 1). */
    public GeneratedOrderBookLevel bestAsk() {
        return levelAt(OrderBookSide.ASK, 1);
    }

    /** 최우선 매수(BID 1). */
    public GeneratedOrderBookLevel bestBid() {
        return levelAt(OrderBookSide.BID, 1);
    }

    private GeneratedOrderBookLevel levelAt(OrderBookSide side, int depth) {
        return levels.stream()
                .filter(level -> level.side() == side && level.levelDepth() == depth)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("레벨이 없습니다: " + side + " " + depth));
    }
}
