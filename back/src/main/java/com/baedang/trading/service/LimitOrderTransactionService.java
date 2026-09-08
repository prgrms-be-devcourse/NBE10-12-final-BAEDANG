package com.baedang.trading.service;

import com.baedang.global.error.BusinessException;
import com.baedang.global.error.ErrorCode;
import com.baedang.market.repository.QuoteSnapshotRepository;
import com.baedang.stock.entity.Stock;
import com.baedang.stock.repository.StockRepository;
import com.baedang.trading.dto.OrderDetailResponse;
import com.baedang.trading.entity.Holding;
import com.baedang.trading.entity.OrderSide;
import com.baedang.trading.entity.OrderStatus;
import com.baedang.trading.entity.OrderType;
import com.baedang.trading.entity.TradeOrder;
import com.baedang.trading.model.ClientOrderRetryPolicy;
import com.baedang.trading.model.LimitOrderCommand;
import com.baedang.trading.model.OrderMarketContext;
import com.baedang.trading.model.OrderClosureResult;
import com.baedang.trading.repository.HoldingRepository;
import com.baedang.trading.repository.TradeOrderRepository;
import com.baedang.user.entity.Account;
import com.baedang.user.entity.AccountStatus;
import com.baedang.user.repository.AccountRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

/** account → order → holding 순서로 잠그며 외부 API는 호출하지 않습니다. */
@Service
public class LimitOrderTransactionService {

    @PersistenceContext
    private EntityManager entityManager;

    private final AccountRepository accounts;
    private final TradeOrderRepository orders;
    private final HoldingRepository holdings;
    private final StockRepository stocks;
    private final QuoteSnapshotRepository quotes;
    private final OrderPolicy policy;
    private final Clock clock;

    public LimitOrderTransactionService(
            AccountRepository accounts,
            TradeOrderRepository orders,
            HoldingRepository holdings,
            StockRepository stocks,
            QuoteSnapshotRepository quotes,
            OrderPolicy policy,
            Clock clock
    ) {
        this.accounts = accounts;
        this.orders = orders;
        this.holdings = holdings;
        this.stocks = stocks;
        this.quotes = quotes;
        this.policy = policy;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Optional<OrderDetailResponse> existing(Long userId, LimitOrderCommand c) {
        Account account = accounts.findByAccountIdAndUserId(c.accountId(), userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND));
        var existing = orders.findByAccountIdAndClientOrderId(account.getAccountId(), c.clientOrderId());
        if (existing.isPresent()) {
            return Optional.of(replay(existing.get(), c));
        }
        requireActive(account);
        return Optional.empty();
    }

    private OrderDetailResponse replay(TradeOrder order, LimitOrderCommand c) {
        Stock stock = stocks.findById(order.getStockId())
                .orElseThrow(() -> new BusinessException(ErrorCode.STOCK_NOT_FOUND));
        var t = c.terms();
        if (order.getOrderType() != OrderType.LIMIT
                || order.getSide() != t.side()
                || order.getQuantity().compareTo(t.quantity()) != 0
                || stock.getMarketCountry() != t.marketCountry()
                || !stock.getSymbol().equalsIgnoreCase(t.symbol())
                || !c.currency().equals(order.getRequestedLimitCurrency())
                || order.getRequestedLimitPrice().compareTo(c.requestedPrice()) != 0) {
            throw new BusinessException(ErrorCode.DUPLICATE_ORDER, ClientOrderRetryPolicy.NOT_RETRYABLE.asData());
        }
        return OrderDetailResponse.from(order, stock);
    }

    @Transactional
    public OrderDetailResponse accept(
            Long userId,
            LimitOrderCommand c,
            OrderMarketContext context,
            LimitOrderPricing.Price price
    ) {
        Account account = accounts.findByAccountIdAndUserIdForUpdate(c.accountId(), userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND));
        var existing = orders.findByAccountIdAndClientOrderId(account.getAccountId(), c.clientOrderId());
        if (existing.isPresent()) {
            return replay(existing.get(), c);
        }
        requireActive(account);
        Instant now = clock.instant();
        policy.validateExecutionContextFresh(context, now);
        var t = c.terms();
        Stock stock = stocks.findBySymbolIgnoreCaseAndMarketCountry(t.symbol(), t.marketCountry())
                .orElseThrow(() -> new BusinessException(ErrorCode.STOCK_NOT_FOUND));
        var quote = quotes.findById(stock.getStockId()).orElseThrow(() ->
                new BusinessException(ErrorCode.QUOTE_NOT_FOUND, ClientOrderRetryPolicy.SAME_CLIENT_ORDER_ID.asData()));
        if (!policy.hasValidCurrencyForMarket(stock, quote)) {
            throw new BusinessException(ErrorCode.QUOTE_CURRENCY_MISMATCH, ClientOrderRetryPolicy.SAME_CLIENT_ORDER_ID.asData());
        }
        Holding holding = t.side() == OrderSide.SELL
                ? holdings.findByAccountIdAndStockIdForUpdate(account.getAccountId(), stock.getStockId()).orElse(null)
                : null;
        ErrorCode reason = policy.determineStaticRejection(stock);
        if (reason == null && !context.isMarketOpenAt(now)) {
            reason = ErrorCode.MARKET_CLOSED;
        }
        if (reason == null) {
            reason = policy.validateQuoteTime(quote, now);
        }
        if (reason == null && t.side() == OrderSide.BUY && account.availableCash().compareTo(price.reserve()) < 0) {
            reason = ErrorCode.INSUFFICIENT_CASH;
        }
        if (reason == null && t.side() == OrderSide.SELL && (holding == null || holding.availableQuantity().compareTo(t.quantity()) < 0)) {
            reason = ErrorCode.INSUFFICIENT_QUANTITY;
        }
        OffsetDateTime at = now.truncatedTo(ChronoUnit.MICROS).atOffset(ZoneOffset.UTC);
        if (reason != null) {
            return OrderDetailResponse.from(orders.save(TradeOrder.rejectedLimitOrder(
                    account.getAccountId(), stock.getStockId(), c.clientOrderId(), t.side(), t.quantity(), price.limitPrice(),
                    c.requestedPrice(), c.currency(), context.executionRate(), reason.name(), at)), stock);
        }
        if (t.side() == OrderSide.BUY) {
            account.reserveCash(price.reserve());
        } else {
            holding.reserveQuantity(t.quantity(), at);
        }
        return OrderDetailResponse.from(orders.save(TradeOrder.pendingLimitOrder(
                account.getAccountId(), stock.getStockId(), c.clientOrderId(), t.side(), t.quantity(),
                price.limitPrice(), price.reserve(), at, context.marketOpenUntil().atOffset(ZoneOffset.UTC),
                c.requestedPrice(), c.currency(), context.executionRate())), stock);
    }

    /** 만료 경합 결과를 예외가 아닌 값으로 반환하여 종료 및 동결 해제를 먼저 커밋합니다. */
    @Transactional
    public OrderDetailResponse close(Long userId, Long accountId, Long orderId, boolean expiration) {
        if (expiration) {
            // PostgreSQL 트랜잭션 로컬 설정: 계좌/주문/보유 잠금 대기를 제한하며 종료 시 자동 해제됩니다.
            // 첫 행 잠금은 여전히 account입니다. 한 주문의 대기가 뒤의 만료 주문을 막지 않게 합니다.
            entityManager.createNativeQuery("SET LOCAL lock_timeout = '2s'").executeUpdate();
        }
        Account account = accounts.findByAccountIdAndUserIdForUpdate(accountId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND));
        TradeOrder order = orders.findForUpdate(orderId)
                .filter(o -> accountId.equals(o.getAccountId()))
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND));
        OffsetDateTime now = clock.instant().truncatedTo(ChronoUnit.MICROS).atOffset(ZoneOffset.UTC);
        if (order.getOrderType() != OrderType.LIMIT || !order.isActive()) {
            return response(order);
        }
        boolean due = !now.isBefore(order.getExpiresAt());
        if (expiration && !due) {
            return response(order);
        }
        Holding holding = order.getSide() == OrderSide.SELL
                ? holdings.findByAccountIdAndStockIdForUpdate(accountId, order.getStockId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.INTERNAL_ERROR))
                : null;
        OrderClosureResult release = due ? order.expire(now) : order.cancel(now);
        if (release.releasedCash().signum() > 0) {
            account.releaseCash(release.releasedCash());
        }
        if (release.releasedQuantity().signum() > 0) {
            holding.releaseQuantity(release.releasedQuantity(), now);
        }
        return response(order);
    }

    private OrderDetailResponse response(TradeOrder order) {
        Stock stock = stocks.findById(order.getStockId()).orElseThrow(() -> new BusinessException(ErrorCode.INTERNAL_ERROR));
        return OrderDetailResponse.from(order, stock);
    }

    private static void requireActive(Account account) {
        if (account.getStatus() != AccountStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.ACCOUNT_ROUND_CHANGED, ClientOrderRetryPolicy.NOT_RETRYABLE.asData());
        }
    }
}
