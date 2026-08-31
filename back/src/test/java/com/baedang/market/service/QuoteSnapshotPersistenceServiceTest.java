package com.baedang.market.service;

import com.baedang.market.entity.QuoteSnapshot;
import com.baedang.market.port.PriceQuote;
import com.baedang.market.repository.QuoteSnapshotRepository;
import com.baedang.stock.entity.Stock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.only;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class QuoteSnapshotPersistenceServiceTest {

    private static final OffsetDateTime COLLECTED_AT =
            OffsetDateTime.parse("2026-08-28T00:30:01Z");

    @Mock
    private QuoteSnapshotRepository quoteSnapshotRepository;

    private QuoteSnapshotPersistenceService persistenceService;

    @BeforeEach
    void setUp() {
        persistenceService = new QuoteSnapshotPersistenceService(quoteSnapshotRepository);
    }

    @Test
    @DisplayName("신규 스냅샷에 검증된 가격·통화·시각을 저장한다")
    void 신규_스냅샷을_저장한다() {
        Stock stock = stock(1L, "005930", "KRW");
        OffsetDateTime quoteAt = OffsetDateTime.parse("2026-08-28T09:30:00+09:00");
        PriceQuote quote = new PriceQuote("005930", new BigDecimal("70000"), quoteAt, "KRW");
        when(quoteSnapshotRepository.findByStockIdIn(List.of(1L))).thenReturn(List.of());

        int count = persistenceService.saveOrUpdate(List.of(stock), List.of(quote), COLLECTED_AT);

        assertThat(count).isEqualTo(1);
        ArgumentCaptor<QuoteSnapshot> captor = ArgumentCaptor.forClass(QuoteSnapshot.class);
        verify(quoteSnapshotRepository).save(captor.capture());
        QuoteSnapshot saved = captor.getValue();
        assertThat(saved.getStockId()).isEqualTo(1L);
        assertThat(saved.getLastPrice()).isEqualByComparingTo("70000");
        assertThat(saved.getCurrency()).isEqualTo("KRW");
        assertThat(saved.getQuoteAt()).isEqualTo(quoteAt);
        assertThat(saved.getCollectedAt()).isEqualTo(COLLECTED_AT);
    }

    @Test
    @DisplayName("기존 스냅샷의 가격·통화·시각을 갱신한다")
    void 기존_스냅샷을_갱신한다() {
        Stock stock = stock(1L, "AAPL", "USD");
        OffsetDateTime oldAt = OffsetDateTime.parse("2026-08-27T10:00:00Z");
        QuoteSnapshot existing = new QuoteSnapshot(
                1L, new BigDecimal("150"), "USD", oldAt, oldAt);
        OffsetDateTime newAt = OffsetDateTime.parse("2026-08-28T10:00:00Z");
        PriceQuote quote = new PriceQuote("AAPL", new BigDecimal("155.50"), newAt, "USD");
        when(quoteSnapshotRepository.findByStockIdIn(List.of(1L))).thenReturn(List.of(existing));

        int count = persistenceService.saveOrUpdate(List.of(stock), List.of(quote), COLLECTED_AT);

        assertThat(count).isEqualTo(1);
        assertThat(existing.getLastPrice()).isEqualByComparingTo("155.50");
        assertThat(existing.getCurrency()).isEqualTo("USD");
        assertThat(existing.getQuoteAt()).isEqualTo(newAt);
        assertThat(existing.getCollectedAt()).isEqualTo(COLLECTED_AT);
        verify(quoteSnapshotRepository, only()).findByStockIdIn(List.of(1L));
    }

    @Test
    @DisplayName("체결 시각이 없으면 스냅샷을 저장하지 않는다")
    void 체결_시각이_없으면_건너뛴다() {
        Stock stock = stock(1L, "005930", "KRW");
        PriceQuote quote = new PriceQuote("005930", new BigDecimal("70000"), null, "KRW");
        when(quoteSnapshotRepository.findByStockIdIn(List.of(1L))).thenReturn(List.of());

        int count = persistenceService.saveOrUpdate(List.of(stock), List.of(quote), COLLECTED_AT);

        assertThat(count).isZero();
        verify(quoteSnapshotRepository, only()).findByStockIdIn(List.of(1L));
    }

    @Test
    @DisplayName("시세 통화가 없거나 종목 통화와 다르면 저장하지 않는다")
    void 통화가_유효하지_않으면_건너뛴다() {
        Stock stock = stock(1L, "005930", "KRW");
        OffsetDateTime quoteAt = OffsetDateTime.parse("2026-08-28T09:30:00+09:00");
        PriceQuote missing = new PriceQuote("005930", new BigDecimal("70000"), quoteAt, null);
        PriceQuote mismatch = new PriceQuote("005930", new BigDecimal("150"), quoteAt, "USD");
        when(quoteSnapshotRepository.findByStockIdIn(List.of(1L))).thenReturn(List.of());

        int count = persistenceService.saveOrUpdate(
                List.of(stock), List.of(missing, mismatch), COLLECTED_AT);

        assertThat(count).isZero();
        verify(quoteSnapshotRepository, only()).findByStockIdIn(List.of(1L));
    }

    @Test
    @DisplayName("DB 적재 메서드만 트랜잭션을 연다")
    void 적재_메서드는_트랜잭션으로_실행한다() throws NoSuchMethodException {
        assertThat(QuoteSnapshotPersistenceService.class
                .getMethod("saveOrUpdate", List.class, List.class, OffsetDateTime.class)
                .isAnnotationPresent(Transactional.class))
                .isTrue();
    }

    private Stock stock(Long stockId, String symbol, String currency) {
        return mock(Stock.class, invocation -> switch (invocation.getMethod().getName()) {
            case "getStockId" -> stockId;
            case "getSymbol" -> symbol;
            case "getCurrency" -> currency;
            default -> org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation);
        });
    }
}
