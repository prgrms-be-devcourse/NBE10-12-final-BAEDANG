package com.baedang.trading.service;

import com.baedang.stock.entity.MarketCountry;
import com.baedang.global.error.ErrorCode;
import com.baedang.stock.entity.Stock;
import com.baedang.trading.dto.LimitExecutionPreviewResponse;
import com.baedang.trading.model.CumulativeSettlementState;
import com.baedang.trading.model.LimitExecutionBook;
import com.baedang.trading.model.LimitExecutionPlan;
import com.baedang.trading.model.OrderMarketContext;
import com.baedang.trading.model.OrderTerms;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;

@Service
public class LimitOrderPreviewService {
    private final LimitExecutionBookReader books;
    private final LimitOrderExecutionPlanner planner;
    private final Clock clock;

    public LimitOrderPreviewService(LimitExecutionBookReader books, LimitOrderExecutionPlanner planner, Clock clock) {
        this.books = books;
        this.planner = planner;
        this.clock = clock;
    }

    public LimitExecutionPreviewResponse preview(Stock stock, OrderTerms terms, LimitOrderPricing.Price price,
            OrderMarketContext context, ErrorCode rejection) {
        Instant now = clock.instant();
        if (rejection != null) return LimitExecutionPreviewResponse.unavailable(LimitExecutionPreviewResponse.Status.NOT_APPLICABLE, rejection.name(), now);
        LimitExecutionBook book = books.read(stock, terms.side(), now).orElse(null);
        now = clock.instant();
        if (book == null) return LimitExecutionPreviewResponse.unavailable(LimitExecutionPreviewResponse.Status.UNAVAILABLE, "NO_USABLE_BOOK", now);
        if (!books.isFresh(stock, stock.getCurrency(), book.quoteAt(), book.generatedAt(), now)
                || !context.isMarketOpenAt(now)
                || !context.executionRateEvidence().isValidAt(now.atOffset(java.time.ZoneOffset.UTC))) {
            return LimitExecutionPreviewResponse.unavailable(LimitExecutionPreviewResponse.Status.UNAVAILABLE, "CONTEXT_EXPIRED", now);
        }
        LimitExecutionPlan plan = planner.plan(stock.getMarketCountry(), terms.side(), price.limitPrice(), terms.quantity(),
                price.reserve(), context.executionRate(), CumulativeSettlementState.empty(), book.levels());
        return LimitExecutionPreviewResponse.from(book, plan, stock.getMarketCountry() == MarketCountry.KR ? 0 : 2, now);
    }
}
