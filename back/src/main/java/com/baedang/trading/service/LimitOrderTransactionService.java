package com.baedang.trading.service;

import com.baedang.global.error.BusinessException;
import com.baedang.global.error.ErrorCode;
import com.baedang.market.entity.QuoteSnapshot;
import com.baedang.market.event.model.ActiveMarketHalt;
import com.baedang.market.event.service.MarketTradingHaltPolicy;
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
import com.baedang.trading.model.LimitOrderAcceptedEvent;
import com.baedang.trading.model.LimitOrderCommand;
import com.baedang.trading.model.OrderClosureResult;
import com.baedang.trading.model.OrderMarketContext;
import com.baedang.trading.model.LimitOrderResult;
import com.baedang.trading.model.OrderTerms;
import com.baedang.trading.repository.HoldingRepository;
import com.baedang.trading.repository.TradeOrderRepository;
import com.baedang.user.entity.Account;
import com.baedang.user.entity.AccountStatus;
import com.baedang.user.repository.AccountRepository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
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
    private final MarketTradingHaltPolicy marketTradingHaltPolicy;
    private final ApplicationEventPublisher events;

    public LimitOrderTransactionService(
            AccountRepository accounts,
            TradeOrderRepository orders,
            HoldingRepository holdings,
            StockRepository stocks,
            QuoteSnapshotRepository quotes,
            OrderPolicy policy,
            Clock clock,
            MarketTradingHaltPolicy marketTradingHaltPolicy,
            ApplicationEventPublisher events
    ) {
        this.accounts = accounts;
        this.orders = orders;
        this.holdings = holdings;
        this.stocks = stocks;
        this.quotes = quotes;
        this.policy = policy;
        this.clock = clock;
        this.marketTradingHaltPolicy = marketTradingHaltPolicy;
        this.events = events;
    }

    @Transactional(readOnly = true)
    public Optional<LimitOrderResult> existing(Long userId, LimitOrderCommand c) {
        Account account = accounts.findByAccountIdAndUserId(c.accountId(), userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND));
        Optional<TradeOrder> existing = orders.findByAccountIdAndClientOrderId(account.getAccountId(), c.clientOrderId());
        if (existing.isPresent()) {
            return Optional.of(replay(existing.get(), c));
        }
        requireActive(account);
        return Optional.empty();
    }

    private LimitOrderResult replay(TradeOrder order, LimitOrderCommand c) {
        Stock stock = stocks.findById(order.getStockId())
                .orElseThrow(() -> new BusinessException(ErrorCode.STOCK_NOT_FOUND));
        OrderTerms t = c.terms();
        if (order.getOrderType() != OrderType.LIMIT
                || order.getSide() != t.side()
                || order.getQuantity().compareTo(t.quantity()) != 0
                || stock.getMarketCountry() != t.marketCountry()
                || !stock.getSymbol().equalsIgnoreCase(t.symbol())
                || !c.currency().equals(order.getRequestedLimitCurrency())
                || order.getRequestedLimitPrice().compareTo(c.requestedPrice()) != 0) {
            throw new BusinessException(ErrorCode.DUPLICATE_ORDER, ClientOrderRetryPolicy.NOT_RETRYABLE.asData());
        }
        OrderDetailResponse response = OrderDetailResponse.from(order, stock);
        if (order.getStatus() == OrderStatus.REJECTED
                && ErrorCode.MARKET_TRADING_HALTED.name().equals(order.getRejectReason())) {
            return LimitOrderResult.rejected(
                    response,
                    marketTradingHaltPolicy.restoreRecordedHalt(order.getMarketEventId(), stock).asErrorData());
        }
        return LimitOrderResult.normal(response);
    }

    /**
     * 시세 준비 실패 시에만 호출합니다. 계좌 잠금 뒤 활성 CB가 확인되거나 동시 요청 결과가 있으면
     * 그 결과를 확정하고, 둘 다 아니면 호출부가 원래 준비 오류를 반환하도록 empty를 돌려줍니다.
     */
    @Transactional
    public Optional<LimitOrderResult> rejectIfHalted(Long userId, LimitOrderCommand c) {
        Account account = accounts.findByAccountIdAndUserIdForUpdate(c.accountId(), userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND));
        Optional<TradeOrder> existing = orders.findByAccountIdAndClientOrderId(
                account.getAccountId(), c.clientOrderId());
        if (existing.isPresent()) {
            return Optional.of(replay(existing.get(), c));
        }
        requireActive(account);
        OrderTerms terms = c.terms();
        Stock stock = stocks.findBySymbolIgnoreCaseAndMarketCountry(terms.symbol(), terms.marketCountry())
                .orElseThrow(() -> new BusinessException(ErrorCode.STOCK_NOT_FOUND));
        Instant now = clock.instant();
        Optional<ActiveMarketHalt> halt = marketTradingHaltPolicy.activeFor(stock, now);
        if (halt.isEmpty()) {
            return Optional.empty();
        }
        OffsetDateTime at = now.truncatedTo(ChronoUnit.MICROS).atOffset(ZoneOffset.UTC);
        return Optional.of(rejectByHalt(
                account, stock, c, c.requestedPrice(), BigDecimal.ONE, halt.get(), at));
    }

    @Transactional
    public LimitOrderResult accept(
            Long userId,
            LimitOrderCommand c,
            OrderMarketContext context,
            LimitOrderPricing.Price price
    ) {
        Account account = accounts.findByAccountIdAndUserIdForUpdate(c.accountId(), userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND));
        Optional<TradeOrder> existing = orders.findByAccountIdAndClientOrderId(account.getAccountId(), c.clientOrderId());
        if (existing.isPresent()) {
            return replay(existing.get(), c);
        }
        requireActive(account);
        Instant checkedAt = clock.instant();
        OrderTerms t = c.terms();
        Stock stock = stocks.findBySymbolIgnoreCaseAndMarketCountry(t.symbol(), t.marketCountry())
                .orElseThrow(() -> new BusinessException(ErrorCode.STOCK_NOT_FOUND));

        // CB 판정은 접수 트랜잭션 안에서만 한다. context 신선도·시세 검증보다 앞에 두어, 락 대기 중
        // 시작된 CB가 만료된 context나 stale quote로 가려지지 않게 한다.
        OffsetDateTime at = checkedAt.truncatedTo(ChronoUnit.MICROS).atOffset(ZoneOffset.UTC);
        Optional<ActiveMarketHalt> halt = marketTradingHaltPolicy.activeFor(stock, checkedAt);
        if (halt.isPresent()) {
            return rejectByHalt(
                    account, stock, c, price.limitPrice(), context.executionRate(), halt.get(), at);
        }

        policy.validateExecutionContextFresh(context, checkedAt);
        QuoteSnapshot quote = quotes.findById(stock.getStockId()).orElseThrow(() ->
                new BusinessException(ErrorCode.QUOTE_NOT_FOUND, ClientOrderRetryPolicy.SAME_CLIENT_ORDER_ID.asData()));
        if (!policy.hasValidCurrencyForMarket(stock, quote)) {
            throw new BusinessException(ErrorCode.QUOTE_CURRENCY_MISMATCH, ClientOrderRetryPolicy.SAME_CLIENT_ORDER_ID.asData());
        }
        Holding holding = t.side() == OrderSide.SELL
                ? holdings.findByAccountIdAndStockIdForUpdate(account.getAccountId(), stock.getStockId()).orElse(null)
                : null;
        // 매도 보유 행 잠금까지 기다린 뒤 세션·시세 검증 시각을 확정합니다.
        Instant now = clock.instant();
        at = now.truncatedTo(ChronoUnit.MICROS).atOffset(ZoneOffset.UTC);
        policy.validateExecutionContextFresh(context, now);
        ErrorCode reason = policy.determineStaticRejection(stock);
        if (reason == null && !context.isMarketOpenAt(now)) {
            reason = ErrorCode.MARKET_CLOSED;
        }
        if (reason == null) {
            reason = policy.validateQuoteTime(quote, now);
        }
        if (reason == null) {
            reason = policy.validateTradingPrice(stock, quote, price.limitPrice(), now, true);
            if (reason == ErrorCode.PRICE_LIMIT_UNAVAILABLE || reason == ErrorCode.QUOTE_OUT_OF_PRICE_LIMIT) {
                throw new BusinessException(reason, ClientOrderRetryPolicy.SAME_CLIENT_ORDER_ID.asData());
            }
        }
        if (reason == null && t.side() == OrderSide.BUY && account.availableCash().compareTo(price.reserve()) < 0) {
            reason = ErrorCode.INSUFFICIENT_CASH;
        }
        if (reason == null && t.side() == OrderSide.SELL && (holding == null || holding.availableQuantity().compareTo(t.quantity()) < 0)) {
            reason = ErrorCode.INSUFFICIENT_QUANTITY;
        }
        if (reason != null) {
            return LimitOrderResult.normal(OrderDetailResponse.from(orders.save(TradeOrder.rejectedLimitOrder(
                    account.getAccountId(), stock.getStockId(), c.clientOrderId(), t.side(), t.quantity(), price.limitPrice(),
                    c.requestedPrice(), c.currency(), context.executionRate(), reason.name(), at)), stock));
        }
        if (t.side() == OrderSide.BUY) {
            account.reserveCash(price.reserve());
        } else {
            holding.reserveQuantity(t.quantity(), at);
        }
        TradeOrder accepted = orders.save(TradeOrder.pendingLimitOrder(
                account.getAccountId(), stock.getStockId(), c.clientOrderId(), t.side(), t.quantity(),
                price.limitPrice(), price.reserve(), at, context.marketOpenUntil().atOffset(ZoneOffset.UTC),
                c.requestedPrice(), c.currency(), context.executionRate()));
        events.publishEvent(new LimitOrderAcceptedEvent(stock.getStockId(), t.side(), accepted.getOrderId(),
                accepted.getLimitPrice(), accepted.getOrderedAt()));
        return LimitOrderResult.normal(OrderDetailResponse.from(accepted, stock));
    }

    private LimitOrderResult rejectByHalt(
            Account account,
            Stock stock,
            LimitOrderCommand command,
            BigDecimal limitPrice,
            BigDecimal executionRate,
            ActiveMarketHalt halt,
            OffsetDateTime at
    ) {
        OrderTerms terms = command.terms();
        TradeOrder rejected = orders.save(TradeOrder.rejectedLimitOrderByHalt(
                account.getAccountId(), stock.getStockId(), command.clientOrderId(),
                terms.side(), terms.quantity(), limitPrice, command.requestedPrice(),
                command.currency(), executionRate, halt.eventId(), at));
        return LimitOrderResult.rejected(
                OrderDetailResponse.from(rejected, stock), halt.asErrorData());
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
