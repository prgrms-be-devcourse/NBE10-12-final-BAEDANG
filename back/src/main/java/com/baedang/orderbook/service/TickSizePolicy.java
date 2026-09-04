package com.baedang.orderbook.service;

import com.baedang.orderbook.model.StockDescriptor;
import com.baedang.stock.entity.MarketCountry;
import com.baedang.stock.entity.StockCategory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Objects;

/**
 * 시장·상품별 유효 호가 가격 단위 정책 (설계서 §4.2).
 *
 * <p>단순히 "현재가의 단위"를 반환하는 데 그치지 않고, 가격 구간 경계를 넘는
 * 이전·다음 유효 가격을 계산한다. 설계서 §4.3의 요구사항대로 기준가를 한 번
 * HALF_UP 정렬한 뒤 같은 tick을 반복 가감하는 방식은 쓰지 않는다 — 그렇게 하면
 * 구간 경계(2,000 / 5,000 / 20,000 / 50,000 / 200,000 / 500,000원, $1.00)에서
 * 새 구간의 규칙이 다시 적용되지 않아 비유효 가격이 나온다.
 *
 * <p>모든 연산은 구간 자체를 무대로 삼는다: 후보는 반드시 그 구간의
 * {@code [lowerInclusive, upperExclusive)} 안에 있어야 한다. 구간을 벗어나는
 * 후보는 null로 취급하고 다른 구간 후보와 비교해 최소(위로)/최대(아래로)를 고른다.
 */
@Component
public class TickSizePolicy {

    private static final BigDecimal HUNDRED_MILLION = new BigDecimal("100000000");

    /** 국내 일반주·우선주 — 2023-01-25 KRX 기준. */
    private static final List<PriceGrid> KR_STOCK_GRIDS = List.of(
            new PriceGrid(BigDecimal.ZERO, new BigDecimal("2000"), BigDecimal.ONE),
            new PriceGrid(new BigDecimal("2000"), new BigDecimal("5000"), new BigDecimal("5")),
            new PriceGrid(new BigDecimal("5000"), new BigDecimal("20000"), new BigDecimal("10")),
            new PriceGrid(new BigDecimal("20000"), new BigDecimal("50000"), new BigDecimal("50")),
            new PriceGrid(new BigDecimal("50000"), new BigDecimal("200000"), new BigDecimal("100")),
            new PriceGrid(new BigDecimal("200000"), new BigDecimal("500000"), new BigDecimal("500")),
            new PriceGrid(new BigDecimal("500000"), null, new BigDecimal("1000"))
    );

    /** 국내 ETF·ETN — 2023-12-11 시행 기준. */
    private static final List<PriceGrid> KR_FUND_GRIDS = List.of(
            new PriceGrid(BigDecimal.ZERO, new BigDecimal("2000"), BigDecimal.ONE),
            new PriceGrid(new BigDecimal("2000"), null, new BigDecimal("5"))
    );

    /** 미국 주식 — 프로젝트 V1 정책 (Rule 612 하프페니 규정이 바뀌면 V2로 추가한다). */
    private static final List<PriceGrid> US_GRIDS = List.of(
            new PriceGrid(BigDecimal.ZERO, new BigDecimal("1.00"), new BigDecimal("0.0001")),
            new PriceGrid(new BigDecimal("1.00"), null, new BigDecimal("0.01"))
    );

    public BigDecimal nextValidPriceAbove(StockDescriptor stock, BigDecimal price) {
        requirePositive(price);
        return gridsFor(stock).stream()
                .map(grid -> grid.firstValidStrictlyAbove(price))
                .filter(Objects::nonNull)
                .min(BigDecimal::compareTo)
                .orElseThrow(() -> new IllegalArgumentException("다음 유효 호가를 계산할 수 없습니다"));
    }

    public BigDecimal previousValidPriceBelow(StockDescriptor stock, BigDecimal price) {
        requirePositive(price);
        BigDecimal result = gridsFor(stock).stream()
                .map(grid -> grid.lastValidStrictlyBelow(price))
                .filter(Objects::nonNull)
                .max(BigDecimal::compareTo)
                .orElseThrow(() -> new IllegalArgumentException("이전 유효 호가를 계산할 수 없습니다"));
        if (result.signum() <= 0) throw new IllegalArgumentException("이전 유효 호가는 양수여야 합니다");
        return result;
    }

    public boolean isValidPrice(StockDescriptor stock, BigDecimal price) {
        requirePositive(price);
        return gridsFor(stock).stream().anyMatch(grid -> grid.containsValid(price));
    }

    private List<PriceGrid> gridsFor(StockDescriptor stock) {
        if (stock.marketCountry() == MarketCountry.US) {
            return US_GRIDS;
        }
        StockCategory category = stock.stockCategory();
        return switch (category) {
            case INDIVIDUAL, PREFERRED -> KR_STOCK_GRIDS;
            case ETF, ETN -> KR_FUND_GRIDS;
        };
    }

    private static void requirePositive(BigDecimal price) {
        if (price == null || price.signum() <= 0) {
            throw new IllegalArgumentException("가격은 양수여야 합니다: " + price);
        }
    }

    /**
     * 유효 가격 구간. {@code upperExclusive}가 null이면 상한 없음.
     *
     * <p>DB 저장 정밀도(NUMERIC(19,4))를 넘어가는 가격이 나오지 않도록 결과에
     * {@code HUNDRED_MILLION} 상한을 둔다.
     */
    private record PriceGrid(BigDecimal lowerInclusive, BigDecimal upperExclusive, BigDecimal tickSize) {

        BigDecimal firstValidStrictlyAbove(BigDecimal price) {
            BigDecimal candidate = belowQuotient(price).add(BigDecimal.ONE).multiply(tickSize);
            return withinUpper(candidate) ? cap(candidate) : null;
        }

        BigDecimal lastValidStrictlyBelow(BigDecimal price) {
            BigDecimal[] quotientAndRemainder = price.divideAndRemainder(tickSize);
            BigDecimal candidate = quotientAndRemainder[1].signum() == 0
                    ? quotientAndRemainder[0].subtract(BigDecimal.ONE).multiply(tickSize)
                    : quotientAndRemainder[0].multiply(tickSize);
            if (candidate.compareTo(lowerInclusive) < 0 || candidate.signum() <= 0) return null;
            return withinUpper(candidate) ? cap(candidate) : null;
        }

        boolean containsValid(BigDecimal price) {
            if (price.compareTo(lowerInclusive) < 0 || !withinUpper(price)) return false;
            return price.remainder(tickSize).signum() == 0;
        }

        private boolean withinUpper(BigDecimal value) {
            return upperExclusive == null || value.compareTo(upperExclusive) < 0;
        }

        private BigDecimal belowQuotient(BigDecimal price) {
            return price.divideToIntegralValue(tickSize);
        }

        private BigDecimal cap(BigDecimal value) {
            return value.compareTo(HUNDRED_MILLION) > 0 ? null : value;
        }
    }
}
