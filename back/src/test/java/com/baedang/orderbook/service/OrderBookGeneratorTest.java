package com.baedang.orderbook.service;

import com.baedang.orderbook.config.OrderBookProperties;
import com.baedang.orderbook.entity.OrderBookSide;
import com.baedang.orderbook.model.GeneratedOrderBook;
import com.baedang.orderbook.model.GeneratedOrderBookLevel;
import com.baedang.orderbook.model.StockDescriptor;
import com.baedang.stock.entity.MarketCountry;
import com.baedang.stock.entity.StockCategory;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderBookGeneratorTest {

    private final OrderBookGenerator generator = new OrderBookGenerator(new TickSizePolicy());

    private static OrderBookProperties v1() {
        return new OrderBookProperties(
                false, "V1", Duration.ofSeconds(3), 10, 1, Duration.ofSeconds(15),
                new BigDecimal("20000000"), new BigDecimal("15000"),
                BigDecimal.ONE, new BigDecimal("1000000"), 8000, 12000, Duration.ofMinutes(1)
        );
    }

    private static StockDescriptor krIndividual() {
        return new StockDescriptor(1L, MarketCountry.KR, StockCategory.INDIVIDUAL, "KRW");
    }

    private static StockDescriptor usIndividual() {
        return new StockDescriptor(5L, MarketCountry.US, StockCategory.INDIVIDUAL, "USD");
    }

    private GeneratedOrderBook generateWithSeed(long seed) {
        return generator.generate(
                v1(),
                krIndividual(),
                new BigDecimal("70000"),
                Instant.parse("2026-09-03T01:00:00Z"),
                Instant.parse("2026-09-03T01:00:03Z"),
                seed
        );
    }

    @Test
    void ASK와_BID를_각각_10개_생성하고_역전시키지_않는다() {
        GeneratedOrderBook book = generateWithSeed(42L);

        assertThat(book.levels()).hasSize(20);
        assertThat(book.levelsBySide(OrderBookSide.ASK)).hasSize(10);
        assertThat(book.levelsBySide(OrderBookSide.BID)).hasSize(10);
        assertThat(book.bestBid().price()).isLessThan(book.bestAsk().price());
    }

    @Test
    void 기준가를_끼고_BID10이_ASK_1보다_작은_단조_배열이다() {
        GeneratedOrderBook book = generateWithSeed(7L);

        List<GeneratedOrderBookLevel> asks = book.levelsBySide(OrderBookSide.ASK);
        List<GeneratedOrderBookLevel> bids = book.levelsBySide(OrderBookSide.BID);

        assertThat(asks).extracting(GeneratedOrderBookLevel::levelDepth).containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
        assertThat(bids).extracting(GeneratedOrderBookLevel::levelDepth).containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
        assertThat(asks).isSortedAccordingTo(
                (a, b) -> a.price().compareTo(b.price()));
        assertThat(bids).isSortedAccordingTo(
                (a, b) -> b.price().compareTo(a.price()));
        assertThat(bids.get(9).price()).isLessThan(asks.get(0).price());
        assertThat(asks.get(0).price()).isGreaterThan(book.basePrice());
        assertThat(bids.get(0).price()).isLessThan(book.basePrice());
        assertThat(book.levels()).allSatisfy(level -> assertThat(level.price()).isPositive());
    }

    @Test
    void 같은_입력과_seed는_같은_결과를_만든다() {
        GeneratedOrderBook first = generateWithSeed(42L);
        GeneratedOrderBook second = generateWithSeed(42L);

        assertThat(second).isEqualTo(first);
    }

    @Test
    void 다른_seed는_다른_결과를_만든다() {
        assertThat(generateWithSeed(41L)).isNotEqualTo(generateWithSeed(42L));
    }

    @Test
    void 수량은_정수이며_설정된_범위에_있다() {
        GeneratedOrderBook book = generateWithSeed(42L);

        assertThat(book.levels()).allSatisfy(level -> {
            assertThat(level.quantity().scale()).isZero();
            assertThat(level.quantity()).isBetween(new BigDecimal("1"), new BigDecimal("1000000"));
        });
    }

    @Test
    void 깊이_이전에_상단_유동성이_크다() {
        // 노이즈(0.8~1.2)보다 깊이 배수(1.00→0.40)가 우세하므로 ASK 1 수량이
        // ASK 10보다 반드시 커야 한다는 보장(설계서 §4 "고정 벽 모양 회피")을 확인한다.
        GeneratedOrderBook book = generateWithSeed(123L);

        assertThat(book.bestAsk().quantity())
                .isGreaterThan(book.levelsBySide(OrderBookSide.ASK).get(9).quantity());
    }

    @Test
    void 가격_구간을_넘어_가도_새_구간_단위를_적용한다() {
        GeneratedOrderBook book = generator.generate(
                v1(), krIndividual(), new BigDecimal("1995"),
                Instant.parse("2026-09-03T01:00:00Z"),
                Instant.parse("2026-09-03T01:00:03Z"), 42L
        );

        List<String> askPrices = book.levelsBySide(OrderBookSide.ASK).stream()
                .map(level -> level.price().stripTrailingZeros().toPlainString())
                .toList();
        // 1,995에서 1원 단위 구간을 따라 1,999까지 올린 뒤, 2,000부터는 5원 단위로 전환된다.
        assertThat(askPrices).containsExactly(
                "1996", "1997", "1998", "1999", "2000",
                "2005", "2010", "2015", "2020", "2025");
    }

    @Test
    void BID를_양수_유효_가격으로_10개_만들_수_없으면_거절한다() {
        // 1원 미만 구간이 없으므로 BID 10개를 만들 수 없다 — 같은 가격 반복이나
        // 1원 강제 치환이 아니라 예외로 실패해야 한다(설계서 §4.3).
        assertThatThrownBy(() -> generator.generate(
                v1(), krIndividual(), new BigDecimal("5"),
                Instant.parse("2026-09-03T01:00:00Z"),
                Instant.parse("2026-09-03T01:00:03Z"), 42L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 미국_종목은_USD_기준_물량과_0_01_단위를_쓴다() {
        GeneratedOrderBook book = generator.generate(
                v1(), usIndividual(), new BigDecimal("100.00"),
                Instant.parse("2026-09-03T01:00:00Z"),
                Instant.parse("2026-09-03T01:00:03Z"), 42L
        );

        assertThat(book.currency()).isEqualTo("USD");
        assertThat(book.bestAsk().price()).isEqualByComparingTo("100.01");
        assertThat(book.bestBid().price()).isEqualByComparingTo("99.99");
        // baseNotional 15,000 / 100.01 ≈ 150주 × 깊이 배수 × 노이즈(0.8~1.2)
        assertThat(book.bestAsk().quantity()).isBetween(new BigDecimal("120"), new BigDecimal("180"));
    }

    @Test
    void 생성기는_policy_값을_그대로_기록한다() {
        GeneratedOrderBook book = generateWithSeed(42L);

        assertThat(book.stockId()).isEqualTo(1L);
        assertThat(book.policyVersion()).isEqualTo("V1");
        assertThat(book.seed()).isEqualTo(42L);
        assertThat(book.quoteAt()).isEqualTo(Instant.parse("2026-09-03T01:00:00Z"));
        assertThat(book.generatedAt()).isEqualTo(Instant.parse("2026-09-03T01:00:03Z"));
    }
}
