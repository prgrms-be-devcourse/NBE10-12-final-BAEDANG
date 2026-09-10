package com.baedang.trading.service;

import com.baedang.global.error.BusinessException;
import com.baedang.global.error.ErrorCode;
import com.baedang.market.entity.QuoteSnapshot;
import com.baedang.market.repository.QuoteSnapshotRepository;
import com.baedang.market.service.QuoteRefreshCoordinator;
import com.baedang.stock.entity.Stock;
import com.baedang.stock.service.StockTradingStatusService;
import com.baedang.trading.model.OrderQuoteQueryContext;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Optional;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class OrderMarketDataServiceTest {
    private final StockTradingStatusService statuses = mock(StockTradingStatusService.class);
    private final QuoteRefreshCoordinator quotes = mock(QuoteRefreshCoordinator.class);
    private final QuoteSnapshotRepository repository = mock(QuoteSnapshotRepository.class);
    private final OrderMarketDataService service = new OrderMarketDataService(statuses, quotes, repository, 15);

    @Test
    void 시세가_없는_견적도_상태와_현재가를_확보한다() {
        Stock stock = mock(Stock.class);
        QuoteSnapshot quote = mock(QuoteSnapshot.class);
        when(statuses.requireCurrent(stock)).thenReturn(stock);
        when(quotes.requireFresh(stock, Duration.ofSeconds(15))).thenReturn(quote);
        OrderQuoteQueryContext result = service.prepareEstimate(
                new OrderQuoteQueryContext(null, stock, null, BigDecimal.ZERO));
        assertThat(result.quote()).isSameAs(quote);
        verifyNoInteractions(repository);
    }

    @Test
    void 재조회후에도_stale이면_견적에는_원본을_돌려주고_주문용은_거절한다() {
        Stock stock = mock(Stock.class);
        when(stock.getStockId()).thenReturn(1L);
        QuoteSnapshot stale = mock(QuoteSnapshot.class);
        when(statuses.requireCurrent(stock)).thenReturn(stock);
        when(quotes.requireFresh(stock, Duration.ofSeconds(15))).thenThrow(new BusinessException(ErrorCode.STALE_QUOTE));
        when(repository.findById(1L)).thenReturn(Optional.of(stale));
        assertThat(service.prepareEstimate(new OrderQuoteQueryContext(null, stock, null, BigDecimal.ZERO)).quote())
                .isSameAs(stale);
        assertThatThrownBy(() -> service.requireQuote(stock)).isInstanceOf(BusinessException.class);
    }

    @Test
    void 상태_조회_실패는_현재가_조회나_과거값으로_우회하지_않는다() {
        Stock stock = mock(Stock.class);
        when(statuses.requireCurrent(stock)).thenThrow(new BusinessException(ErrorCode.STOCK_STATUS_UNAVAILABLE));
        assertThatThrownBy(() -> service.prepareEstimate(new OrderQuoteQueryContext(null, stock, null, BigDecimal.ZERO)))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(quotes, repository);
    }
}
