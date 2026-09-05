package com.baedang.trading.service;

import com.baedang.global.error.BusinessException;
import com.baedang.global.error.ErrorCode;
import com.baedang.stock.entity.MarketCountry;
import com.baedang.trading.entity.OrderSide;
import com.baedang.trading.model.BuyReservationResult;
import com.baedang.trading.model.CumulativeSettlementState;
import com.baedang.trading.model.ExecutionAmounts;
import com.baedang.trading.model.LimitOrderSettlementResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

import static com.baedang.trading.support.DecimalScaleValidator.isRepresentableAtScale;

/** 호가 하나의 주문 누적 차액 계산. 호가 탐색/수량 선택/외부 조회/DB 변경은 호출부 책임입니다. */
@Component
public class LimitOrderSettlementCalculator {
    private static final BigDecimal MONEY_LIMIT = new BigDecimal("1000000000000000"); // NUMERIC(19,4)
    private static final BigDecimal RATE_LIMIT = new BigDecimal("10000000000000"); // NUMERIC(19,6)

    private final BigDecimal feeRate;
    private final BigDecimal krSellTaxRate;
    private final BigDecimal usSecFeeRate;
    private final BigDecimal usSecFeeMinimumUsd;
    private final BigDecimal maxOrderQuantity;

    public LimitOrderSettlementCalculator(
            @Value("${trading.fee-rate}") BigDecimal feeRate,
            @Value("${trading.k-tax-rate}") BigDecimal krSellTaxRate,
            @Value("${trading.a-tax-rate}") BigDecimal usSecFeeRate,
            @Value("${trading.a-tax-min-usd}") BigDecimal usSecFeeMinimumUsd,
            @Value("${trading.max-order-quantity}") BigDecimal maxOrderQuantity) {
        for (BigDecimal value : new BigDecimal[]{feeRate, krSellTaxRate, usSecFeeRate, usSecFeeMinimumUsd}) {
            if (value == null || value.signum() < 0) throw new IllegalArgumentException("요율은 0 이상이어야 합니다");
        }
        if (maxOrderQuantity == null || maxOrderQuantity.signum() <= 0
                || !isRepresentableAtScale(maxOrderQuantity, 0)
                || maxOrderQuantity.compareTo(RATE_LIMIT) >= 0) {
            throw new IllegalArgumentException("최대 주문 수량이 올바르지 않습니다");
        }
        this.feeRate = feeRate;
        this.krSellTaxRate = krSellTaxRate;
        this.usSecFeeRate = usSecFeeRate;
        this.usSecFeeMinimumUsd = usSecFeeMinimumUsd;
        this.maxOrderQuantity = maxOrderQuantity;
    }

    public LimitOrderSettlementResult calculate(MarketCountry country, OrderSide side,
            BigDecimal price, BigDecimal quantity, BigDecimal exchangeRate, CumulativeSettlementState previous) {
        if (country == null || side == null || previous == null) throw new BusinessException(ErrorCode.INVALID_INPUT);
        validateQuantity(quantity);
        validateQuantity(previous.quantity().add(quantity));
        if (price == null || price.signum() <= 0 || price.compareTo(MONEY_LIMIT) >= 0
                || !isRepresentableAtScale(price, country == MarketCountry.KR ? 0 : 2)) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
        BigDecimal rate = country == MarketCountry.KR ? BigDecimal.ONE : exchangeRate;
        if (rate == null || rate.signum() <= 0 || rate.compareTo(RATE_LIMIT) >= 0
                || !isRepresentableAtScale(rate, 6)) {
            throw new BusinessException(ErrorCode.EXCHANGE_RATE_NOT_FOUND);
        }
        validateState(country, side, previous);

        BigDecimal nativeGross = price.multiply(quantity);
        BigDecimal rawKrw = nativeGross.multiply(rate);
        BigDecimal totalNative = previous.nativeGrossAmount().add(nativeGross);
        BigDecimal totalRawKrw = previous.unroundedGrossAmountKrw().add(rawKrw);
        BigDecimal totalGross = krw(totalRawKrw);
        BigDecimal totalFee = krw(totalGross.multiply(feeRate));
        BigDecimal totalSec = country == MarketCountry.US && side == OrderSide.SELL
                ? secFee(totalNative) : BigDecimal.ZERO;
        BigDecimal secDelta = totalSec.subtract(previous.secFeeUsd());
        // 이미 부과한 SEC의 원화 환산액은 당시 환율을 유지합니다.
        BigDecimal totalRawTax = previous.unroundedTaxKrw().add(secDelta.multiply(rate));
        BigDecimal totalTax = tax(country, side, totalGross, totalRawTax);
        BigDecimal gross = totalGross.subtract(previous.grossAmountKrw());
        BigDecimal fee = totalFee.subtract(previous.feeKrw());
        BigDecimal tax = totalTax.subtract(previous.taxKrw());
        BigDecimal net = side == OrderSide.BUY ? gross.add(fee) : gross.subtract(fee).subtract(tax);
        validateMoney(totalGross, totalFee, totalTax,
                side == OrderSide.BUY ? totalGross.add(totalFee) : totalGross.subtract(totalFee).subtract(totalTax));
        if (net.signum() <= 0) return new LimitOrderSettlementResult(net, null, previous);

        var amounts = new ExecutionAmounts(country == MarketCountry.US ? nativeGross : BigDecimal.ZERO,
                rawKrw, secDelta, gross, fee, tax, net);
        var next = new CumulativeSettlementState(previous.quantity().add(quantity), totalNative, totalRawKrw,
                totalSec, totalRawTax, totalGross, totalFee, totalTax);
        return new LimitOrderSettlementResult(net, amounts, next);
    }

    /** 접수 당시 환율로 지정가 전체 금액과 수수료를 동결합니다. 환율 상승 버퍼는 없습니다. */
    public BigDecimal initialReservedCash(MarketCountry country, BigDecimal limitPrice,
                                          BigDecimal quantity, BigDecimal exchangeRate) {
        return calculate(country, OrderSide.BUY, limitPrice, quantity, exchangeRate,
                CumulativeSettlementState.empty()).requireExecutionAmounts().netAmountKrw();
    }

    /** 자유 예수금은 입력받지 않습니다. 수량 탐색 없이 주어진 매수 후보의 동결 조건만 판단합니다. */
    public BuyReservationResult reserveAfterBuy(BigDecimal reservedCash, BigDecimal remainingQuantity,
                                               BigDecimal fillQuantity, BigDecimal netAmountKrw) {
        validateQuantity(remainingQuantity);
        validateQuantity(fillQuantity);
        if (fillQuantity.compareTo(remainingQuantity) > 0 || reservedCash == null || reservedCash.signum() < 0
                || !isRepresentableAtScale(reservedCash, 0) || netAmountKrw == null
                || !isRepresentableAtScale(netAmountKrw, 0)) throw new BusinessException(ErrorCode.INVALID_INPUT);
        validateMoney(reservedCash, netAmountKrw.abs());
        BigDecimal remainingCash = reservedCash.subtract(netAmountKrw);
        boolean fullyFilled = fillQuantity.compareTo(remainingQuantity) == 0;
        if (netAmountKrw.signum() <= 0 || remainingCash.signum() < 0
                || (!fullyFilled && remainingCash.signum() == 0)) {
            return new BuyReservationResult(false, reservedCash, BigDecimal.ZERO);
        }
        return fullyFilled ? new BuyReservationResult(true, BigDecimal.ZERO, remainingCash)
                : new BuyReservationResult(true, remainingCash, BigDecimal.ZERO);
    }

    private void validateQuantity(BigDecimal quantity) {
        if (quantity == null || quantity.signum() <= 0 || !isRepresentableAtScale(quantity, 0)
                || quantity.compareTo(maxOrderQuantity) > 0) throw new BusinessException(ErrorCode.INVALID_QUANTITY);
    }

    private void validateState(MarketCountry country, OrderSide side, CumulativeSettlementState state) {
        // 고정 요율과 저장된 누적값이 어긋나면 과거 금액을 덮어 맞추지 않고 중단합니다.
        boolean empty = state.quantity().signum() == 0;
        BigDecimal expectedSec = !empty && country == MarketCountry.US && side == OrderSide.SELL
                ? secFee(state.nativeGrossAmount()) : BigDecimal.ZERO;
        if (!isRepresentableAtScale(state.quantity(), 0)
                || !isRepresentableAtScale(state.nativeGrossAmount(), country == MarketCountry.KR ? 0 : 2)
                || (empty && (state.nativeGrossAmount().signum() != 0 || state.unroundedGrossAmountKrw().signum() != 0
                    || state.unroundedTaxKrw().signum() != 0))
                || (!empty && (state.nativeGrossAmount().signum() <= 0 || state.unroundedGrossAmountKrw().signum() <= 0))
                || state.secFeeUsd().compareTo(expectedSec) != 0
                || (expectedSec.signum() == 0 && state.unroundedTaxKrw().signum() != 0)
                || (expectedSec.signum() > 0 && state.unroundedTaxKrw().signum() <= 0)
                || (country == MarketCountry.KR && state.nativeGrossAmount().compareTo(state.unroundedGrossAmountKrw()) != 0)
                || state.grossAmountKrw().compareTo(krw(state.unroundedGrossAmountKrw())) != 0
                || state.feeKrw().compareTo(krw(state.grossAmountKrw().multiply(feeRate))) != 0
                || state.taxKrw().compareTo(tax(country, side, state.grossAmountKrw(), state.unroundedTaxKrw())) != 0) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "누적 체결 근거와 고정 정산 정책이 일치하지 않습니다");
        }
    }

    private BigDecimal tax(MarketCountry country, OrderSide side, BigDecimal gross, BigDecimal rawTax) {
        if (side == OrderSide.BUY) return BigDecimal.ZERO;
        return country == MarketCountry.KR ? krw(gross.multiply(krSellTaxRate)) : krw(rawTax);
    }

    private BigDecimal secFee(BigDecimal nativeGross) {
        return nativeGross.multiply(usSecFeeRate).max(usSecFeeMinimumUsd).setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal krw(BigDecimal amount) {
        return amount.setScale(0, RoundingMode.HALF_UP);
    }

    private void validateMoney(BigDecimal... values) {
        for (BigDecimal value : values) {
            if (value.abs().compareTo(MONEY_LIMIT) >= 0) throw new BusinessException(ErrorCode.INVALID_SETTLEMENT_AMOUNT);
        }
    }
}
