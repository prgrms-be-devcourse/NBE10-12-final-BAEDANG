package com.baedang.trading.service;

import com.baedang.global.error.BusinessException;
import com.baedang.global.error.ErrorCode;
import com.baedang.market.entity.QuoteSnapshot;
import com.baedang.market.repository.QuoteSnapshotRepository;
import com.baedang.stock.entity.MarketCountry;
import com.baedang.stock.entity.Stock;
import com.baedang.stock.repository.StockRepository;
import com.baedang.trading.entity.Holding;
import com.baedang.trading.entity.LedgerEntry;
import com.baedang.trading.entity.OrderSide;
import com.baedang.trading.entity.OrderStatus;
import com.baedang.trading.entity.OrderType;
import com.baedang.trading.entity.TradeOrder;
import com.baedang.trading.model.MarketOrderCommand;
import com.baedang.trading.model.MarketOrderExecutionContext;
import com.baedang.trading.model.MarketOrderReceipt;
import com.baedang.trading.model.MarketOrderResult;
import com.baedang.trading.model.ClientOrderRetryPolicy;
import com.baedang.trading.model.OrderAmount;
import com.baedang.trading.model.OrderTerms;
import com.baedang.trading.repository.HoldingRepository;
import com.baedang.trading.repository.LedgerEntryRepository;
import com.baedang.trading.repository.TradeOrderRepository;
import com.baedang.user.entity.Account;
import com.baedang.user.entity.AccountStatus;
import com.baedang.user.repository.AccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import static com.baedang.trading.model.AmountFormatter.plain;

/** 시장가 주문의 DB 변경을 하나의 트랜잭션으로 즉시 확정합니다. */
@Service
public class MarketOrderTransactionService {

    private static final Logger log = LoggerFactory.getLogger(MarketOrderTransactionService.class);
    private static final int MAX_MEMO_LENGTH = 200;

    private final AccountRepository accountRepository;
    private final StockRepository stockRepository;
    private final QuoteSnapshotRepository quoteSnapshotRepository;
    private final HoldingRepository holdingRepository;
    private final TradeOrderRepository tradeOrderRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
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
        this.amountCalculator = amountCalculator;
        this.marketOrderPolicy = marketOrderPolicy;
        this.clock = clock;
    }

    /** 처리 완료된 주문은 외부 시장 데이터 조회 없이 저장된 감사 값으로 재생합니다. */
    @Transactional(readOnly = true)
    public Optional<MarketOrderResult> findExisting(Long userId, MarketOrderCommand command) {
        Account account = accountRepository.findByAccountIdAndUserId(command.accountId(), userId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.ACCOUNT_NOT_FOUND, "accountId=" + command.accountId()));
        TradeOrder existing = tradeOrderRepository
                .findByAccountIdAndClientOrderId(account.getAccountId(), command.clientOrderId())
                .orElse(null);
        if (existing == null) {
            rejectChangedRound(account);
            return Optional.empty();
        }

        Stock stock = stockRepository.findById(existing.getStockId())
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.STOCK_NOT_FOUND, "stockId=" + existing.getStockId()));
        verifySameRequest(existing, account, stock, command.terms());
        log.info("시장가 주문 멱등 응답: orderId={}, accountId={}, status={}",
                existing.getOrderId(), account.getAccountId(), existing.getStatus());
        return Optional.of(existingResult(existing, stock));
    }

    @Transactional
    public MarketOrderResult execute(
            Long userId,
            MarketOrderCommand command,
            MarketOrderExecutionContext executionContext
    ) {
        // 거래 트랜잭션의 첫 DB 접근은 계좌 행 잠금입니다.
        Account account = accountRepository.findByAccountIdAndUserIdForUpdate(command.accountId(), userId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.ACCOUNT_NOT_FOUND, "accountId=" + command.accountId()));
        rejectChangedRound(account);
        Instant now = clock.instant();

        OrderTerms terms = command.terms();
        Stock stock = stockRepository.findBySymbolIgnoreCaseAndMarketCountry(
                        terms.symbol(), terms.marketCountry())
                .orElseThrow(() -> new BusinessException(ErrorCode.STOCK_NOT_FOUND, "symbol=" + terms.symbol()));
        if (stock.getMarketCountry() != executionContext.marketCountry()) {
            throw new BusinessException(ErrorCode.STOCK_NOT_FOUND, "symbol=" + terms.symbol());
        }

        TradeOrder existing = tradeOrderRepository
                .findByAccountIdAndClientOrderId(account.getAccountId(), command.clientOrderId())
                .orElse(null);
        if (existing != null) {
            verifySameRequest(existing, account, stock, terms);
            log.info("시장가 주문 동시 멱등 응답: orderId={}, accountId={}, status={}",
                    existing.getOrderId(), account.getAccountId(), existing.getStatus());
            return existingResult(existing, stock);
        }

        // 신규 주문만 검사합니다. 락 대기 중 같은 주문이 먼저 확정됐다면 위에서 저장 결과를 반환합니다.
        marketOrderPolicy.validateExecutionContextFresh(executionContext, now);

        QuoteSnapshot quote = quoteSnapshotRepository.findById(stock.getStockId())
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.QUOTE_NOT_FOUND,
                        "stockId=" + stock.getStockId(),
                        ClientOrderRetryPolicy.SAME_CLIENT_ORDER_ID.asData()));
        if (!marketOrderPolicy.hasValidCurrencyForMarket(stock, quote)) {
            throw new BusinessException(
                    ErrorCode.QUOTE_CURRENCY_MISMATCH,
                    "stockCurrency=" + stock.getCurrency() + ", quoteCurrency=" + quote.getCurrency(),
                    ClientOrderRetryPolicy.SAME_CLIENT_ORDER_ID.asData());
        }

        BigDecimal executionRate = stock.getMarketCountry() == MarketCountry.KR
                ? BigDecimal.ONE
                : executionContext.executionRate();
        OrderAmount amount = amountCalculator.calculate(
                stock.getMarketCountry(), terms.side(), quote.getLastPrice(), terms.quantity(), executionRate);

        Holding holding = terms.side() == OrderSide.SELL
                ? holdingRepository.findByAccountIdAndStockIdForUpdate(account.getAccountId(), stock.getStockId())
                    .orElse(null)
                : null;
        BigDecimal availableQuantity = holding == null ? BigDecimal.ZERO : holding.availableQuantity();
        OffsetDateTime orderedAt = OffsetDateTime.ofInstant(now, ZoneOffset.UTC);

        ErrorCode rejection = marketOrderPolicy.determineRejection(
                account,
                stock,
                quote,
                terms.side(),
                terms.quantity(),
                amount,
                availableQuantity,
                () -> executionContext.isMarketOpenAt(now),
                now
        );
        if (rejection != null) {
            TradeOrder rejectedOrder = tradeOrderRepository.save(TradeOrder.rejectedMarketOrder(
                    account.getAccountId(), stock.getStockId(), command.clientOrderId(), terms.side(),
                    terms.quantity(), quote.getLastPrice(), quote.getQuoteAt(), amount.exchangeRate(),
                    rejection.name(), orderedAt));
            log.info("시장가 주문 거절: orderId={}, accountId={}, stockId={}, reason={}",
                    rejectedOrder.getOrderId(), account.getAccountId(), stock.getStockId(), rejection);
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

        log.info("시장가 주문 체결: orderId={}, accountId={}, stockId={}, side={}, quantity={}",
                order.getOrderId(), account.getAccountId(), stock.getStockId(), terms.side(), terms.quantity());

        return MarketOrderResult.filled(MarketOrderReceipt.from(
                order, stock, account.getCashBalance()));
    }

    private MarketOrderResult existingResult(TradeOrder order, Stock stock) {
        if (order.getStatus() == OrderStatus.REJECTED) {
            try {
                return MarketOrderResult.rejected(ErrorCode.valueOf(order.getRejectReason()));
            } catch (IllegalArgumentException | NullPointerException e) {
                throw new BusinessException(ErrorCode.DUPLICATE_ORDER, "orderId=" + order.getOrderId());
            }
        }
        if (order.getStatus() != OrderStatus.FILLED) {
            throw new BusinessException(ErrorCode.DUPLICATE_ORDER, "orderId=" + order.getOrderId());
        }
        LedgerEntry ledgerEntry = ledgerEntryRepository
                .findFirstByOrderIdOrderByEntryIdAsc(order.getOrderId())
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.INTERNAL_ERROR, "filled order ledger missing: orderId=" + order.getOrderId()));
        return MarketOrderResult.filled(MarketOrderReceipt.from(
                order, stock, ledgerEntry.getBalanceAfter()));
    }

    private void verifySameRequest(TradeOrder order, Account account, Stock stock, OrderTerms terms) {
        boolean same = order.getAccountId().equals(account.getAccountId())
                && order.getStockId().equals(stock.getStockId())
                && stock.getSymbol().equalsIgnoreCase(terms.symbol())
                && stock.getMarketCountry() == terms.marketCountry()
                && order.getOrderType() == OrderType.MARKET
                && order.getSide() == terms.side()
                && order.getQuantity().compareTo(terms.quantity()) == 0;
        if (!same) {
            throw new BusinessException(ErrorCode.DUPLICATE_ORDER,
                    "clientOrderId=" + order.getClientOrderId(),
                    ClientOrderRetryPolicy.NOT_RETRYABLE.asData());
        }
    }

    private void rejectChangedRound(Account account) {
        if (account.getStatus() != AccountStatus.ACTIVE) {
            throw new BusinessException(
                    ErrorCode.ACCOUNT_ROUND_CHANGED,
                    "accountId=" + account.getAccountId(),
                    ClientOrderRetryPolicy.NOT_RETRYABLE.asData());
        }
    }

    private String createMemo(Stock stock, TradeOrder order) {
        String memo = stock.getName() + " " + plain(order.getQuantity()) + "주 @ "
                + plain(order.getExecutedPrice()) + " (수수료·세금 포함)";
        return memo.length() <= MAX_MEMO_LENGTH ? memo : memo.substring(0, MAX_MEMO_LENGTH);
    }
}
