package com.baedang.trading.service;

import com.baedang.stock.entity.MarketCountry;
import com.baedang.trading.entity.OrderSide;
import com.baedang.trading.model.OrderAmount;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Component
public class OrderAmountCalculator {

    private static final int USD_SCALE = 2;
    private static final int KRW_SCALE = 0;
    private static final RoundingMode MONEY_ROUNDING = RoundingMode.HALF_UP;

    private final BigDecimal feeRate;
    private final BigDecimal krSellTaxRate;
    private final BigDecimal usSecFeeRate;
    private final BigDecimal usSecFeeMinimumUsd;

    public OrderAmountCalculator(
            @Value("${trading.fee-rate}") BigDecimal feeRate,
            @Value("${trading.k-tax-rate}") BigDecimal krSellTaxRate,
            @Value("${trading.a-tax-rate}") BigDecimal usSecFeeRate,
            @Value("${trading.a-tax-min-usd}") BigDecimal usSecFeeMinimumUsd
    ) {
        this.feeRate = feeRate;
        this.krSellTaxRate = krSellTaxRate;
        this.usSecFeeRate = usSecFeeRate;
        this.usSecFeeMinimumUsd = usSecFeeMinimumUsd;
    }

    /** 시장가 주문의 현재가 기준 예상 금액을 계산합니다. */
    public OrderAmount calculate(
            MarketCountry marketCountry,
            OrderSide side,
            BigDecimal executedPrice,
            BigDecimal quantity,
            BigDecimal exchangeRate
    ) {
        return switch (marketCountry) {
            case KR -> calculateKr(side, executedPrice, quantity);
            case US -> calculateUs(side, executedPrice, quantity, exchangeRate);
        };
    }

    private OrderAmount calculateKr(OrderSide side, BigDecimal priceKrw, BigDecimal quantity) {
        BigDecimal unroundedGrossAmountKrw = priceKrw.multiply(quantity);
        BigDecimal grossAmountKrw = krw(unroundedGrossAmountKrw);
        BigDecimal tradingFeeKrw = krw(grossAmountKrw.multiply(feeRate));
        BigDecimal sellChargeKrw = side == OrderSide.SELL
                ? krw(grossAmountKrw.multiply(krSellTaxRate))
                : BigDecimal.ZERO;
        BigDecimal netAmountKrw = netAmount(side, grossAmountKrw, tradingFeeKrw, sellChargeKrw);

        return new OrderAmount(
                priceKrw,
                BigDecimal.ONE,
                BigDecimal.ZERO,
                unroundedGrossAmountKrw,
                grossAmountKrw,
                tradingFeeKrw,
                sellChargeKrw,
                netAmountKrw,
                BigDecimal.ZERO
        );
    }

    private OrderAmount calculateUs(
            OrderSide side,
            BigDecimal priceUsd,
            BigDecimal quantity,
            BigDecimal exchangeRate
    ) {
        BigDecimal roundedPriceUsd = usd(priceUsd);
        BigDecimal grossAmountUsd = roundedPriceUsd.multiply(quantity);
        BigDecimal unroundedGrossAmountKrw = grossAmountUsd.multiply(exchangeRate);
        BigDecimal grossAmountKrw = krw(unroundedGrossAmountKrw);
        BigDecimal tradingFeeKrw = krw(grossAmountKrw.multiply(feeRate));

        BigDecimal secFeeUsd = BigDecimal.ZERO;
        if (side == OrderSide.SELL) {
            BigDecimal rawSecFeeUsd = grossAmountUsd.multiply(usSecFeeRate);
            secFeeUsd = usd(rawSecFeeUsd.max(usSecFeeMinimumUsd));
        }
        BigDecimal secFeeKrw = krw(secFeeUsd.multiply(exchangeRate));
        BigDecimal netAmountKrw = netAmount(side, grossAmountKrw, tradingFeeKrw, secFeeKrw);

        return new OrderAmount(
                roundedPriceUsd,
                exchangeRate,
                grossAmountUsd,
                unroundedGrossAmountKrw,
                grossAmountKrw,
                tradingFeeKrw,
                secFeeKrw,
                netAmountKrw,
                secFeeUsd
        );
    }

    private BigDecimal netAmount(
            OrderSide side,
            BigDecimal grossAmount,
            BigDecimal fee,
            BigDecimal tax
    ) {
        if (side == OrderSide.BUY) return grossAmount.add(fee);
        return grossAmount.subtract(fee).subtract(tax);
    }

    private BigDecimal usd(BigDecimal amount) {
        return amount.setScale(USD_SCALE, MONEY_ROUNDING);
    }

    private BigDecimal krw(BigDecimal amount) {
        return amount.setScale(KRW_SCALE, MONEY_ROUNDING);
    }
}
