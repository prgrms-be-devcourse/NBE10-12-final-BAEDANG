package com.baedang.orderbook.service;

import com.baedang.orderbook.config.OrderBookProperties;
import com.baedang.orderbook.entity.OrderBookSide;
import com.baedang.orderbook.model.GeneratedOrderBook;
import com.baedang.orderbook.model.GeneratedOrderBookLevel;
import com.baedang.orderbook.model.StockDescriptor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 고정 seed로 재현 가능한 순수 가상 호가 생성기 (설계서 §4.3~§4.4).
 *
 * <p>DB·Clock·Toss를 조회하지 않는다. 입력(policy·stock·basePrice·quoteAt·
 * generatedAt·seed)이 같으면 가격과 수량이 반드시 같게 나온다. seed는 publisher가
 * 버전마다 생성해 이 매개변수로 넘긴다.
 *
 * <p>V1 가격 배열: ASK 1은 basePrice보다 큰 첫 유효 가격, BID 1은 basePrice보다
 * 작은 첫 유효 가격이고, 이후 각 레벨은 직전 레벨의 다음/이전 유효 가격이다.
 * 구간 경계를 지날 때 새 구간의 규칙이 다시 적용되므로 {@link TickSizePolicy}에
 * 위임한다. BID 10을 양수 유효 가격으로 만들 수 없는 저가 종목은 예외를 던진다 —
 * 같은 가격 반복이나 1원 강제 치환은 하지 않는다.
 */
@Component
public class OrderBookGenerator {

    /** V1 깊이 배수 (설계서 §4.4). */
    private static final List<BigDecimal> DEPTH_MULTIPLIERS = List.of(
            new BigDecimal("1.00"),
            new BigDecimal("0.96"),
            new BigDecimal("0.92"),
            new BigDecimal("0.86"),
            new BigDecimal("0.80"),
            new BigDecimal("0.72"),
            new BigDecimal("0.64"),
            new BigDecimal("0.56"),
            new BigDecimal("0.48"),
            new BigDecimal("0.40")
    );

    private final TickSizePolicy tickSizePolicy;

    public OrderBookGenerator(TickSizePolicy tickSizePolicy) {
        this.tickSizePolicy = tickSizePolicy;
    }

    public GeneratedOrderBook generate(
            OrderBookProperties policy,
            StockDescriptor stock,
            BigDecimal basePrice,
            Instant quoteAt,
            Instant generatedAt,
            long seed
    ) {
        Random random = new Random(seed);
        BigDecimal baseNotional = baseNotionalFor(policy, stock);
        int levelsPerSide = policy.levelsPerSide();

        List<GeneratedOrderBookLevel> levels = new ArrayList<>(levelsPerSide * 2);
        levels.addAll(generateSide(policy, stock, OrderBookSide.ASK, basePrice, baseNotional, random));
        levels.addAll(generateSide(policy, stock, OrderBookSide.BID, basePrice, baseNotional, random));

        return new GeneratedOrderBook(
                stock.stockId(),
                basePrice,
                stock.currency(),
                quoteAt,
                generatedAt,
                policy.policyVersion(),
                seed,
                levels
        );
    }

    private List<GeneratedOrderBookLevel> generateSide(
            OrderBookProperties policy,
            StockDescriptor stock,
            OrderBookSide side,
            BigDecimal basePrice,
            BigDecimal baseNotional,
            Random random
    ) {
        List<GeneratedOrderBookLevel> levels = new ArrayList<>(policy.levelsPerSide());
        BigDecimal price = basePrice;
        for (int depth = 1; depth <= policy.levelsPerSide(); depth++) {
            price = side == OrderBookSide.ASK
                    ? tickSizePolicy.nextValidPriceAbove(stock, price)
                    : tickSizePolicy.previousValidPriceBelow(stock, price);
            levels.add(new GeneratedOrderBookLevel(side, depth, price, quantity(price, baseNotional, depth, policy, random)));
        }
        return levels;
    }

    private BigDecimal quantity(
            BigDecimal price,
            BigDecimal baseNotional,
            int depth,
            OrderBookProperties policy,
            Random random
    ) {
        int noiseBps = random.nextInt(policy.noiseMaxBps() - policy.noiseMinBps() + 1) + policy.noiseMinBps();
        BigDecimal noiseFactor = BigDecimal.valueOf(noiseBps, 4);
        BigDecimal baseQuantity = baseNotional.divide(price, 0, RoundingMode.HALF_UP);
        return baseQuantity
                .multiply(DEPTH_MULTIPLIERS.get(depth - 1))
                .multiply(noiseFactor)
                .setScale(0, RoundingMode.HALF_UP)
                .max(policy.minQuantity())
                .min(policy.maxQuantity());
    }

    private BigDecimal baseNotionalFor(OrderBookProperties policy, StockDescriptor stock) {
        return "USD".equals(stock.currency()) ? policy.usBaseNotional() : policy.krBaseNotional();
    }
}
