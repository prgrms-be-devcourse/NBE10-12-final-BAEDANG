package com.baedang.trading.service;

import com.baedang.global.error.BusinessException;
import com.baedang.global.error.ErrorCode;
import com.baedang.stock.entity.MarketCountry;
import com.baedang.trading.entity.OrderSide;
import com.baedang.trading.model.BuyReservationResult;
import com.baedang.trading.model.CumulativeSettlementState;
import com.baedang.trading.model.LimitExecutionPlan;
import com.baedang.trading.model.LimitOrderSettlementResult;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.baedang.trading.support.DecimalScaleValidator.isRepresentableAtScale;
import static com.baedang.trading.model.LimitExecutionPlan.StopReason.*;

/** 실제 실행과 비구속성 preview가 공유하는 순수 함수. 엔티티·외부 API·DB에 의존하지 않습니다. */
@Component
public class LimitOrderExecutionPlanner {
    private final LimitOrderSettlementCalculator calculator;

    public LimitOrderExecutionPlanner(LimitOrderSettlementCalculator calculator) { this.calculator = calculator; }

    public LimitExecutionPlan plan(MarketCountry country, OrderSide side, BigDecimal limitPrice,
            BigDecimal remaining, BigDecimal reservedCash, BigDecimal rate,
            CumulativeSettlementState previous, List<LimitExecutionPlan.Level> levels) {
        if (country == null || side == null || limitPrice == null || limitPrice.signum() <= 0
                || remaining == null || remaining.signum() <= 0 || !isRepresentableAtScale(remaining, 0)
                || reservedCash == null || reservedCash.signum() < 0 || !isRepresentableAtScale(reservedCash, 0)
                || previous == null || levels == null) throw new BusinessException(ErrorCode.INVALID_INPUT);
        validateLevels(levels, side);
        List<LimitExecutionPlan.Fill> fills = new ArrayList<>();
        BigDecimal cash = reservedCash;
        BigDecimal released = BigDecimal.ZERO;
        CumulativeSettlementState state = previous;
        LimitExecutionPlan.StopReason reason = NO_LIQUIDITY;
        for (LimitExecutionPlan.Level level : levels) {
            if (side == OrderSide.BUY ? level.price().compareTo(limitPrice) > 0
                    : level.price().compareTo(limitPrice) < 0) { reason = PRICE_LIMIT; break; }
            if (level.quantity().signum() == 0) continue;
            BigDecimal quantity = remaining.min(level.quantity());
            LimitOrderSettlementResult result = calculate(country, side, level.price(), quantity, rate, state);
            if (side == OrderSide.BUY && (result == null || (result.isExecutable()
                    && !calculator.reserveAfterBuy(cash, remaining, quantity, result.netAmountKrw()).executable()))) {
                // 전량 체결의 정확한 동결 소진은 허용하므로 위에서 먼저 판정합니다.
                // 부분 후보만 탐색하여 예약액 1원 이상을 유지합니다. 0원 후보는 하한 영역입니다.
                long low = 1, high = quantity.longValueExact(), best = 0;
                while (low <= high) {
                    long mid = low + (high - low) / 2;
                    LimitOrderSettlementResult candidate = calculate(country, side, level.price(), BigDecimal.valueOf(mid), rate, state);
                    if (candidate == null || candidate.netAmountKrw().compareTo(cash.subtract(BigDecimal.ONE)) > 0) {
                        high = mid - 1;
                    } else {
                        if (candidate.isExecutable()) best = mid;
                        low = mid + 1;
                    }
                }
                if (best == 0) { reason = INSUFFICIENT_RESERVED_CASH; break; }
                quantity = BigDecimal.valueOf(best);
                result = calculator.calculate(country, side, level.price(), quantity, rate, state);
            }
            if (result == null || !result.isExecutable()) { reason = NON_POSITIVE_SETTLEMENT; break; }
            BuyReservationResult reservation = side == OrderSide.BUY
                    ? calculator.reserveAfterBuy(cash, remaining, quantity, result.netAmountKrw())
                    : new BuyReservationResult(true, BigDecimal.ZERO, BigDecimal.ZERO);
            if (!reservation.executable()) throw new BusinessException(ErrorCode.INTERNAL_ERROR);
            cash = reservation.reservedCashAfter();
            released = released.add(reservation.releasedCash());
            fills.add(new LimitExecutionPlan.Fill(level.levelId(), level.price(), quantity,
                    result.requireExecutionAmounts(), cash, reservation.releasedCash()));
            state = result.nextState();
            remaining = remaining.subtract(quantity);
            if (remaining.signum() == 0) { reason = FILLED; break; }
            if (quantity.compareTo(level.quantity()) < 0) { reason = INSUFFICIENT_RESERVED_CASH; break; }
        }
        return new LimitExecutionPlan(fills, remaining, cash, released, state, reason);
    }

    private LimitOrderSettlementResult calculate(MarketCountry country, OrderSide side, BigDecimal price,
            BigDecimal quantity, BigDecimal rate, CumulativeSettlementState state) {
        try {
            return calculator.calculate(country, side, price, quantity, rate, state);
        } catch (BusinessException exception) {
            // 매수 최대 후보가 NUMERIC 금액 상한을 넘으면 작은 정수 후보를 탐색할 수 있습니다.
            if (side == OrderSide.BUY && exception.getErrorCode() == ErrorCode.INVALID_SETTLEMENT_AMOUNT) return null;
            throw exception;
        }
    }

    private void validateLevels(List<LimitExecutionPlan.Level> levels, OrderSide side) {
        BigDecimal previousPrice = null;
        Set<Long> ids = new HashSet<>();
        for (LimitExecutionPlan.Level level : levels) {
            if (level == null || level.levelId() == null || level.levelId() <= 0 || !ids.add(level.levelId())
                    || level.price() == null || level.price().signum() <= 0 || level.quantity() == null
                    || level.quantity().signum() < 0 || !isRepresentableAtScale(level.quantity(), 0)
                    || (previousPrice != null && (side == OrderSide.BUY
                        ? previousPrice.compareTo(level.price()) >= 0 : previousPrice.compareTo(level.price()) <= 0))) {
                throw new BusinessException(ErrorCode.INTERNAL_ERROR, "호가 순서/잔량이 올바르지 않습니다");
            }
            previousPrice = level.price();
        }
    }
}
