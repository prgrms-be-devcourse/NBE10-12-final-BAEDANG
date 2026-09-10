package com.baedang.trading.service;

import com.baedang.global.error.BusinessException;
import com.baedang.global.error.ErrorCode;
import com.baedang.market.entity.QuoteSnapshot;
import com.baedang.market.service.QuoteRefreshCoordinator;
import com.baedang.stock.entity.Stock;
import com.baedang.stock.service.StockTradingStatusService;
import com.baedang.trading.model.OrderQuoteQueryContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import com.baedang.market.repository.QuoteSnapshotRepository;
import java.time.Duration;

/** 외부 준비 전용. 금융 트랜잭션/차트 백필을 시작하지 않습니다. */
@Service
@Transactional(propagation = Propagation.NEVER)
public class OrderMarketDataService {
    private final StockTradingStatusService statuses;
    private final QuoteRefreshCoordinator quotes;
    private final QuoteSnapshotRepository snapshots;
    private final Duration maxAge;

    public OrderMarketDataService(StockTradingStatusService statuses, QuoteRefreshCoordinator quotes,
            QuoteSnapshotRepository snapshots, @Value("${trading.quote-max-staleness-seconds}") long maxAgeSeconds) {
        this.statuses = statuses;
        this.quotes = quotes;
        this.snapshots = snapshots;
        this.maxAge = Duration.ofSeconds(maxAgeSeconds);
    }

    public Stock refreshStatus(Stock stock) {
        return statuses.requireCurrent(stock);
    }

    public QuoteSnapshot requireQuote(Stock stock) {
        return quotes.requireFresh(stock, maxAge);
    }

    /** 시각 오류는 기존 견적의 실행 불가 reason으로 표현하고, 통신/데이터 부재는 HTTP 오류로 유지합니다. */
    public OrderQuoteQueryContext prepareEstimate(OrderQuoteQueryContext db) {
        Stock stock = refreshStatus(db.stock());
        QuoteSnapshot quote;
        try {
            quote = requireQuote(stock);
        } catch (BusinessException exception) {
            if (exception.getErrorCode() != ErrorCode.STALE_QUOTE
                    && exception.getErrorCode() != ErrorCode.FUTURE_QUOTE) throw exception;
            quote = snapshots.findById(stock.getStockId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.QUOTE_NOT_FOUND));
        }
        return new OrderQuoteQueryContext(db.account(), stock, quote, db.availableQuantity());
    }
}
