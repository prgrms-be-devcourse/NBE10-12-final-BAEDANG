package com.baedang.trading.service;

import com.baedang.global.error.BusinessException;
import com.baedang.global.error.ErrorCode;
import com.baedang.market.port.ExecutionExchangeRateProvider;
import com.baedang.market.port.MarketSessionProvider;
import com.baedang.market.port.MarketSessionStatus;
import com.baedang.stock.entity.MarketCountry;
import com.baedang.stock.entity.Stock;
import com.baedang.stock.repository.StockRepository;
import com.baedang.trading.dto.MarketOrderRequest;
import com.baedang.trading.dto.MarketOrderResponse;
import com.baedang.trading.model.MarketOrderCommand;
import com.baedang.trading.model.OrderMarketContext;
import com.baedang.trading.model.MarketOrderResult;
import com.baedang.trading.model.ExecutionRateEvidence;
import com.baedang.trading.model.ClientOrderRetryPolicy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** 입력 검증과 트랜잭션 결과의 HTTP 오류 변환을 담당하는 시장가 주문 진입점입니다. */
@Service
public class MarketOrderService {

    private final OrderPolicy orderPolicy;
    private final MarketOrderTransactionService transactionService;
    private final StockRepository stockRepository;
    private final MarketSessionProvider marketSessionProvider;
    private final ExecutionExchangeRateProvider exchangeRateProvider;
    private final MarketOrderResponseAssembler responseAssembler;
    private final Clock clock;

    public MarketOrderService(
            OrderPolicy orderPolicy,
            MarketOrderTransactionService transactionService,
            StockRepository stockRepository,
            MarketSessionProvider marketSessionProvider,
            ExecutionExchangeRateProvider exchangeRateProvider,
            MarketOrderResponseAssembler responseAssembler,
            Clock clock
    ) {
        this.orderPolicy = orderPolicy;
        this.transactionService = transactionService;
        this.stockRepository = stockRepository;
        this.marketSessionProvider = marketSessionProvider;
        this.exchangeRateProvider = exchangeRateProvider;
        this.responseAssembler = responseAssembler;
        this.clock = clock;
    }

    /** 주문은 다른 업무 트랜잭션에 참여하지 않고 반드시 최상위 유스케이스로 실행합니다. */
    @Transactional(propagation = Propagation.NEVER)
    public MarketOrderResponse place(Long userId, MarketOrderRequest request) {
        if (request == null) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, Map.of("field", "request"));
        }
        var input = orderPolicy.parseInput(
                request.accountId(), request.clientOrderId(), request.symbol(), request.marketCountry(),
                request.side(), request.quantity());

        MarketOrderCommand command = new MarketOrderCommand(input.accountId(), input.clientOrderId(), input.terms());

        Optional<MarketOrderResult> existing = transactionService.findExisting(userId, command);
        if (existing.isPresent()) {
            return unwrap(existing.get());
        }

        OrderMarketContext executionContext = prepareExecutionContext(command);
        MarketOrderResult result = transactionService.execute(userId, command, executionContext);
        return unwrap(result);
    }

    private MarketOrderResponse unwrap(MarketOrderResult result) {
        if (result.rejected()) {
            // 트랜잭션 서비스가 REJECTED 행을 커밋한 뒤 예외로 변환합니다.
            throw new BusinessException(
                    result.rejectionReason(), ClientOrderRetryPolicy.NEW_CLIENT_ORDER_ID.asData());
        }
        return responseAssembler.assemble(result.receipt());
    }

    private OrderMarketContext prepareExecutionContext(MarketOrderCommand command) {
        Stock stock = stockRepository
                .findBySymbolIgnoreCaseAndMarketCountry(
                        command.terms().symbol(), command.terms().marketCountry())
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.STOCK_NOT_FOUND,
                        "symbol=" + command.terms().symbol(),
                        ClientOrderRetryPolicy.SAME_CLIENT_ORDER_ID.asData()));
        ErrorCode staticRejection = orderPolicy.determineStaticRejection(stock);
        if (staticRejection != null) {
            // 외부 조회와 주문 저장 전이므로 조건이 바뀐 뒤 같은 clientOrderId로 재시도할 수 있습니다.
            throw new BusinessException(
                    staticRejection, ClientOrderRetryPolicy.SAME_CLIENT_ORDER_ID.asData());
        }
        Instant sessionLookupAt = clock.instant();
        MarketSessionStatus session;
        ExecutionRateEvidence rateEvidence = null;
        try {
            session = marketSessionProvider.currentSession(stock.getMarketCountry(), sessionLookupAt);
            if (stock.getMarketCountry() == MarketCountry.US) {
                var snapshot = exchangeRateProvider.currentUsdKrwSnapshot();
                if (snapshot == null) {
                    throw new BusinessException(ErrorCode.EXCHANGE_RATE_NOT_FOUND);
                }
                rateEvidence = ExecutionRateEvidence.from(snapshot);
            }
        } catch (BusinessException e) {
            throw withRetryPolicy(e, ClientOrderRetryPolicy.SAME_CLIENT_ORDER_ID);
        }
        Instant checkedAt = clock.instant();
        if (stock.getMarketCountry() == MarketCountry.KR) {
            rateEvidence = ExecutionRateEvidence.krw(checkedAt.atOffset(ZoneOffset.UTC));
        }
        return new OrderMarketContext(
                stock.getMarketCountry(), session.open(), session.validUntil(), rateEvidence, checkedAt);
    }

    private BusinessException withRetryPolicy(
            BusinessException exception,
            ClientOrderRetryPolicy retryPolicy
    ) {
        Map<String, Object> data = new LinkedHashMap<>();
        if (exception.getData() != null) data.putAll(exception.getData());
        data.putAll(retryPolicy.asData());
        if (exception.getDetail() == null) {
            return new BusinessException(exception.getErrorCode(), data);
        }
        return new BusinessException(exception.getErrorCode(), exception.getDetail(), data);
    }
}
