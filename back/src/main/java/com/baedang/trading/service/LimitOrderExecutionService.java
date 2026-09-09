package com.baedang.trading.service;

import com.baedang.global.error.BusinessException;
import com.baedang.global.error.ErrorCode;
import com.baedang.market.port.ExecutionExchangeRateProvider;
import com.baedang.market.port.ExecutionExchangeRateSnapshot;
import com.baedang.market.port.MarketSessionProvider;
import com.baedang.market.port.MarketSessionStatus;
import com.baedang.stock.entity.MarketCountry;
import com.baedang.stock.entity.Stock;
import com.baedang.stock.repository.StockRepository;
import com.baedang.stock.service.StockTradingStatusService;
import com.baedang.trading.entity.OrderType;
import com.baedang.trading.entity.TradeOrder;
import com.baedang.trading.model.ExecutionRateEvidence;
import com.baedang.trading.model.LimitExecutionAttempt;
import com.baedang.trading.model.LimitExecutionBook;
import com.baedang.trading.model.LimitExecutionOutcome;
import com.baedang.trading.model.OrderMarketContext;
import com.baedang.trading.repository.TradeOrderRepository;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static com.baedang.trading.model.LimitExecutionOutcome.Reason.*;
import static com.baedang.trading.model.LimitExecutionOutcome.deferred;

/** 외부 준비 및 최대 한 번의 재시도. 호출자가 트랜잭션을 열고 진입하는 것을 금지합니다. */
@Service
@Transactional(propagation = Propagation.NEVER)
public class LimitOrderExecutionService {
    private final TradeOrderRepository orders;
    private final StockRepository stocks;
    private final StockTradingStatusService statuses;
    private final MarketSessionProvider sessions;
    private final ExecutionExchangeRateProvider rates;
    private final LimitExecutionBookReader books;
    private final LimitOrderExecutionTransactionService transactions;
    private final OrderPolicy policy;
    private final Clock clock;

    public LimitOrderExecutionService(TradeOrderRepository orders, StockRepository stocks,
            StockTradingStatusService statuses, MarketSessionProvider sessions, ExecutionExchangeRateProvider rates,
            LimitExecutionBookReader books, LimitOrderExecutionTransactionService transactions, OrderPolicy policy, Clock clock) {
        this.orders = orders;
        this.stocks = stocks;
        this.statuses = statuses;
        this.sessions = sessions;
        this.rates = rates;
        this.books = books;
        this.transactions = transactions;
        this.policy = policy;
        this.clock = clock;
    }

    public LimitExecutionOutcome execute(Long orderId) {
        LimitExecutionOutcome result = deferred(INACTIVE);
        Integer expectedExecutionCount = null;
        for (int retry = 0; retry < 2; retry++) {
            try {
                TradeOrder order = orders.findById(orderId).orElse(null);
                if (order == null) return deferred(INACTIVE);
                // 재시도 중 다른 실행이 확정됐다면 새로운 체결로 이어서 실행하지 않습니다.
                if (expectedExecutionCount != null && expectedExecutionCount != order.getExecutionCount()) return deferred(ORDER_CHANGED);
                expectedExecutionCount = order.getExecutionCount();
                result = attempt(orderId, order);
            } catch (PessimisticLockingFailureException | QueryTimeoutException exception) {
                result = deferred(LOCK_BUSY);
            } catch (BusinessException exception) {
                result = switch (exception.getErrorCode()) {
                    case STOCK_STATUS_UNAVAILABLE -> deferred(STATUS_UNAVAILABLE);
                    case MARKET_CLOSED -> deferred(MARKET_CLOSED);
                    case EXCHANGE_RATE_NOT_FOUND, MARKET_CONTEXT_EXPIRED -> deferred(CONTEXT_EXPIRED);
                    default -> throw exception;
                };
            }
            if (result.reason() != BOOK_CHANGED && result.reason() != LOCK_BUSY) return result;
        }
        return result;
    }

    private LimitExecutionOutcome attempt(Long orderId, TradeOrder order) {
        if (order.getOrderType() != OrderType.LIMIT || !order.isActive()) return deferred(INACTIVE);
        if (!clock.instant().isBefore(order.getExpiresAt().toInstant())) return deferred(EXPIRED);
        Stock stock = stocks.findById(order.getStockId()).orElseThrow(() -> new BusinessException(ErrorCode.INTERNAL_ERROR));
        // 외부 준비 지연이 먼저 조회한 세션/상태 근거의 유효 시간을 늘리지 않게 합니다.
        Instant preparationStartedAt = clock.instant();
        MarketSessionStatus session = sessions.currentSession(stock.getMarketCountry(), preparationStartedAt);
        if (!session.open() || session.validUntil() == null || !clock.instant().isBefore(session.validUntil())) return deferred(MARKET_CLOSED);
        LimitExecutionBook book = books.read(stock, order.getSide(), clock.instant()).orElse(null);
        if (book == null) return deferred(NO_BOOK);
        stock = statuses.requireCurrent(stock);
        if (policy.determineStaticRejection(stock) != null) return deferred(NOT_TRADABLE);
        ExecutionRateEvidence evidence = stock.getMarketCountry() == MarketCountry.KR
                ? ExecutionRateEvidence.krw(clock.instant().atOffset(ZoneOffset.UTC))
                : usdEvidence();
        OrderMarketContext context = new OrderMarketContext(stock.getMarketCountry(), session.open(), session.validUntil(), evidence, preparationStartedAt);
        return transactions.execute(new LimitExecutionAttempt(order.getAccountId(), orderId, stock.getStockId(),
                order.getExecutionCount(), book.version(), book.revision(), context));
    }

    private ExecutionRateEvidence usdEvidence() {
        ExecutionExchangeRateSnapshot snapshot = rates.currentUsdKrwSnapshot();
        if (snapshot == null) throw new BusinessException(ErrorCode.EXCHANGE_RATE_NOT_FOUND);
        return ExecutionRateEvidence.from(snapshot);
    }
}
