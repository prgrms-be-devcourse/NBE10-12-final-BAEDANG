package com.baedang.trading.service;

import com.baedang.global.error.BusinessException;
import com.baedang.global.error.ErrorCode;
import com.baedang.market.entity.QuoteSnapshot;
import com.baedang.market.repository.QuoteSnapshotRepository;
import com.baedang.market.port.ExecutionExchangeRateProvider;
import com.baedang.market.port.MarketSessionProvider;
import com.baedang.stock.entity.MarketCountry;
import com.baedang.stock.entity.Stock;
import com.baedang.stock.repository.StockRepository;
import com.baedang.trading.dto.OrderQuoteResponse;
import com.baedang.trading.entity.Holding;
import com.baedang.trading.entity.OrderSide;
import com.baedang.trading.model.OrderAmount;
import com.baedang.trading.model.OrderTerms;
import com.baedang.trading.repository.HoldingRepository;
import com.baedang.user.entity.Account;
import com.baedang.user.entity.AccountStatus;
import com.baedang.user.repository.AccountRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;

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
    private final MarketOrderPolicy marketOrderPolicy;
    private final Clock clock;

    public OrderQuoteService(
            AccountRepository accountRepository,
            StockRepository stockRepository,
            QuoteSnapshotRepository quoteSnapshotRepository,
            HoldingRepository holdingRepository,
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
        this.marketSessionProvider = marketSessionProvider;
        this.exchangeRateProvider = exchangeRateProvider;
        this.amountCalculator = amountCalculator;
        this.marketOrderPolicy = marketOrderPolicy;
        this.clock = clock;
    }

    /** 견적은 자금이나 수량을 예약하지 않는 비구속성 읽기 모델입니다. */
    public OrderQuoteResponse getQuote(Long userId, String symbolValue, String sideValue, String quantityValue) {
        OrderTerms terms = marketOrderPolicy.parseTerms(symbolValue, sideValue, quantityValue);

        Account account = accountRepository.findByUserIdAndStatus(userId, AccountStatus.ACTIVE)
                .orElseThrow(() -> new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND, "userId=" + userId));
        Stock stock = stockRepository.findFirstBySymbolIgnoreCaseOrderByStockIdAsc(terms.symbol())
                .orElseThrow(() -> new BusinessException(ErrorCode.STOCK_NOT_FOUND, "symbol=" + terms.symbol()));
        QuoteSnapshot quote = quoteSnapshotRepository.findById(stock.getStockId())
                .orElseThrow(() -> new BusinessException(ErrorCode.QUOTE_NOT_FOUND, "stockId=" + stock.getStockId()));

        BigDecimal exchangeRate = stock.getMarketCountry() == MarketCountry.KR
                ? BigDecimal.ONE
                : exchangeRateProvider.currentUsdKrwRate();
        OrderAmount amount = amountCalculator.calculate(
                stock.getMarketCountry(),
                terms.side(),
                quote.getLastPrice(),
                terms.quantity(),
                exchangeRate
        );

        Instant now = clock.instant();
        BigDecimal availableQuantity = terms.side() == OrderSide.SELL
                ? availableQuantity(account, stock)
                : BigDecimal.ZERO;
        ErrorCode reason = marketOrderPolicy.determineRejection(
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
        return OrderQuoteResponse.of(
                stock.getSymbol(),
                terms.side(),
                terms.quantity(),
                amount,
                account.availableCash(),
                quote.getQuoteAt(),
                reason
        );
    }

    private BigDecimal availableQuantity(Account account, Stock stock) {
        return holdingRepository.findByAccountIdAndStockId(account.getAccountId(), stock.getStockId())
                .map(Holding::availableQuantity)
                .orElse(BigDecimal.ZERO);
    }

}
