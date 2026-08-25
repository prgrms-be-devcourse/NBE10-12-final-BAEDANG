package com.baedang.trading.service;

import com.baedang.global.error.BusinessException;
import com.baedang.global.error.ErrorCode;
import com.baedang.market.entity.QuoteSnapshot;
import com.baedang.market.port.ExecutionExchangeRateProvider;
import com.baedang.market.port.MarketSessionProvider;
import com.baedang.market.repository.QuoteSnapshotRepository;
import com.baedang.stock.entity.MarketCountry;
import com.baedang.stock.entity.Stock;
import com.baedang.stock.repository.StockRepository;
import com.baedang.trading.dto.OrderResponse;
import com.baedang.trading.entity.Holding;
import com.baedang.trading.entity.LedgerEntry;
import com.baedang.trading.entity.OrderSide;
import com.baedang.trading.entity.OrderStatus;
import com.baedang.trading.entity.OrderType;
import com.baedang.trading.entity.TradeOrder;
import com.baedang.trading.model.MarketOrderCommand;
import com.baedang.trading.model.MarketOrderResult;
import com.baedang.trading.model.OrderAmount;
import com.baedang.trading.model.OrderTerms;
import com.baedang.trading.repository.HoldingRepository;
import com.baedang.trading.repository.LedgerEntryRepository;
import com.baedang.trading.repository.TradeOrderRepository;
import com.baedang.user.entity.Account;
import com.baedang.user.entity.AccountStatus;
import com.baedang.user.repository.AccountRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/** 시장가 주문의 DB 변경을 하나의 트랜잭션으로 즉시 확정합니다. */
@Service
public class MarketOrderTransactionService {

    private final AccountRepository accountRepository;
    private final StockRepository stockRepository;
    private final QuoteSnapshotRepository quoteSnapshotRepository;
    private final HoldingRepository holdingRepository;
    private final TradeOrderRepository tradeOrderRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final MarketSessionProvider marketSessionProvider;
    private final ExecutionExchangeRateProvider exchangeRateProvider;
    private final OrderAmountCalculator amountCalculator;
    private final MarketOrderPolicy marketOrderPolicy;
    private final Clock clock;

    public MarketOrderTransactionService(
            AccountRepository accountRepository,
            StockRepository stockRepository,
            QuoteSnapshotRepository quoteSnapshotRepository,
            HoldingRepository holdingRepository,
            TradeOrderRepository tradeOrderRepository,
            LedgerEntryRepository ledgerEntryRepository,
            MarketSessionProvider marketSessionProvider,
            ExecutionExchangeRateProvider exchangeRateProvider,
            OrderAmountCalculator amountCalculator,
            MarketOrderPolicy marketOrderPolicy,
            Clock clock
    ) {
        this.accountRepository = accountRepository;
        this.stockRepository = stockRepository;
        this.quoteSnapshotRepository = quoteSnapshotRepository;
        this.holdingRepository = holdingRepository;
        this.tradeOrderRepository = tradeOrderRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.marketSessionProvider = marketSessionProvider;
        this.exchangeRateProvider = exchangeRateProvider;
        this.amountCalculator = amountCalculator;
        this.marketOrderPolicy = marketOrderPolicy;
        this.clock = clock;
    }

    @Transactional
    public MarketOrderResult execute(Long userId, MarketOrderCommand command) {
        // 거래 트랜잭션의 첫 DB 접근은 계좌 행 잠금입니다.
        Account account = accountRepository.findByUserIdAndStatusForUpdate(userId, AccountStatus.ACTIVE)
                .orElseThrow(() -> new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND, "userId=" + userId));

        OrderTerms terms = command.terms();
        Stock stock = stockRepository.findFirstBySymbolIgnoreCaseOrderByStockIdAsc(terms.symbol())
                .orElseThrow(() -> new BusinessException(ErrorCode.STOCK_NOT_FOUND, "symbol=" + terms.symbol()));

        TradeOrder existing = tradeOrderRepository.findByClientOrderId(command.clientOrderId()).orElse(null);
        if (existing != null) {
            verifySameRequest(existing, account, stock, terms);
            return existingResult(existing, account, stock);
        }

        QuoteSnapshot quote = quoteSnapshotRepository.findById(stock.getStockId())
                .orElseThrow(() -> new BusinessException(ErrorCode.QUOTE_NOT_FOUND, "stockId=" + stock.getStockId()));

        BigDecimal executionRate = stock.getMarketCountry() == MarketCountry.KR
                ? BigDecimal.ONE
                : exchangeRateProvider.currentUsdKrwRate();
        OrderAmount amount = amountCalculator.calculate(
                stock.getMarketCountry(), terms.side(), quote.getLastPrice(), terms.quantity(), executionRate);

        Holding holding = terms.side() == OrderSide.SELL
                ? holdingRepository.findByAccountIdAndStockIdForUpdate(account.getAccountId(), stock.getStockId())
                    .orElse(null)
                : null;
        BigDecimal availableQuantity = holding == null ? BigDecimal.ZERO : holding.availableQuantity();
        Instant now = clock.instant();
        OffsetDateTime orderedAt = OffsetDateTime.ofInstant(now, ZoneOffset.UTC);

        ErrorCode rejection = marketOrderPolicy.determineRejection(
                account,
                stock,
                quote,
                terms.side(),
                terms.quantity(),
                amount,
                availableQuantity,
                () -> marketSessionProvider.isOpen(stock.getMarketCountry(), now),
                now
        );
        if (rejection != null) {
            tradeOrderRepository.save(TradeOrder.rejectedMarketOrder(
                    account.getAccountId(), stock.getStockId(), command.clientOrderId(), terms.side(),
                    terms.quantity(), rejection.name(), orderedAt));
            return MarketOrderResult.rejected(rejection);
        }

        TradeOrder order = TradeOrder.filledMarketOrder(
                account.getAccountId(), stock.getStockId(), command.clientOrderId(), terms.side(),
                terms.quantity(), amount.executedPrice(), quote.getQuoteAt(), amount.exchangeRate(),
                amount.grossAmount(), amount.fee(), amount.tax(), amount.netAmount(), orderedAt);
        tradeOrderRepository.save(order);

        if (terms.side() == OrderSide.BUY) {
            account.debitMarketBuy(amount.netAmount());
            Holding buyHolding = holdingRepository
                    .findByAccountIdAndStockIdForUpdate(account.getAccountId(), stock.getStockId())
                    .orElse(null);
            if (buyHolding == null) {
                holdingRepository.save(Holding.firstBuy(
                        account.getAccountId(), stock.getStockId(), terms.quantity(),
                        amount.executedPrice(), amount.exchangeRate(), orderedAt));
            } else {
                buyHolding.addBuy(terms.quantity(), amount.executedPrice(), amount.exchangeRate(), orderedAt);
            }
        } else {
            holding.subtractSell(terms.quantity(), orderedAt);
            account.creditMarketSell(amount.netAmount());
        }

        String memo = createMemo(stock, order);
        LedgerEntry ledgerEntry = terms.side() == OrderSide.BUY
                ? LedgerEntry.buy(account.getAccountId(), order.getOrderId(), amount.netAmount(),
                    account.getCashBalance(), amount.exchangeRate(), memo, orderedAt)
                : LedgerEntry.sell(account.getAccountId(), order.getOrderId(), amount.netAmount(),
                    account.getCashBalance(), amount.exchangeRate(), memo, orderedAt);
        ledgerEntryRepository.save(ledgerEntry);

        return MarketOrderResult.filled(toResponse(order, account, stock, executionRate));
    }

    private MarketOrderResult existingResult(TradeOrder order, Account account, Stock stock) {
        if (order.getStatus() == OrderStatus.REJECTED) {
            try {
                return MarketOrderResult.rejected(ErrorCode.valueOf(order.getRejectReason()));
            } catch (IllegalArgumentException e) {
                throw new BusinessException(ErrorCode.DUPLICATE_ORDER, "orderId=" + order.getOrderId());
            }
        }
        if (order.getStatus() != OrderStatus.FILLED) {
            throw new BusinessException(ErrorCode.DUPLICATE_ORDER, "orderId=" + order.getOrderId());
        }
        return MarketOrderResult.filled(toResponse(order, account, stock, null));
    }

    private OrderResponse toResponse(
            TradeOrder order,
            Account account,
            Stock stock,
            BigDecimal executionRate
    ) {
        BigDecimal usdKrwRate = BigDecimal.ONE;
        if (holdingRepository.existsUsHolding(account.getAccountId())) {
            usdKrwRate = executionRate != null && stock.getMarketCountry() == MarketCountry.US
                    ? executionRate
                    : exchangeRateProvider.currentUsdKrwRate();
        }
        BigDecimal stockValue = holdingRepository.calculateStockValue(account.getAccountId(), usdKrwRate);
        return OrderResponse.filled(order, stock, account.getCashBalance(),
                account.getCashBalance().add(stockValue));
    }

    private void verifySameRequest(TradeOrder order, Account account, Stock stock, OrderTerms terms) {
        boolean same = order.getAccountId().equals(account.getAccountId())
                && order.getStockId().equals(stock.getStockId())
                && order.getOrderType() == OrderType.MARKET
                && order.getSide() == terms.side()
                && order.getQuantity().compareTo(terms.quantity()) == 0;
        if (!same) {
            throw new BusinessException(ErrorCode.DUPLICATE_ORDER,
                    "clientOrderId=" + order.getClientOrderId());
        }
    }

    private String createMemo(Stock stock, TradeOrder order) {
        return stock.getName() + " " + plain(order.getQuantity()) + "주 @ "
                + plain(order.getExecutedPrice()) + " (수수료·세금 포함)";
    }

    private String plain(BigDecimal value) {
        if (value.signum() == 0) return "0";
        return value.stripTrailingZeros().toPlainString();
    }
}
