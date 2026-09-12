package com.baedang.trading.service;

import com.baedang.global.error.BusinessException;
import com.baedang.global.error.ErrorCode;
import com.baedang.market.entity.QuoteSnapshot;
import com.baedang.market.event.model.ActiveMarketHalt;
import com.baedang.market.event.repository.MarketEventRepository;
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
import com.baedang.trading.model.LimitOrderCommand;
import com.baedang.trading.model.LimitOrderAcceptedEvent;
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
import org.springframework.stereotype.Service;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Map;
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
    private final MarketEventRepository marketEventRepository;
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
            MarketEventRepository marketEventRepository,
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
        this.marketEventRepository = marketEventRepository;
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
            return LimitOrderResult.rejected(response, haltDataOf(order, stock));
        }
        return LimitOrderResult.normal(response);
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
        Instant now = clock.instant();
        OrderTerms t = c.terms();
        Stock stock = stocks.findBySymbolIgnoreCaseAndMarketCountry(t.symbol(), t.marketCountry())
                .orElseThrow(() -> new BusinessException(ErrorCode.STOCK_NOT_FOUND));

        // CB 판정은 접수 트랜잭션 안에서만 한다. context 신선도·시세 검증보다 앞에 두어, 락 대기 중
        // 시작된 CB가 만료된 context나 stale quote로 가려지지 않게 한다.
        OffsetDateTime at = now.truncatedTo(ChronoUnit.MICROS).atOffset(ZoneOffset.UTC);
        Optional<ActiveMarketHalt> halt = marketTradingHaltPolicy.activeFor(stock, now);
        if (halt.isPresent()) {
            // 동결하지 않는다. 판정 이벤트를 FK로 고정해 같은 clientOrderId 재요청이 최초 데이터를 재생한다.
            TradeOrder rejected = orders.save(TradeOrder.rejectedLimitOrderByHalt(
                    account.getAccountId(), stock.getStockId(), c.clientOrderId(), t.side(), t.quantity(),
                    price.limitPrice(), c.requestedPrice(), c.currency(), context.executionRate(),
                    halt.get().eventId(), at));
            return LimitOrderResult.rejected(
                    OrderDetailResponse.from(rejected, stock), halt.get().asErrorData());
        }

        policy.validateExecutionContextFresh(context, now);
        QuoteSnapshot quote = quotes.findById(stock.getStockId()).orElseThrow(() ->
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

    /**
     * CB 거절 주문의 오류 데이터를 저장된 이벤트 ID로 복원한다.
     *
     * <p>FK는 행 존재만 보장하므로, 주문 종목의 시장과 이벤트 시장이 같은지도 확인한다. 없거나,
     * CB가 아니거나, 시장이 어긋나면 재생할 수 없으므로 {@code INTERNAL_ERROR}로 끊는다.
     */
    private Map<String, Object> haltDataOf(TradeOrder order, Stock stock) {
        Long eventId = order.getMarketEventId();
        if (eventId == null) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR,
                    "CB rejection without market_event_id: orderId=" + order.getOrderId());
        }
        com.baedang.market.event.entity.KrMarket expected =
                com.baedang.market.event.entity.KrMarket.fromStockMarket(stock.getMarket())
                        .orElseThrow(() -> new BusinessException(ErrorCode.INTERNAL_ERROR,
                                "CB rejection on unsupported market: orderId=" + order.getOrderId()
                                        + ", market=" + stock.getMarket()));
        return marketEventRepository.findById(eventId)
                .filter(event -> event.getEventType() == com.baedang.market.event.entity.MarketEventType.CIRCUIT_BREAKER)
                .filter(event -> event.getMarket() == expected)
                .map(ActiveMarketHalt::from)
                .map(ActiveMarketHalt::asErrorData)
                .orElseThrow(() -> new BusinessException(ErrorCode.INTERNAL_ERROR,
                        "market_event mismatch for CB rejection: orderId=" + order.getOrderId()
                                + ", marketEventId=" + eventId + ", expectedMarket=" + expected));
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
