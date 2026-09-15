package com.baedang.trading.service;

import com.baedang.global.error.BusinessException;
import com.baedang.global.error.ErrorCode;
import com.baedang.stock.entity.MarketCountry;
import com.baedang.trading.entity.OrderSide;
import com.baedang.trading.model.MarketOrderAmount;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

import static com.baedang.trading.support.DecimalScaleValidator.isRepresentableAtScale;
import static com.baedang.trading.support.NumericBounds.MONEY_LIMIT;
import static com.baedang.trading.support.NumericBounds.RATE_LIMIT;

@Component
public class MarketOrderSettlementCalculator {

    private static final int USD_SCALE = 2;
    private static final int KRW_SCALE = 0;
    private static final RoundingMode MONEY_ROUNDING = RoundingMode.HALF_UP;

    private final BigDecimal feeRate;
    private final BigDecimal krSellTaxRate;
    private final BigDecimal usSecFeeRate;
    private final BigDecimal usSecFeeMinimumUsd;

    public MarketOrderSettlementCalculator(
            @Value("${trading.fee-rate}") BigDecimal feeRate,
            @Value("${trading.k-tax-rate}") BigDecimal krSellTaxRate,
            @Value("${trading.a-tax-rate}") BigDecimal usSecFeeRate,
            @Value("${trading.a-tax-min-usd}") BigDecimal usSecFeeMinimumUsd
    ) {
        for (BigDecimal value : new BigDecimal[]{feeRate, krSellTaxRate, usSecFeeRate, usSecFeeMinimumUsd}) {
            if (value == null || value.signum() < 0) throw new IllegalArgumentException("요율은 0 이상이어야 합니다");
        }
        this.feeRate = feeRate;
        this.krSellTaxRate = krSellTaxRate;
        this.usSecFeeRate = usSecFeeRate;
        this.usSecFeeMinimumUsd = usSecFeeMinimumUsd;
    }

    /** 시장가 주문의 현재가 기준 예상 금액을 계산합니다. */
    public MarketOrderAmount calculate(
            MarketCountry marketCountry,
            OrderSide side,
            BigDecimal executedPrice,
            BigDecimal quantity,
            BigDecimal exchangeRate
    ) {
        if (marketCountry == null || side == null || executedPrice == null || executedPrice.signum() <= 0) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
        if (quantity == null || quantity.signum() <= 0 || quantity.compareTo(RATE_LIMIT) >= 0
                || !isRepresentableAtScale(quantity, 6)) {
            throw new BusinessException(ErrorCode.INVALID_QUANTITY);
        }
        // USD는 원시 시세가 아닌 실제 정산 단가로 반올림한 뒤 저장 가능 범위를 검사합니다.
        BigDecimal price = marketCountry == MarketCountry.US ? usd(executedPrice) : executedPrice;
        if (price.compareTo(MONEY_LIMIT) >= 0 || !isRepresentableAtScale(price, 4)) {
            throw new BusinessException(ErrorCode.INVALID_SETTLEMENT_AMOUNT);
        }
        if (marketCountry == MarketCountry.US && (exchangeRate == null || exchangeRate.signum() <= 0
                || exchangeRate.compareTo(RATE_LIMIT) >= 0 || !isRepresentableAtScale(exchangeRate, 6))) {
            throw new BusinessException(ErrorCode.EXCHANGE_RATE_NOT_FOUND);
        }
        MarketOrderAmount amount = switch (marketCountry) {
            case KR -> calculateKr(side, price, quantity);
            case US -> calculateUs(side, price, quantity, exchangeRate);
        };
        validateMoney(amount.grossAmount(), amount.fee(), amount.tax(), amount.netAmount());
        return amount;
    }

    private MarketOrderAmount calculateKr(OrderSide side, BigDecimal priceKrw, BigDecimal quantity) {
        BigDecimal unroundedGrossAmountKrw = priceKrw.multiply(quantity);
        BigDecimal grossAmountKrw = krw(unroundedGrossAmountKrw);
        BigDecimal tradingFeeKrw = krw(grossAmountKrw.multiply(feeRate));
        BigDecimal sellChargeKrw = side == OrderSide.SELL
                ? krw(grossAmountKrw.multiply(krSellTaxRate))
                : BigDecimal.ZERO;
        BigDecimal netAmountKrw = netAmount(side, grossAmountKrw, tradingFeeKrw, sellChargeKrw);

        return new MarketOrderAmount(
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

    private MarketOrderAmount calculateUs(
            OrderSide side,
            BigDecimal priceUsd,
            BigDecimal quantity,
            BigDecimal exchangeRate
    ) {
        BigDecimal grossAmountUsd = priceUsd.multiply(quantity);
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

        return new MarketOrderAmount(
                priceUsd,
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

    private void validateMoney(BigDecimal... values) {
        for (BigDecimal value : values) {
            if (value.abs().compareTo(MONEY_LIMIT) >= 0 || !isRepresentableAtScale(value, KRW_SCALE)) {
                throw new BusinessException(ErrorCode.INVALID_SETTLEMENT_AMOUNT);
            }
        }
        // 0 이하 정산액은 기존 주문 정책에서 REJECTED로 기록하므로 여기서 차단하지 않습니다.
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
