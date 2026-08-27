package com.baedang.stock.service;

import com.baedang.global.error.BusinessException;
import com.baedang.global.error.ErrorCode;
import com.baedang.market.entity.QuoteSnapshot;
import com.baedang.market.port.MarketSessionProvider;
import com.baedang.market.repository.QuoteSnapshotRepository;
import com.baedang.stock.dto.RankingResponse;
import com.baedang.stock.entity.MarketCountry;
import com.baedang.stock.entity.Stock;
import com.baedang.stock.entity.StockCategory;
import com.baedang.stock.repository.StockRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class RankingServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-27T03:00:00Z");

    @Mock
    private StockRepository stockRepository;

    @Mock
    private QuoteSnapshotRepository quoteSnapshotRepository;

    @Mock
    private MarketSessionProvider marketSessionProvider;

    @Mock
    private Clock clock;

    @InjectMocks
    private RankingService rankingService;

    @Test
    @DisplayName("국내 랭킹과 시세 파생값을 반환")
    void t1() {
        Stock stock = mock(Stock.class);
        QuoteSnapshot quoteSnapshot = mock(QuoteSnapshot.class);

        when(stockRepository.findRankedByMarketCountry(
                MarketCountry.KR,
                PageRequest.of(0, 21)
        )).thenReturn(List.of(stock));

        when(stock.getStockId()).thenReturn(1L);
        when(stock.getRankNo()).thenReturn(1);
        when(stock.getSymbol()).thenReturn("005930");
        when(stock.getName()).thenReturn("삼성전자");
        when(stock.getMarket()).thenReturn("KOSPI");
        when(stock.getStockCategory()).thenReturn(StockCategory.INDIVIDUAL);
        when(stock.getIsDividend()).thenReturn(false);
        when(stock.getLeverageFactor()).thenReturn(null);
        when(stock.getCurrency()).thenReturn("KRW");
        when(stock.getTradingAmount()).thenReturn(new BigDecimal("1240000000000"));
        when(stock.getMarketCountry()).thenReturn(MarketCountry.KR);
        when(quoteSnapshotRepository.findByStockIdIn(List.of(1L))).thenReturn(List.of(quoteSnapshot));
        when(quoteSnapshot.getStockId()).thenReturn(1L);
        when(quoteSnapshot.getLastPrice()).thenReturn(new BigDecimal("241500"));
        when(quoteSnapshot.getPrevClose()).thenReturn(new BigDecimal("236050"));
        when(quoteSnapshot.changeRate()).thenReturn(new BigDecimal("0.023069"));
        when(quoteSnapshot.getQuoteAt()).thenReturn(OffsetDateTime.parse("2026-08-27T12:00:00+09:00"));

        when(clock.instant()).thenReturn(NOW);
        when(marketSessionProvider.isOpen(MarketCountry.KR, NOW)).thenReturn(true);

        RankingResponse response = rankingService.getRankings("KR", 20, null);

        assertThat(response.items()).hasSize(1);

        RankingResponse.Item item = response.items().get(0);

        assertThat(item.rank()).isEqualTo(1);
        assertThat(item.symbol()).isEqualTo("005930");
        assertThat(item.lastPrice()).isEqualTo("241500");
        assertThat(item.prevClose()).isEqualTo("236050");
        assertThat(item.changeAmount()).isEqualTo("5450");
        assertThat(item.changeRate()).isEqualTo("0.023069");
        assertThat(item.tradingAmount()).isEqualTo("1240000000000");
        assertThat(item.realtime()).isTrue();

        assertThat(response.hasNext()).isFalse();
        assertThat(response.nextCursor()).isNull();
    }

    @Test
    @DisplayName("size보다 한 건 더 있으면 다음 cursor 반환")
    void t2() {
        Stock first = rankedStock(1L, 1, "005930", new BigDecimal("300"));
        Stock second = rankedStock(2L, 2, "000660", new BigDecimal("200"));
        Stock extra = mock(Stock.class);
        when(quoteSnapshotRepository.findByStockIdIn(List.of(1L, 2L))).thenReturn(List.of());
        when(stockRepository.findRankedByMarketCountry(
                MarketCountry.KR,
                PageRequest.of(0, 3)
        )).thenReturn(List.of(first, second, extra));

        RankingResponse response = rankingService.getRankings("KR", 2, null);
        assertThat(response.items()).hasSize(2);
        assertThat(response.hasNext()).isTrue();
        assertThat(response.nextCursor()).isNotBlank();
        verify(stockRepository).findRankedByMarketCountry(
                MarketCountry.KR,
                PageRequest.of(0, 3));
    }

    @Test
    @DisplayName("cursor 요청은 마지막 거래대금, stockId 이후를 조회")
    void t3() {
        String cursor = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("200:2".getBytes(StandardCharsets.UTF_8));

        when(stockRepository.findRankedAfterCursor(
                MarketCountry.KR,
                new BigDecimal("200"),
                2L,
                PageRequest.of(0, 21)
        )).thenReturn(List.of());

        RankingResponse response = rankingService.getRankings("KR", 20, cursor);

        assertThat(response.items()).isEmpty();
        assertThat(response.hasNext()).isFalse();
        assertThat(response.nextCursor()).isNull();

        verify(stockRepository).findRankedAfterCursor(
                MarketCountry.KR,
                new BigDecimal("200"),
                2L,
                PageRequest.of(0, 21)
        );

        verifyNoInteractions(quoteSnapshotRepository);
    }

    @Test
    @DisplayName("지원하지 않는 market이면 예외 발생")
    void t4() {
        assertThatThrownBy(() -> rankingService.getRankings("JP", 20, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    @DisplayName("size가 허용 범위를 벗어나면 예외 발생")
    void t5() {
        assertThatThrownBy(() -> rankingService.getRankings("KR", 101, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidCursors")
    @DisplayName("잘못된 cursor면 예외 발생")
    void t6(String caseName, String cursor) {
        assertThatThrownBy(() -> rankingService.getRankings("KR", 20, cursor))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_CURSOR);
    }

    private static Stream<Arguments> invalidCursors() {
        return Stream.of(
                Arguments.of("Base64 형식 오류", "%"),
                Arguments.of("구분자 없음", encodeCursorValue("200")),
                Arguments.of("구분자가 여러 개", encodeCursorValue("200:2:3")),
                Arguments.of("거래대금이 비어 있음", encodeCursorValue(":2")),
                Arguments.of("stockId가 비어 있음", encodeCursorValue("200:")),
                Arguments.of("거래대금이 숫자가 아님", encodeCursorValue("amount:2")),
                Arguments.of("stockId가 숫자가 아님", encodeCursorValue("200:stock")),
                Arguments.of("거래대금이 음수", encodeCursorValue("-1:2")),
                Arguments.of("stockId가 0", encodeCursorValue("200:0")),
                Arguments.of("stockId가 long 범위 초과", encodeCursorValue("200:9223372036854775808"))

        );
    }

    private static String encodeCursorValue(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private Stock rankedStock(
            long stockId,
            int rankNo,
            String symbol,
            BigDecimal tradingAmount
    ) {
        Stock stock = mock(Stock.class);

        when(stock.getStockId()).thenReturn(stockId);
        when(stock.getRankNo()).thenReturn(rankNo);
        when(stock.getSymbol()).thenReturn(symbol);
        when(stock.getName()).thenReturn(symbol);
        when(stock.getMarket()).thenReturn("KOSPI");
        when(stock.getStockCategory())
                .thenReturn(StockCategory.INDIVIDUAL);
        when(stock.getIsDividend()).thenReturn(false);
        when(stock.getCurrency()).thenReturn("KRW");
        when(stock.getTradingAmount()).thenReturn(tradingAmount);

        return stock;
    }
}
