package com.baedang.trading.service;

import com.baedang.global.error.BusinessException;
import com.baedang.global.error.ErrorCode;
import com.baedang.stock.entity.MarketCountry;
import com.baedang.trading.entity.OrderSide;
import com.baedang.trading.model.LimitOrderCommand;
import com.baedang.trading.model.MarketOrderAmount;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

import static com.baedang.trading.support.NumericBounds.MONEY_LIMIT;

/** 입력 통화의 접수 동결과 종목 통화의 체결 한도를 구분합니다. */
@Component
public class LimitOrderPricing {

    private final LimitOrderSettlementCalculator limitCalculator;
    private final MarketOrderSettlementCalculator estimateCalculator;

    public LimitOrderPricing(
            LimitOrderSettlementCalculator limitCalculator,
            MarketOrderSettlementCalculator estimateCalculator
    ) {
        this.limitCalculator = limitCalculator;
        this.estimateCalculator = estimateCalculator;
    }

    public record Price(BigDecimal limitPrice, BigDecimal reserve, MarketOrderAmount estimate) {}

    public Price calculate(LimitOrderCommand command, BigDecimal rate) {
        var terms = command.terms();
        boolean krwInput = "KRW".equals(command.currency());
        BigDecimal price = terms.marketCountry() == MarketCountry.US && krwInput
                ? command.requestedPrice().divide(rate, 2, RoundingMode.HALF_UP)
                : command.requestedPrice();
        if (price.signum() <= 0 || price.compareTo(MONEY_LIMIT) >= 0) {
            throw new BusinessException(ErrorCode.INVALID_SETTLEMENT_AMOUNT);
        }
        // 원화 입력 동결액은 USD 센트 환산 가격으로 역산하지 않습니다.
        BigDecimal reserve = terms.side() == OrderSide.BUY
                ? limitCalculator.initialReservedCash(
                        krwInput ? MarketCountry.KR : MarketCountry.US,
                        command.requestedPrice(),
                        terms.quantity(),
                        krwInput ? BigDecimal.ONE : rate)
                : BigDecimal.ZERO;
        // 미체결 견적은 한 번에 전량 체결한다고 가정한 값이며 누적 부분 체결을 확정하지 않습니다.
        MarketOrderAmount estimate = estimateCalculator.calculate(terms.marketCountry(), terms.side(), price, terms.quantity(), rate);
        return new Price(price, reserve, estimate);
    }
}
