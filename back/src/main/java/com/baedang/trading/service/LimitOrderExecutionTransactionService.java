package com.baedang.trading.service;

import com.baedang.global.error.BusinessException;
import com.baedang.global.error.ErrorCode;
import com.baedang.orderbook.entity.OrderBookLevel;
import com.baedang.orderbook.entity.OrderBookSide;
import com.baedang.orderbook.entity.OrderBookVersion;
import com.baedang.orderbook.model.LockedOrderBook;
import com.baedang.orderbook.port.OrderBookExecutionStore;
import com.baedang.stock.entity.Stock;
import com.baedang.stock.repository.StockRepository;
import com.baedang.trading.entity.Holding;
import com.baedang.trading.entity.OrderSide;
import com.baedang.trading.entity.OrderType;
import com.baedang.trading.entity.TradeExecution;
import com.baedang.trading.entity.TradeOrder;
import com.baedang.trading.model.CumulativeSettlementState;
import com.baedang.trading.model.LimitExecutionAttempt;
import com.baedang.trading.model.LimitExecutionOutcome;
import com.baedang.trading.model.LimitExecutionPlan;
import com.baedang.trading.repository.HoldingRepository;
import com.baedang.trading.repository.TradeExecutionRepository;
import com.baedang.trading.repository.TradeOrderRepository;
import com.baedang.user.entity.Account;
import com.baedang.user.entity.AccountStatus;
import com.baedang.user.repository.AccountRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.baedang.trading.model.LimitExecutionOutcome.Reason.*;
import static com.baedang.trading.model.LimitExecutionOutcome.deferred;

@Service
public class LimitOrderExecutionTransactionService {
    private final AccountRepository accounts;
    private final TradeOrderRepository orders;
    private final OrderBookExecutionStore books;
    private final HoldingRepository holdings;
    private final StockRepository stocks;
    private final TradeExecutionRepository executions;
    private final LedgerService ledger;
    private final LimitOrderExecutionPlanner planner;
    private final LimitExecutionBookReader bookReader;
    private final OrderPolicy policy;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final long lockTimeoutMillis;

    public LimitOrderExecutionTransactionService(AccountRepository accounts, TradeOrderRepository orders,
            OrderBookExecutionStore books, HoldingRepository holdings, StockRepository stocks,
            TradeExecutionRepository executions, LedgerService ledger, LimitOrderExecutionPlanner planner,
            LimitExecutionBookReader bookReader, OrderPolicy policy, JdbcTemplate jdbc, Clock clock,
            @Value("${trading.limit-execution.lock-timeout:2s}") Duration lockTimeout) {
        if (lockTimeout == null || lockTimeout.toMillis() < 1 || lockTimeout.toMillis() > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("체결 락 대기 제한은 양수 밀리초 범위여야 합니다");
        }
        this.lockTimeoutMillis = lockTimeout.toMillis();
        this.accounts = accounts;
        this.orders = orders;
        this.books = books;
        this.holdings = holdings;
        this.stocks = stocks;
        this.executions = executions;
        this.ledger = ledger;
        this.planner = planner;
        this.bookReader = bookReader;
        this.policy = policy;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** 외부 조회 없이 계좌 → 주문 → 호가 버전 → 레벨 → 보유 수량을 잠그고 전체 결과를 원자적으로 저장합니다. */
    @Transactional
    public LimitExecutionOutcome execute(LimitExecutionAttempt attempt) {
        // SET LOCAL은 트랜잭션 종료 시 복구됩니다. 행 조회보다 먼저 제한을 설정합니다.
        jdbc.execute("SET LOCAL lock_timeout = '" + lockTimeoutMillis + "ms'");
        Account account = accounts.findForUpdate(attempt.accountId()).orElseThrow(() -> internal("계좌 누락"));
        TradeOrder order = orders.findForUpdate(attempt.orderId()).orElseThrow(() -> internal("주문 누락"));
        if (!order.getAccountId().equals(account.getAccountId()) || !order.getStockId().equals(attempt.stockId())) {
            throw internal("실행 대상 불일치");
        }
        if (account.getStatus() != AccountStatus.ACTIVE || order.getOrderType() != OrderType.LIMIT || !order.isActive()) {
            return deferred(INACTIVE);
        }
        if (order.getExecutionCount() != attempt.executionCount()) return deferred(ORDER_CHANGED);
        if (!clock.instant().isBefore(order.getExpiresAt().toInstant())) return deferred(EXPIRED);
        LockedOrderBook book = books.lockForExecution(order.getStockId(), attempt.bookVersion(), attempt.revision(),
                order.getSide() == OrderSide.BUY ? OrderBookSide.ASK : OrderBookSide.BID).orElse(null);
        if (book == null) return deferred(BOOK_CHANGED);
        Holding holding = holdings.findByAccountIdAndStockIdForUpdate(account.getAccountId(), order.getStockId()).orElse(null);
        Stock stock = stocks.findById(order.getStockId()).orElseThrow(() -> internal("종목 누락"));
        Instant now = clock.instant();
        if (!now.isBefore(order.getExpiresAt().toInstant())) return deferred(EXPIRED);
        policy.validateExecutionContextFresh(attempt.context(), now);
        if (stock.getMarketCountry() != attempt.context().marketCountry()) throw internal("시장 불일치");
        if (!attempt.context().isMarketOpenAt(now)) return deferred(MARKET_CLOSED);
        if (policy.determineStaticRejection(stock) != null) return deferred(NOT_TRADABLE);
        OrderBookVersion version = book.version();
        if (!bookReader.isFresh(stock, version.getCurrency(), version.getQuoteAt().toInstant(),
                version.getGeneratedAt().toInstant(), now)) return deferred(STALE_BOOK);
        CumulativeSettlementState previous = executions.summarizeByOrderId(order.getOrderId());
        if (previous.quantity().compareTo(order.getFilledQuantity()) != 0
                || previous.grossAmountKrw().compareTo(order.getGrossAmount()) != 0
                || previous.feeKrw().compareTo(order.getFee()) != 0 || previous.taxKrw().compareTo(order.getTax()) != 0
                || executions.countByOrderId(order.getOrderId()) != order.getExecutionCount()) throw internal("누적 체결 불일치");
        BigDecimal expectedNet = order.getSide() == OrderSide.BUY
                ? previous.grossAmountKrw().add(previous.feeKrw())
                : previous.grossAmountKrw().subtract(previous.feeKrw()).subtract(previous.taxKrw());
        if (expectedNet.compareTo(order.getNetAmount()) != 0) throw internal("누적 순정산액 불일치");
        if (order.getSide() == OrderSide.BUY && account.getLockedCash().compareTo(order.getReservedCash()) < 0) {
            throw internal("매수 동결액 불일치");
        }
        if (order.getSide() == OrderSide.SELL && (holding == null
                || holding.getLockedQuantity().compareTo(order.activeRemainingQuantity()) < 0)) throw internal("매도 동결량 불일치");
        LimitExecutionPlan plan = planner.plan(stock.getMarketCountry(), order.getSide(), order.getLimitPrice(),
                order.activeRemainingQuantity(), order.getReservedCash(), attempt.context().executionRate(), previous,
                book.levels().stream().map(l -> new LimitExecutionPlan.Level(l.getLevelId(), l.getPrice(), l.getRemainingQuantity())).toList());
        Map<Long, OrderBookLevel> levels = book.levels().stream().collect(Collectors.toMap(OrderBookLevel::getLevelId, Function.identity()));
        OffsetDateTime executedAt = now.truncatedTo(ChronoUnit.MICROS).atOffset(ZoneOffset.UTC);
        for (LimitExecutionPlan.Fill fill : plan.fills()) {
            TradeExecution execution = executions.save(TradeExecution.limit(order, stock.getMarketCountry(), UUID.randomUUID(),
                    order.getExecutionCount() + 1, fill.quantity(), fill.price(), attempt.context().executionRateEvidence(),
                    fill.amounts(), version.getQuoteAt(), now.atOffset(ZoneOffset.UTC), fill.levelId()));
            if (order.getSide() == OrderSide.BUY) {
                account.settleReservedBuy(fill.amounts().netAmountKrw());
                if (fill.releasedCash().signum() > 0) account.releaseCash(fill.releasedCash());
                if (holding == null) {
                    holding = holdings.save(Holding.firstBuy(account.getAccountId(), stock.getStockId(), fill.quantity(),
                            fill.amounts().grossAmountUsd(), fill.amounts().unroundedGrossAmountKrw(), executedAt));
                } else {
                    holding.addBuy(fill.quantity(), fill.amounts().grossAmountUsd(), fill.amounts().unroundedGrossAmountKrw(), executedAt);
                }
                ledger.recordBuy(order, execution, account.getCashBalance(), stock);
            } else {
                holding.settleReservedSell(fill.quantity(), executedAt);
                account.creditLimitSell(fill.amounts().netAmountKrw());
                ledger.recordSell(order, execution, account.getCashBalance(), stock);
            }
            order.applyExecution(execution, fill.reservedCashAfter());
            levels.get(fill.levelId()).consume(fill.quantity());
        }
        if (!plan.fills().isEmpty()) {
            version.advanceRevision();
            return new LimitExecutionOutcome(plan.fills().size(), EXECUTED);
        }
        return deferred(switch (plan.reason()) {
            case INSUFFICIENT_RESERVED_CASH -> RESERVED_CASH;
            case NON_POSITIVE_SETTLEMENT -> NON_POSITIVE_SETTLEMENT;
            default -> PRICE_OR_LIQUIDITY;
        });
    }

    private BusinessException internal(String detail) { return new BusinessException(ErrorCode.INTERNAL_ERROR, detail); }
}
