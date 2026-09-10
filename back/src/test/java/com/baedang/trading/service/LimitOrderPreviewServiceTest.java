package com.baedang.trading.service;

import com.baedang.orderbook.support.MutableClock;
import com.baedang.stock.entity.MarketCountry;
import com.baedang.stock.entity.Stock;
import com.baedang.trading.dto.LimitExecutionPreviewResponse;
import com.baedang.trading.entity.OrderSide;
import com.baedang.trading.model.ExecutionRateEvidence;
import com.baedang.trading.model.LimitExecutionBook;
import com.baedang.trading.model.LimitExecutionPlan;
import com.baedang.trading.model.OrderMarketContext;
import com.baedang.trading.model.OrderTerms;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LimitOrderPreviewServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-09T01:00:00Z");
    private final MutableClock clock = new MutableClock(NOW);
    private final LimitExecutionBookReader books = mock(LimitExecutionBookReader.class);
    private final OrderPolicy policy = new OrderPolicy(15, 15, new BigDecimal("1000000"));
    private final LimitOrderExecutionPlanner planner = new LimitOrderExecutionPlanner(new LimitOrderSettlementCalculator(
            new BigDecimal("0.0001"), new BigDecimal("0.002"), new BigDecimal("0.0000206"),
            new BigDecimal("0.01"), new BigDecimal("1000000")));
    private final LimitOrderPreviewService service = new LimitOrderPreviewService(books, planner, policy, clock);

    @ParameterizedTest
    @CsvSource({"14, AVAILABLE", "15, UNAVAILABLE"})
    void 조회전에는_유효해도_조회후_컨텍스트_수명으로_판정한다(int ageBeforeRead, LimitExecutionPreviewResponse.Status expected) {
        OrderMarketContext context = context(NOW.minusSeconds(ageBeforeRead), NOW.plusSeconds(60));
        policy.validateExecutionContextFresh(context, NOW);

        LimitExecutionPreviewResponse result = previewAfterOneSecond(context);

        assertThat(result.status()).isEqualTo(expected);
        assertThat(result.evaluatedAt()).isEqualTo(NOW.plusSeconds(1));
        if (expected == LimitExecutionPreviewResponse.Status.AVAILABLE) {
            assertThat(result.expectedFilledQuantity()).isEqualTo("1");
            assertThat(result.netAmountKrw()).isEqualTo("140014");
        } else {
            assertThat(result.reason()).isEqualTo("CONTEXT_EXPIRED");
            assertThat(result.expectedFilledQuantity()).isNull();
        }
    }

    @Test
    void 조회중_환율이_만료되면_미리보기도_보류한다() {
        LimitExecutionPreviewResponse result = previewAfterOneSecond(context(NOW, NOW.plusSeconds(1)));
        assertThat(result.status()).isEqualTo(LimitExecutionPreviewResponse.Status.UNAVAILABLE);
        assertThat(result.reason()).isEqualTo("CONTEXT_EXPIRED");
        assertThat(result.netAmountKrw()).isNull();
    }

    private LimitExecutionPreviewResponse previewAfterOneSecond(OrderMarketContext context) {
        Stock stock = Stock.create("INTC", MarketCountry.US, "NASDAQ", "Intel", null, "USD", "STOCK", true);
        LimitExecutionBook book = new LimitExecutionBook(1L, 0L, NOW, NOW,
                List.of(new LimitExecutionPlan.Level(1L, new BigDecimal("100"), BigDecimal.ONE)));
        when(books.read(eq(stock), eq(OrderSide.BUY), any())).thenAnswer(invocation -> {
            clock.advance(Duration.ofSeconds(1));
            return Optional.of(book);
        });
        when(books.isFresh(eq(stock), eq("USD"), eq(NOW), eq(NOW), any())).thenReturn(true);
        return service.preview(stock, new OrderTerms("INTC", MarketCountry.US, OrderSide.BUY, BigDecimal.ONE),
                new LimitOrderPricing.Price(new BigDecimal("100"), new BigDecimal("140014"), null), context, null);
    }

    private OrderMarketContext context(Instant checkedAt, Instant validUntil) {
        ExecutionRateEvidence rate = new ExecutionRateEvidence(new BigDecimal("1400"), NOW.atOffset(ZoneOffset.UTC),
                NOW.atOffset(ZoneOffset.UTC), validUntil.atOffset(ZoneOffset.UTC));
        return new OrderMarketContext(MarketCountry.US, true, NOW.plusSeconds(3600), rate, checkedAt);
    }
}
