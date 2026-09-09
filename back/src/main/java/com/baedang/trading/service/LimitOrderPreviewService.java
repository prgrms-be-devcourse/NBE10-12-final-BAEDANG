package com.baedang.trading.service;

import com.baedang.stock.entity.MarketCountry;
import com.baedang.global.error.BusinessException;
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
    private final OrderPolicy policy;
    private final Clock clock;

    public LimitOrderPreviewService(LimitExecutionBookReader books, LimitOrderExecutionPlanner planner, OrderPolicy policy, Clock clock) {
        this.books = books;
        this.planner = planner;
        this.policy = policy;
        this.clock = clock;
    }

    public LimitExecutionPreviewResponse preview(Stock stock, OrderTerms terms, LimitOrderPricing.Price price,
            OrderMarketContext context, ErrorCode rejection) {
        Instant now = clock.instant();
        if (rejection != null) return LimitExecutionPreviewResponse.unavailable(LimitExecutionPreviewResponse.Status.NOT_APPLICABLE, rejection.name(), now);
        LimitExecutionBook book = books.read(stock, terms.side(), now).orElse(null);
        now = clock.instant();
        if (book == null) return LimitExecutionPreviewResponse.unavailable(LimitExecutionPreviewResponse.Status.UNAVAILABLE, "NO_USABLE_BOOK", now);
        try {
            // 호가 조회 지연도 컨텍스트 수명에 포함하며 실제 체결과 같은 정책을 적용합니다.
            policy.validateExecutionContextFresh(context, now);
        } catch (BusinessException exception) {
            if (exception.getErrorCode() != ErrorCode.MARKET_CONTEXT_EXPIRED
                    && exception.getErrorCode() != ErrorCode.EXCHANGE_RATE_NOT_FOUND) throw exception;
            return LimitExecutionPreviewResponse.unavailable(LimitExecutionPreviewResponse.Status.UNAVAILABLE, "CONTEXT_EXPIRED", now);
        }
        if (!books.isFresh(stock, stock.getCurrency(), book.quoteAt(), book.generatedAt(), now)
                || !context.isMarketOpenAt(now)) {
            return LimitExecutionPreviewResponse.unavailable(LimitExecutionPreviewResponse.Status.UNAVAILABLE, "CONTEXT_EXPIRED", now);
        }
        LimitExecutionPlan plan = planner.plan(stock.getMarketCountry(), terms.side(), price.limitPrice(), terms.quantity(),
                price.reserve(), context.executionRate(), CumulativeSettlementState.empty(), book.levels());
        return LimitExecutionPreviewResponse.from(book, plan, stock.getMarketCountry() == MarketCountry.KR ? 0 : 2, now);
    }
}
