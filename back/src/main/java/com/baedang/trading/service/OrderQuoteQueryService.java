package com.baedang.trading.service;

import com.baedang.global.error.BusinessException;
import com.baedang.global.error.ErrorCode;
import com.baedang.market.entity.QuoteSnapshot;
import com.baedang.market.repository.QuoteSnapshotRepository;
import com.baedang.stock.entity.Stock;
import com.baedang.stock.repository.StockRepository;
import com.baedang.trading.entity.Holding;
import com.baedang.trading.entity.OrderSide;
import com.baedang.trading.model.OrderQuoteQueryContext;
import com.baedang.trading.model.OrderTerms;
import com.baedang.trading.repository.HoldingRepository;
import com.baedang.user.entity.Account;
import com.baedang.user.entity.AccountStatus;
import com.baedang.user.repository.AccountRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/** 견적에 필요한 DB 값만 하나의 짧은 읽기 전용 트랜잭션에서 조회합니다. */
@Service
public class OrderQuoteQueryService {

    private final AccountRepository accountRepository;
    private final StockRepository stockRepository;
    private final QuoteSnapshotRepository quoteSnapshotRepository;
    private final HoldingRepository holdingRepository;

    public OrderQuoteQueryService(
            AccountRepository accountRepository,
            StockRepository stockRepository,
            QuoteSnapshotRepository quoteSnapshotRepository,
            HoldingRepository holdingRepository
    ) {
        this.accountRepository = accountRepository;
        this.stockRepository = stockRepository;
        this.quoteSnapshotRepository = quoteSnapshotRepository;
        this.holdingRepository = holdingRepository;
    }

    @Transactional(readOnly = true)
    public OrderQuoteQueryContext load(Long userId, OrderTerms terms) {
        Account account = accountRepository.findByUserIdAndStatus(userId, AccountStatus.ACTIVE)
                .orElseThrow(() -> new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND, "userId=" + userId));
        Stock stock = stockRepository.findBySymbolIgnoreCase(terms.symbol())
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.STOCK_NOT_FOUND, "symbol=" + terms.symbol()));
        QuoteSnapshot quote = quoteSnapshotRepository.findById(stock.getStockId())
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.QUOTE_NOT_FOUND, "stockId=" + stock.getStockId()));
        BigDecimal availableQuantity = terms.side() == OrderSide.SELL
                ? holdingRepository.findByAccountIdAndStockId(account.getAccountId(), stock.getStockId())
                    .map(Holding::availableQuantity)
                    .orElse(BigDecimal.ZERO)
                : BigDecimal.ZERO;
        return new OrderQuoteQueryContext(account, stock, quote, availableQuantity);
    }
}
