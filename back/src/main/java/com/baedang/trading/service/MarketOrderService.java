package com.baedang.trading.service;

import com.baedang.global.error.BusinessException;
import com.baedang.global.error.ErrorCode;
import com.baedang.trading.dto.OrderResponse;
import com.baedang.trading.dto.PlaceOrderRequest;
import com.baedang.trading.model.MarketOrderCommand;
import com.baedang.trading.model.MarketOrderResult;
import org.springframework.stereotype.Service;

/** 입력 검증과 트랜잭션 결과의 HTTP 오류 변환을 담당하는 시장가 주문 진입점입니다. */
@Service
public class MarketOrderService {

    private final MarketOrderPolicy marketOrderPolicy;
    private final MarketOrderTransactionService transactionService;

    public MarketOrderService(
            MarketOrderPolicy marketOrderPolicy,
            MarketOrderTransactionService transactionService
    ) {
        this.marketOrderPolicy = marketOrderPolicy;
        this.transactionService = transactionService;
    }

    public OrderResponse place(Long userId, PlaceOrderRequest request) {
        if (request == null) throw new BusinessException(ErrorCode.INVALID_INPUT);
        MarketOrderCommand command = marketOrderPolicy.parseCommand(
                request.clientOrderId(), request.symbol(), request.side(), request.quantity());
        MarketOrderResult result = transactionService.execute(userId, command);
        if (result.rejected()) {
            // 트랜잭션 서비스가 REJECTED 행을 커밋한 뒤 예외로 변환합니다.
            throw new BusinessException(result.rejectionReason());
        }
        return result.response();
    }
}
