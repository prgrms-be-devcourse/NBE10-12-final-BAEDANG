package com.baedang.trading.service;

import com.baedang.global.error.BusinessException;
import com.baedang.global.error.ErrorCode;
import com.baedang.market.port.ExecutionExchangeRateProvider;
import com.baedang.market.port.MarketSessionProvider;
import com.baedang.stock.entity.MarketCountry;
import com.baedang.stock.entity.Stock;
import com.baedang.trading.dto.OrderQuoteResponse;
import com.baedang.trading.model.OrderAmount;
import com.baedang.trading.model.OrderQuoteQueryContext;
import com.baedang.trading.model.OrderTerms;
import com.baedang.user.entity.Account;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;

@Service
public class OrderQuoteService {

    private static final Logger log = LoggerFactory.getLogger(OrderQuoteService.class);

    private final OrderQuoteQueryService queryService;

    // 시장 데이터 모듈이 구현하는 포트입니다. 거래 모듈에서는 구현하지 않습니다.
    private final MarketSessionProvider marketSessionProvider;
    private final ExecutionExchangeRateProvider exchangeRateProvider;
    private final OrderAmountCalculator amountCalculator;
    private final MarketOrderPolicy marketOrderPolicy;
    private final Clock clock;

    public OrderQuoteService(
            OrderQuoteQueryService queryService,
            MarketSessionProvider marketSessionProvider,
            ExecutionExchangeRateProvider exchangeRateProvider,
            OrderAmountCalculator amountCalculator,
            MarketOrderPolicy marketOrderPolicy,
            Clock clock
    ) {
        this.queryService = queryService;
        this.marketSessionProvider = marketSessionProvider;
        this.exchangeRateProvider = exchangeRateProvider;
        this.amountCalculator = amountCalculator;
        this.marketOrderPolicy = marketOrderPolicy;
        this.clock = clock;
    }

    /** 견적은 자금이나 수량을 예약하지 않는 비구속성 읽기 모델입니다. */
    public OrderQuoteResponse getQuote(
            Long userId,
            String symbolValue,
            String marketCountryValue,
            String sideValue,
            String quantityValue
    ) {
        OrderTerms terms = marketOrderPolicy.parseTerms(
                symbolValue, marketCountryValue, sideValue, quantityValue);

        OrderQuoteQueryContext queryContext = queryService.load(userId, terms);
        Account account = queryContext.account();
        Stock stock = queryContext.stock();
        if (!marketOrderPolicy.hasValidCurrencyForMarket(stock, queryContext.quote())) {
            throw new BusinessException(
                    ErrorCode.QUOTE_CURRENCY_MISMATCH,
                    "stockCurrency=" + stock.getCurrency()
                            + ", quoteCurrency=" + queryContext.quote().getCurrency());
        }

        BigDecimal exchangeRate = stock.getMarketCountry() == MarketCountry.KR
                ? BigDecimal.ONE
                : exchangeRateProvider.currentUsdKrwRate();
        if (exchangeRate == null || exchangeRate.signum() <= 0) {
            throw new BusinessException(ErrorCode.EXCHANGE_RATE_NOT_FOUND);
        }
        OrderAmount amount = amountCalculator.calculate(
                stock.getMarketCountry(),
                terms.side(),
                queryContext.quote().getLastPrice(),
                terms.quantity(),
                exchangeRate
        );

        Instant now = clock.instant();
        ErrorCode reason = marketOrderPolicy.determineRejection(
                account,
                stock,
                queryContext.quote(),
                terms.side(),
                terms.quantity(),
                amount,
                queryContext.availableQuantity(),
                () -> marketSessionProvider.isOpen(stock.getMarketCountry(), now),
                now
        );
        if (reason != null) {
            log.info("시장가 견적 실행 불가: userId={}, stockId={}, side={}, quantity={}, reason={}",
                    userId, stock.getStockId(), terms.side(), terms.quantity(), reason);
        }
        return OrderQuoteResponse.of(
                stock.getSymbol(),
                stock.getMarketCountry(),
                terms.side(),
                terms.quantity(),
                amount,
                account.availableCash(),
                queryContext.quote().getQuoteAt(),
                reason
        );
    }
}
