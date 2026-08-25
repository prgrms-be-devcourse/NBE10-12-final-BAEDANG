package com.baedang.trading.service;

import com.baedang.global.error.BusinessException;
import com.baedang.global.error.ErrorCode;
import com.baedang.trading.dto.PlaceOrderRequest;
import com.baedang.trading.model.MarketOrderCommand;
import com.baedang.trading.model.MarketOrderResult;
import com.baedang.trading.model.OrderTerms;
import com.baedang.trading.entity.OrderSide;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MarketOrderServiceTest {

    @Mock MarketOrderPolicy marketOrderPolicy;
    @Mock MarketOrderTransactionService transactionService;

    @Test
    void REJECTED가_커밋된_뒤_업무_예외로_변환한다() {
        PlaceOrderRequest request = new PlaceOrderRequest(
                UUID.randomUUID().toString(), "005930", "BUY", "10");
        MarketOrderCommand command = new MarketOrderCommand(
                UUID.fromString(request.clientOrderId()),
                new OrderTerms("005930", OrderSide.BUY, new BigDecimal("10")));
        when(marketOrderPolicy.parseCommand(
                request.clientOrderId(), request.symbol(), request.side(), request.quantity()))
                .thenReturn(command);
        when(transactionService.execute(1L, command))
                .thenReturn(MarketOrderResult.rejected(ErrorCode.INSUFFICIENT_CASH));

        MarketOrderService service = new MarketOrderService(marketOrderPolicy, transactionService);

        assertThatThrownBy(() -> service.place(1L, request))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INSUFFICIENT_CASH);
    }
}
