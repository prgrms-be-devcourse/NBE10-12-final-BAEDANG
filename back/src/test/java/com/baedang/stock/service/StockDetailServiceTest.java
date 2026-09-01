package com.baedang.stock.service;

import com.baedang.global.error.BusinessException;
import com.baedang.global.error.ErrorCode;
import com.baedang.market.entity.QuoteSnapshot;
import com.baedang.market.repository.QuoteSnapshotRepository;
import com.baedang.stock.dto.StockDetailResponse;
import com.baedang.stock.entity.ListingStatus;
import com.baedang.stock.entity.MarketCountry;
import com.baedang.stock.entity.Stock;
import com.baedang.stock.entity.StockCategory;
import com.baedang.stock.repository.StockRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class StockDetailServiceTest {

    private final StockRepository stockRepository = mock(StockRepository.class);
    private final QuoteSnapshotRepository quoteSnapshotRepository = mock(QuoteSnapshotRepository.class);
    private final QuoteRealtimePolicy quoteRealtimePolicy = mock(QuoteRealtimePolicy.class);
    private final StockOnDemandQuoteService stockOnDemandQuoteService = mock(StockOnDemandQuoteService.class);
    private final StockDetailService service =
            new StockDetailService(stockRepository, quoteSnapshotRepository, quoteRealtimePolicy, stockOnDemandQuoteService);
    private Stock stock;

    @BeforeEach
    void setUp() {
        // 온디맨드 갱신은 별도 StockOnDemandQuoteServiceTest에서 검증한다 — 여기서는
        // "넘겨받은 시세를 그대로 돌려준다"로 고정해 기존 시나리오에 영향이 없게 한다.
        when(stockOnDemandQuoteService.ensureQuote(any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(1));
        stock = mock(Stock.class);
        when(stock.getStockId()).thenReturn(1L);
        when(stock.getSymbol()).thenReturn("ABC");
        when(stock.getName()).thenReturn("테스트 종목");
        when(stock.getEnglishName()).thenReturn("Test Stock");
        when(stock.getMarket()).thenReturn("KOSPI");
        when(stock.getMarketCountry()).thenReturn(MarketCountry.KR);
        when(stock.getCurrency()).thenReturn("KRW");
        when(stock.getIsinCode()).thenReturn("KR0000000001");
        when(stock.getStockCategory()).thenReturn(StockCategory.INDIVIDUAL);
        when(stock.getIsDividend()).thenReturn(false);
        when(stock.getSharesOutstanding()).thenReturn(new BigDecimal("1000"));
        when(stock.getListDate()).thenReturn(LocalDate.parse("2020-01-01"));
        when(stock.getIsRanked()).thenReturn(true);
        when(stock.getListingStatus()).thenReturn(ListingStatus.ACTIVE);
        when(stockRepository.findBySymbolIgnoreCaseAndMarketCountry("abc", MarketCountry.KR))
                .thenReturn(Optional.of(stock));
    }

    @Test
    void 시장국가와_심볼로_조회하고_파생값을_문자열로_반환한다() {
        QuoteSnapshot quote = quote("120", "100");
        when(quoteSnapshotRepository.findById(1L)).thenReturn(Optional.of(quote));
        when(quoteRealtimePolicy.isRealtime(MarketCountry.KR, quote)).thenReturn(true);
        when(quoteRealtimePolicy.isMarketOpen(MarketCountry.KR)).thenReturn(true);

        StockDetailResponse result = service.getDetail("abc", "kr");

        assertThat(result.price().lastPrice()).isEqualTo("120");
        assertThat(result.price().changeAmount()).isEqualTo("20");
        assertThat(result.price().changeRate()).isEqualTo("0.2");
        assertThat(result.info().marketCap()).isEqualTo("120000");
        assertThat(result.tradable()).isTrue();
    }

    @Test
    void 시세가_없어도_메타데이터를_반환하고_거래불가로_표시한다() {
        when(quoteSnapshotRepository.findById(1L)).thenReturn(Optional.empty());
        when(quoteRealtimePolicy.isMarketOpen(MarketCountry.KR)).thenReturn(true);

        StockDetailResponse result = service.getDetail("abc", "KR");

        assertThat(result.price().lastPrice()).isNull();
        assertThat(result.price().realtime()).isFalse();
        assertThat(result.tradable()).isFalse();
        assertThat(result.tradableReason()).isEqualTo("QUOTE_NOT_FOUND");
    }

    @Test
    void 거래정지_사유는_장상태와_시세보다_우선한다() {
        when(stock.getIsSuspended()).thenReturn(true);
        when(quoteSnapshotRepository.findById(1L)).thenReturn(Optional.empty());

        StockDetailResponse result = service.getDetail("abc", "KR");

        assertThat(result.tradableReason()).isEqualTo("SUSPENDED");
        verify(quoteRealtimePolicy, never()).isMarketOpen(any());
    }

    @Test
    void 상위_100_밖의_종목은_최신_시세가_있어도_실시간이_아니다() {
        when(stock.getIsRanked()).thenReturn(false);
        QuoteSnapshot quote = quote("120", "100");
        when(quoteSnapshotRepository.findById(1L)).thenReturn(Optional.of(quote));

        StockDetailResponse result = service.getDetail("abc", "KR");

        assertThat(result.price().realtime()).isFalse();
        assertThat(result.tradableReason()).isEqualTo("NOT_IN_UNIVERSE");
        verify(quoteRealtimePolicy, never()).isRealtime(any(), any());
    }

    @Test
    void 경고종목은_범용_투자경고를_반환한다() {
        when(stock.getIsWarned()).thenReturn(true);
        when(quoteSnapshotRepository.findById(1L)).thenReturn(Optional.empty());
        when(quoteRealtimePolicy.isMarketOpen(MarketCountry.KR)).thenReturn(true);

        StockDetailResponse result = service.getDetail("abc", "KR");

        assertThat(result.warnings()).containsExactly(
                new StockDetailResponse.Warning("INVESTMENT_WARNING", "투자경고"));
    }

    @Test
    void 지원하지_않는_시장국가는_거절한다() {
        assertThatThrownBy(() -> service.getDetail("ABC", "JP"))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
        verifyNoInteractions(stockRepository);
    }

    @Test
    void 종목이_없으면_STOCK_NOT_FOUND를_던진다() {
        when(stockRepository.findBySymbolIgnoreCaseAndMarketCountry("NONE", MarketCountry.US))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getDetail("NONE", "US"))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode())
                .isEqualTo(ErrorCode.STOCK_NOT_FOUND);
    }

    private QuoteSnapshot quote(String lastPrice, String prevClose) {
        QuoteSnapshot quote = mock(QuoteSnapshot.class);
        BigDecimal last = new BigDecimal(lastPrice);
        BigDecimal previous = new BigDecimal(prevClose);
        when(quote.getLastPrice()).thenReturn(last);
        when(quote.getPrevClose()).thenReturn(previous);
        when(quote.changeRate()).thenReturn(last.subtract(previous).divide(previous, 6, RoundingMode.HALF_UP));
        when(quote.getQuoteAt()).thenReturn(OffsetDateTime.parse("2026-08-27T12:00:00+09:00"));
        return quote;
    }
}
