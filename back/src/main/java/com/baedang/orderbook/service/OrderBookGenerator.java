package com.baedang.orderbook.service;

import com.baedang.orderbook.config.OrderBookProperties;
import com.baedang.orderbook.entity.OrderBookSide;
import com.baedang.orderbook.model.GeneratedOrderBook;
import com.baedang.orderbook.model.GeneratedOrderBookLevel;
import com.baedang.orderbook.model.StockDescriptor;
import com.baedang.stock.entity.MarketCountry;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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
 * 위임한다. V1 깊이는 {@link #DEPTH_MULTIPLIERS}의 10단계로 고정한다. ASK는 항상
 * 10개를 만들고, 미국 BID는 양수 유효 가격이 남아 있는 깊이(1~10개)까지만 만든다.
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

        List<GeneratedOrderBookLevel> levels = new ArrayList<>(DEPTH_MULTIPLIERS.size() * 2);
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
        List<GeneratedOrderBookLevel> levels = new ArrayList<>(DEPTH_MULTIPLIERS.size());
        BigDecimal price = basePrice;
        for (int depth = 1; depth <= DEPTH_MULTIPLIERS.size(); depth++) {
            Optional<BigDecimal> nextPrice = side == OrderBookSide.ASK
                    ? Optional.of(tickSizePolicy.nextValidPriceAbove(stock, price))
                    : tickSizePolicy.findPreviousValidPriceBelow(stock, price);
            if (nextPrice.isEmpty()) {
                if (side == OrderBookSide.BID
                        && stock.marketCountry() == MarketCountry.US
                        && !levels.isEmpty()) {
                    break;
                }
                throw new IllegalArgumentException("이전 유효 호가를 계산할 수 없습니다");
            }
            price = nextPrice.orElseThrow();
            levels.add(new GeneratedOrderBookLevel(
                    side, depth, price, quantity(stock, price, baseNotional, depth, policy, random)));
        }
        return levels;
    }

    private BigDecimal quantity(
            StockDescriptor stock,
            BigDecimal price,
            BigDecimal baseNotional,
            int depth,
            OrderBookProperties policy,
            Random random
    ) {
        int noiseBps = random.nextInt(policy.noiseMaxBps() - policy.noiseMinBps() + 1) + policy.noiseMinBps();
        BigDecimal noiseFactor = BigDecimal.valueOf(noiseBps, 4);
        BigDecimal baseQuantity = baseNotional.divide(price, 0, RoundingMode.HALF_UP);
        BigDecimal roundBoost = roundNumberBoost(price, tickSizePolicy.tickSizeAt(stock, price));
        return baseQuantity
                .multiply(DEPTH_MULTIPLIERS.get(depth - 1))
                .multiply(noiseFactor)
                .multiply(roundBoost)
                .setScale(0, RoundingMode.HALF_UP)
                .max(policy.minQuantity())
                .min(policy.maxQuantity());
    }

    /**
     * 라운드 넘버 부스트 — 사람이 딱 떨어지는 가격에 주문을 몰아 두는 실증 효과
     * (Osler 계열: $1·$0.10 경계 클러스터링)를 흉내 낸다.
     *
     * <p>판정 기준은 절대 원화가 아니라 <b>그 가격 구간의 호가 단위(tick) 배수</b>다:
     * price = tick × steps로 놓면 steps가 100/50/10의 배수일수록 강하게 부스트한다.
     * 그러면 70,000원 종목(100원 틱)은 1만·5천·1천원 경계에서, 700원 종목(1원 틱)은
     * 100·50·10원 경계에서, 미국 종목(0.01달러 틱)은 $1·$0.50·$0.10 경계에서
     * 자동으로 같은 리듬이 나온다 — 가격대별 별도 표가 필요 없다.
     *
     * <p>벽 위치는 현재가와 함께 움직인다(현재가가 70,350이면 70,000 벽이 창 안으로,
     * 70,050이면 벽이 창 밖으로 밀려난다). 깊이 인덱스에 고정 파형을 얹는 방식은
     * 종목·버전 불문 같은 자리에 영구 벽이 생겨 설계서가 피하려는 "고정 벽 모양"에
     * 걸리므로 쓰지 않는다.
     */
    static BigDecimal roundNumberBoost(BigDecimal price, BigDecimal tickSize) {
        BigDecimal steps = price.divideToIntegralValue(tickSize);
        if (steps.remainder(new BigDecimal("100")).signum() == 0) return new BigDecimal("1.60");
        if (steps.remainder(new BigDecimal("50")).signum() == 0) return new BigDecimal("1.40");
        if (steps.remainder(new BigDecimal("10")).signum() == 0) return new BigDecimal("1.15");
        return BigDecimal.ONE;
    }

    private BigDecimal baseNotionalFor(OrderBookProperties policy, StockDescriptor stock) {
        return "USD".equals(stock.currency()) ? policy.usBaseNotional() : policy.krBaseNotional();
    }
}
