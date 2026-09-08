package com.baedang.trading.service;

import com.baedang.global.error.BusinessException;
import com.baedang.global.error.ErrorCode;
import com.baedang.market.port.ExecutionExchangeRateProvider;
import com.baedang.market.port.MarketSessionProvider;
import com.baedang.stock.entity.MarketCountry;
import com.baedang.stock.repository.StockRepository;
import com.baedang.trading.dto.LimitOrderQuoteResponse;
import com.baedang.trading.dto.LimitOrderRequest;
import com.baedang.trading.dto.OrderDetailResponse;
import com.baedang.trading.entity.OrderSide;
import com.baedang.trading.entity.OrderStatus;
import com.baedang.trading.model.ClientOrderRetryPolicy;
import com.baedang.trading.model.ExecutionRateEvidence;
import com.baedang.trading.model.LimitOrderCommand;
import com.baedang.trading.model.OrderMarketContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;

import static com.baedang.global.formatter.FinancialDecimalFormatter.krw;
import static com.baedang.global.formatter.FinancialDecimalFormatter.plain;
import static com.baedang.global.formatter.FinancialDecimalFormatter.currency;
import static com.baedang.global.formatter.FinancialDecimalFormatter.rate;

@Service
@Transactional(propagation = Propagation.NEVER)
public class LimitOrderService {

    private final OrderPolicy policy;
    private final LimitOrderTransactionService transactions;
    private final LimitOrderPricing pricing;
    private final MarketSessionProvider sessions;
    private final ExecutionExchangeRateProvider rates;
    private final StockRepository stocks;
    private final OrderReadService reads;
    private final OrderQuoteQueryService quoteReads;
    private final Clock clock;

    public LimitOrderService(
            OrderPolicy policy,
            LimitOrderTransactionService transactions,
            LimitOrderPricing pricing,
            MarketSessionProvider sessions,
            ExecutionExchangeRateProvider rates,
            StockRepository stocks,
            OrderReadService reads,
            OrderQuoteQueryService quoteReads,
            Clock clock
    ) {
        this.policy = policy;
        this.transactions = transactions;
        this.pricing = pricing;
        this.sessions = sessions;
        this.rates = rates;
        this.stocks = stocks;
        this.reads = reads;
        this.quoteReads = quoteReads;
        this.clock = clock;
    }

    public OrderDetailResponse place(Long userId, LimitOrderRequest request) {
        var base = policy.parseInput(
                request.accountId(),
                request.clientOrderId(),
                request.symbol(),
                request.marketCountry(),
                request.side(),
                request.quantity()
        );
        String currency = LimitOrderRequestPolicy.currency(request.limitCurrency(), base.terms().marketCountry());
        var command = new LimitOrderCommand(
                base.accountId(),
                base.clientOrderId(),
                base.terms(),
                LimitOrderRequestPolicy.price(request.limitPrice(), currency),
                currency
        );
        Optional<OrderDetailResponse> existing = transactions.existing(userId, command);
        if (existing.isPresent()) {
            return unwrap(existing.get());
        }

        var stock = stocks.findBySymbolIgnoreCaseAndMarketCountry(base.terms().symbol(), base.terms().marketCountry())
                .orElseThrow(() -> retry(ErrorCode.STOCK_NOT_FOUND));
        ErrorCode reason = policy.determineStaticRejection(stock);
        if (reason != null) {
            throw retry(reason);
        }

        OrderMarketContext context = prepare(base.terms().marketCountry());
        LimitOrderPricing.Price price;
        try {
            price = pricing.calculate(command, context.executionRate());
        } catch (BusinessException e) {
            throw retry(e.getErrorCode());
        }
        return unwrap(transactions.accept(userId, command, context, price));
    }

    private OrderMarketContext prepare(MarketCountry country) {
        try {
            var session = sessions.currentSession(country, clock.instant());
            ExecutionRateEvidence evidence;
            if (country == MarketCountry.KR) {
                evidence = ExecutionRateEvidence.krw(clock.instant().atOffset(ZoneOffset.UTC));
            } else {
                var snapshot = rates.currentUsdKrwSnapshot();
                if (snapshot == null) {
                    throw retry(ErrorCode.EXCHANGE_RATE_NOT_FOUND);
                }
                evidence = ExecutionRateEvidence.from(snapshot);
            }
            return new OrderMarketContext(country, session.open(), session.validUntil(), evidence, clock.instant());
        } catch (BusinessException e) {
            throw retry(e.getErrorCode());
        }
    }

    private OrderDetailResponse unwrap(OrderDetailResponse result) {
        if (result.status() == OrderStatus.REJECTED) {
            throw new BusinessException(ErrorCode.valueOf(result.rejectReason()),
                    ClientOrderRetryPolicy.NEW_CLIENT_ORDER_ID.asData());
        }
        return result;
    }

    public OrderDetailResponse cancel(Long userId, Long orderId) {
        var order = reads.owned(userId, orderId);
        var result = transactions.close(userId, order.getAccountId(), orderId, false);
        if (result.status() != OrderStatus.CANCELED) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT,
                    Map.of("orderId", result.orderId(), "status", result.status().name()));
        }
        return result;
    }

    public LimitOrderQuoteResponse quote(
            Long userId,
            String symbol,
            String country,
            String side,
            String quantity,
            String price,
            String currency
    ) {
        var terms = policy.parseTerms(symbol, country, side, quantity);
        String normalizedCurrency = LimitOrderRequestPolicy.currency(currency, terms.marketCountry());
        BigDecimal requested = LimitOrderRequestPolicy.price(price, normalizedCurrency);
        var db = quoteReads.load(userId, terms);
        var context = prepare(terms.marketCountry());
        var p = pricing.calculate(
                new LimitOrderCommand(db.account().getAccountId(), null, terms, requested, normalizedCurrency),
                context.executionRate()
        );
        Instant now = clock.instant();
        policy.validateExecutionContextFresh(context, now);
        ErrorCode reason = policy.determineStaticRejection(db.stock());
        if (reason == null && !context.isMarketOpenAt(now)) {
            reason = ErrorCode.MARKET_CLOSED;
        }
        if (reason == null && !policy.hasValidCurrencyForMarket(db.stock(), db.quote())) {
            reason = ErrorCode.QUOTE_CURRENCY_MISMATCH;
        }
        if (reason == null) {
            reason = policy.validateQuoteTime(db.quote(), now);
        }
        if (reason == null && terms.side() == OrderSide.BUY && db.account().availableCash().compareTo(p.reserve()) < 0) {
            reason = ErrorCode.INSUFFICIENT_CASH;
        }
        if (reason == null && terms.side() == OrderSide.SELL && db.availableQuantity().compareTo(terms.quantity()) < 0) {
            reason = ErrorCode.INSUFFICIENT_QUANTITY;
        }
        var a = p.estimate();
        return new LimitOrderQuoteResponse(
                currency(requested, normalizedCurrency),
                normalizedCurrency,
                currency(p.limitPrice(), terms.marketCountry().defaultCurrency()),
                rate(context.executionRate()),
                reason == null,
                reason,
                krw(db.account().availableCash()),
                plain(db.availableQuantity()),
                context.marketOpenUntil() == null ? null : context.marketOpenUntil().atOffset(ZoneOffset.UTC),
                new LimitOrderQuoteResponse.Estimate(krw(a.grossAmount()), krw(a.fee()), krw(a.tax()), krw(a.netAmount()), krw(p.reserve())),
                Map.of("status", "UNSUPPORTED")
        );
    }

    private static BusinessException retry(ErrorCode code) {
        return new BusinessException(code, ClientOrderRetryPolicy.SAME_CLIENT_ORDER_ID.asData());
    }
}
