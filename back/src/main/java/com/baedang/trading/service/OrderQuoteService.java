package com.baedang.trading.service;

import com.baedang.global.error.BusinessException;
import com.baedang.global.error.ErrorCode;
import com.baedang.market.entity.QuoteSnapshot;
import com.baedang.market.repository.QuoteSnapshotRepository;
import com.baedang.market.port.ExecutionExchangeRateProvider;
import com.baedang.market.port.MarketSessionProvider;
import com.baedang.stock.entity.ListingStatus;
import com.baedang.stock.entity.MarketCountry;
import com.baedang.stock.entity.Stock;
import com.baedang.stock.repository.StockRepository;
import com.baedang.trading.dto.OrderQuoteResponse;
import com.baedang.trading.entity.Holding;
import com.baedang.trading.entity.OrderSide;
import com.baedang.trading.model.OrderAmount;
import com.baedang.trading.repository.HoldingRepository;
import com.baedang.user.entity.Account;
import com.baedang.user.entity.AccountStatus;
import com.baedang.user.repository.AccountRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;

@Service
@Transactional(readOnly = true)
public class OrderQuoteService {

    private final AccountRepository accountRepository;
    private final StockRepository stockRepository;
    private final QuoteSnapshotRepository quoteSnapshotRepository;
    private final HoldingRepository holdingRepository;

    // 시장 데이터 담당 구현과 연결되는 포트입니다. 거래 모듈에서는 구현하지 않습니다.
    // 상대 구현체가 Spring Bean으로 병합되기 전 IDE 자동 주입 경고가 발생할 수 있습니다.
    private final MarketSessionProvider marketSessionProvider;
    private final ExecutionExchangeRateProvider exchangeRateProvider;
    private final OrderAmountCalculator amountCalculator;
    private final Clock clock;
    private final Duration quoteMaxStaleness;

    public OrderQuoteService(
            AccountRepository accountRepository,
            StockRepository stockRepository,
            QuoteSnapshotRepository quoteSnapshotRepository,
            HoldingRepository holdingRepository,
            MarketSessionProvider marketSessionProvider,
            ExecutionExchangeRateProvider exchangeRateProvider,
            OrderAmountCalculator amountCalculator,
            Clock clock,
            @Value("${trading.quote-max-staleness-seconds}") long quoteMaxStalenessSeconds
    ) {
        this.accountRepository = accountRepository;
        this.stockRepository = stockRepository;
        this.quoteSnapshotRepository = quoteSnapshotRepository;
        this.holdingRepository = holdingRepository;
        this.marketSessionProvider = marketSessionProvider;
        this.exchangeRateProvider = exchangeRateProvider;
        this.amountCalculator = amountCalculator;
        this.clock = clock;
        this.quoteMaxStaleness = Duration.ofSeconds(quoteMaxStalenessSeconds);
    }

    /** 견적은 자금이나 수량을 예약하지 않는 비구속성 읽기 모델입니다. */
    public OrderQuoteResponse getQuote(Long userId, String symbolValue, String sideValue, String quantityValue) {
        OrderSide side = parseSide(sideValue);
        BigDecimal quantity = parseQuantity(quantityValue);
        String symbol = normalizeSymbol(symbolValue);

        Account account = accountRepository.findByUserIdAndStatus(userId, AccountStatus.ACTIVE)
                .orElseThrow(() -> new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND, "userId=" + userId));
        Stock stock = stockRepository.findFirstBySymbolIgnoreCaseOrderByStockIdAsc(symbol)
                .orElseThrow(() -> new BusinessException(ErrorCode.STOCK_NOT_FOUND, "symbol=" + symbol));
        QuoteSnapshot quote = quoteSnapshotRepository.findById(stock.getStockId())
                .orElseThrow(() -> new BusinessException(ErrorCode.QUOTE_NOT_FOUND, "stockId=" + stock.getStockId()));

        BigDecimal exchangeRate = stock.getMarketCountry() == MarketCountry.KR
                ? BigDecimal.ONE
                : exchangeRateProvider.currentUsdKrwRate();
        OrderAmount amount = amountCalculator.calculate(
                stock.getMarketCountry(),
                side,
                quote.getLastPrice(),
                quantity,
                exchangeRate
        );

        Instant now = clock.instant();
        ErrorCode reason = determineReason(account, stock, quote, side, quantity, amount, now);
        return OrderQuoteResponse.of(
                stock.getSymbol(),
                side,
                quantity,
                amount,
                account.availableCash(),
                quote.getQuoteAt(),
                reason
        );
    }

    private ErrorCode determineReason(
            Account account,
            Stock stock,
            QuoteSnapshot quote,
            OrderSide side,
            BigDecimal quantity,
            OrderAmount amount,
            Instant now
    ) {
        if (!Boolean.TRUE.equals(stock.getIsRanked()) || stock.getListingStatus() != ListingStatus.ACTIVE) {
            return ErrorCode.NOT_IN_UNIVERSE;
        }
        if (Boolean.TRUE.equals(stock.getIsSuspended())) return ErrorCode.STOCK_SUSPENDED;
        if (Boolean.TRUE.equals(stock.getIsLiquidation())) return ErrorCode.STOCK_LIQUIDATION;
        if (!marketSessionProvider.isOpen(stock.getMarketCountry(), now)) return ErrorCode.MARKET_CLOSED;
        if (isStale(quote, now)) return ErrorCode.STALE_QUOTE;

        if (side == OrderSide.BUY && account.availableCash().compareTo(amount.netAmount()) < 0) {
            return ErrorCode.INSUFFICIENT_CASH;
        }
        if (side == OrderSide.SELL && availableQuantity(account, stock).compareTo(quantity) < 0) {
            return ErrorCode.INSUFFICIENT_QUANTITY;
        }
        return null;
    }

    private boolean isStale(QuoteSnapshot quote, Instant now) {
        Duration age = Duration.between(quote.getQuoteAt().toInstant(), now);
        return !age.isNegative() && age.compareTo(quoteMaxStaleness) > 0;
    }

    private BigDecimal availableQuantity(Account account, Stock stock) {
        return holdingRepository.findByAccountIdAndStockId(account.getAccountId(), stock.getStockId())
                .map(Holding::availableQuantity)
                .orElse(BigDecimal.ZERO);
    }

    private OrderSide parseSide(String side) {
        if (side == null || side.isBlank()) throw new BusinessException(ErrorCode.INVALID_INPUT);
        try {
            return OrderSide.valueOf(side.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "side=" + side);
        }
    }

    private BigDecimal parseQuantity(String quantity) {
        if (quantity == null || quantity.isBlank()) throw new BusinessException(ErrorCode.INVALID_QUANTITY);
        try {
            BigDecimal parsed = new BigDecimal(quantity.trim());
            if (parsed.compareTo(BigDecimal.ONE) < 0 || parsed.stripTrailingZeros().scale() > 0) {
                throw new BusinessException(ErrorCode.INVALID_QUANTITY, "quantity=" + quantity);
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw new BusinessException(ErrorCode.INVALID_QUANTITY, "quantity=" + quantity);
        }
    }

    private String normalizeSymbol(String symbol) {
        if (symbol == null || symbol.isBlank()) throw new BusinessException(ErrorCode.INVALID_INPUT);
        return symbol.trim().toUpperCase(Locale.ROOT);
    }
}
