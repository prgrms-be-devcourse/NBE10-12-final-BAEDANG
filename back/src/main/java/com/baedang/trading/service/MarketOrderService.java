package com.baedang.trading.service;

import com.baedang.global.error.BusinessException;
import com.baedang.global.error.ErrorCode;
import com.baedang.market.port.ExecutionExchangeRateProvider;
import com.baedang.market.port.MarketSessionProvider;
import com.baedang.stock.entity.Stock;
import com.baedang.stock.repository.StockRepository;
import com.baedang.trading.dto.OrderResponse;
import com.baedang.trading.dto.PlaceOrderRequest;
import com.baedang.trading.model.MarketOrderCommand;
import com.baedang.trading.model.MarketOrderExecutionContext;
import com.baedang.trading.model.MarketOrderResult;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;

/** 입력 검증과 트랜잭션 결과의 HTTP 오류 변환을 담당하는 시장가 주문 진입점입니다. */
@Service
public class MarketOrderService {

    private final MarketOrderPolicy marketOrderPolicy;
    private final MarketOrderTransactionService transactionService;
    private final StockRepository stockRepository;
    private final MarketSessionProvider marketSessionProvider;
    private final ExecutionExchangeRateProvider exchangeRateProvider;
    private final Clock clock;

    public MarketOrderService(
            MarketOrderPolicy marketOrderPolicy,
            MarketOrderTransactionService transactionService,
            StockRepository stockRepository,
            MarketSessionProvider marketSessionProvider,
            ExecutionExchangeRateProvider exchangeRateProvider,
            Clock clock
    ) {
        this.marketOrderPolicy = marketOrderPolicy;
        this.transactionService = transactionService;
        this.stockRepository = stockRepository;
        this.marketSessionProvider = marketSessionProvider;
        this.exchangeRateProvider = exchangeRateProvider;
        this.clock = clock;
    }

    public OrderResponse place(Long userId, PlaceOrderRequest request) {
        if (request == null) throw new BusinessException(ErrorCode.INVALID_INPUT);
        MarketOrderCommand command = marketOrderPolicy.parseCommand(
                request.clientOrderId(), request.symbol(), request.side(), request.quantity());
        MarketOrderExecutionContext executionContext = prepareExecutionContext(command);
        MarketOrderResult result = transactionService.execute(userId, command, executionContext);
        if (result.rejected()) {
            // 트랜잭션 서비스가 REJECTED 행을 커밋한 뒤 예외로 변환합니다.
            throw new BusinessException(result.rejectionReason());
        }
        return result.response();
    }

    private MarketOrderExecutionContext prepareExecutionContext(MarketOrderCommand command) {
        Stock stock = stockRepository
                .findBySymbolIgnoreCase(command.terms().symbol())
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.STOCK_NOT_FOUND, "symbol=" + command.terms().symbol()));
        Instant checkedAt = clock.instant();
        boolean marketOpen = marketSessionProvider.isOpen(stock.getMarketCountry(), checkedAt);
        BigDecimal usdKrwRate = exchangeRateProvider.currentUsdKrwRate();
        if (usdKrwRate == null || usdKrwRate.signum() <= 0) {
            throw new BusinessException(ErrorCode.EXCHANGE_RATE_NOT_FOUND);
        }
        return new MarketOrderExecutionContext(
                stock.getMarketCountry(), marketOpen, usdKrwRate, checkedAt);
    }
}
