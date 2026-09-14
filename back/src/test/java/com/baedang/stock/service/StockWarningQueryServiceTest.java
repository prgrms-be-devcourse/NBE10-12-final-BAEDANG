package com.baedang.stock.service;

import com.baedang.stock.dto.StockDetailResponse;
import com.baedang.stock.entity.Stock;
import com.baedang.stock.port.StockWarnings;
import com.baedang.stock.port.SymbolInfoPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class StockWarningQueryServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 14);

    private final SymbolInfoPort port = mock(SymbolInfoPort.class);
    private final MutableClock clock = new MutableClock(
            TODAY.atStartOfDay().plusHours(10).toInstant(ZoneOffset.UTC));
    private final StockWarningQueryService service =
            new StockWarningQueryService(port, clock, Duration.ofMinutes(5));
    private Stock stock;

    @BeforeEach
    void setUp() {
        stock = mock(Stock.class);
        when(stock.getStockId()).thenReturn(1L);
        when(stock.getSymbol()).thenReturn("005930");
    }

    @Test
    void 유의기간에_포함된_경고만_반환한다() {
        // 오늘(2026-09-14) 기준: 어제 시작해 내일 끝나는 경고는 활성,
        // 이미 끝난 경고와 아직 시작 안 한 경고는 비활성이다.
        when(port.fetchStockWarnings("005930")).thenReturn(new StockWarnings("005930", List.of(
                warning("OVERHEATED", TODAY.minusDays(1), TODAY.plusDays(1)),
                warning("INVESTMENT_WARNING", TODAY.minusDays(10), TODAY.minusDays(1)),
                warning("VI_STATIC", TODAY.plusDays(1), TODAY.plusDays(5)))));

        StockWarningQueryService.WarningSnapshot snapshot = service.currentWarnings(stock);

        assertThat(snapshot.status()).isEqualTo(StockDetailResponse.WarningStatus.AVAILABLE);
        assertThat(snapshot.warnings())
                .extracting(StockDetailResponse.Warning::type)
                .containsExactly("OVERHEATED");
    }

    @Test
    void 시작일과_종료일_당일은_포함하고_기간이_없으면_활성으로_본다() {
        when(port.fetchStockWarnings("005930")).thenReturn(new StockWarnings("005930", List.of(
                warning("START_TODAY", TODAY, TODAY.plusDays(3)),
                warning("END_TODAY", TODAY.minusDays(3), TODAY),
                warning("NO_PERIOD", null, null))));

        StockWarningQueryService.WarningSnapshot snapshot = service.currentWarnings(stock);

        assertThat(snapshot.warnings())
                .extracting(StockDetailResponse.Warning::type)
                .containsExactly("START_TODAY", "END_TODAY", "NO_PERIOD");
        assertThat(snapshot.warnings())
                .extracting(StockDetailResponse.Warning::label)
                .containsOnly("거래유의종목");
    }

    @Test
    void TTL_안에서는_외부를_다시_호출하지_않고_지나면_다시_호출한다() {
        when(port.fetchStockWarnings("005930"))
                .thenReturn(new StockWarnings("005930", List.of(warning("OVERHEATED", null, null))));

        service.currentWarnings(stock);
        clock.advance(Duration.ofMinutes(4));
        service.currentWarnings(stock);
        verify(port, times(1)).fetchStockWarnings("005930");

        clock.advance(Duration.ofMinutes(2));
        service.currentWarnings(stock);
        verify(port, times(2)).fetchStockWarnings("005930");
    }

    @Test
    void 캐시된_경고도_읽는_시점_날짜로_활성_여부를_다시_판정한다() {
        // 자정을 넘겨 종료일이 지나면, 캐시가 아직 살아 있어도 배지가 사라져야 한다.
        when(port.fetchStockWarnings("005930")).thenReturn(new StockWarnings("005930", List.of(
                warning("OVERHEATED", TODAY, TODAY))));
        clock.setNearMidnight(TODAY);

        assertThat(service.currentWarnings(stock).warnings()).hasSize(1);

        // TTL(5분) 안에서 자정만 넘긴다 — 외부를 다시 부르지 않고 날짜만 바뀌어야 한다.
        clock.advance(Duration.ofMinutes(4));

        assertThat(service.currentWarnings(stock).warnings()).isEmpty();
        verify(port, times(1)).fetchStockWarnings("005930");
    }

    @Test
    void 조회_실패는_유의사항없음이_아니라_UNAVAILABLE이다() {
        when(port.fetchStockWarnings("005930")).thenThrow(new IllegalStateException("network"));

        StockWarningQueryService.WarningSnapshot snapshot = service.currentWarnings(stock);

        assertThat(snapshot.warnings()).isEmpty();
        assertThat(snapshot.status()).isEqualTo(StockDetailResponse.WarningStatus.UNAVAILABLE);
    }

    @Test
    void 한번_확인한_뒤의_실패는_마지막으로_확인한_유의사항을_유지한다() {
        when(port.fetchStockWarnings("005930"))
                .thenReturn(new StockWarnings("005930", List.of(warning("OVERHEATED", null, null))))
                .thenThrow(new IllegalStateException("network"));

        service.currentWarnings(stock);
        clock.advance(Duration.ofMinutes(6));

        StockWarningQueryService.WarningSnapshot snapshot = service.currentWarnings(stock);

        assertThat(snapshot.status()).isEqualTo(StockDetailResponse.WarningStatus.AVAILABLE);
        assertThat(snapshot.warnings())
                .extracting(StockDetailResponse.Warning::type)
                .containsExactly("OVERHEATED");
    }

    @Test
    void 실패한_조회는_성공_캐시로_남지_않는다() {
        when(port.fetchStockWarnings("005930"))
                .thenThrow(new IllegalStateException("network"))
                .thenReturn(new StockWarnings("005930", List.of(warning("OVERHEATED", null, null))));

        assertThat(service.currentWarnings(stock).status())
                .isEqualTo(StockDetailResponse.WarningStatus.UNAVAILABLE);
        assertThat(service.currentWarnings(stock).status())
                .isEqualTo(StockDetailResponse.WarningStatus.AVAILABLE);
        assertThat(service.currentWarnings(stock).warnings()).hasSize(1);
        verify(port, times(2)).fetchStockWarnings("005930");
    }

    @Test
    void 빈_경고_목록은_성공으로_기록해_재호출하지_않는다() {
        when(port.fetchStockWarnings("005930")).thenReturn(new StockWarnings("005930", List.of()));

        service.currentWarnings(stock);
        clock.advance(Duration.ofMinutes(1));
        StockWarningQueryService.WarningSnapshot snapshot = service.currentWarnings(stock);

        assertThat(snapshot.status()).isEqualTo(StockDetailResponse.WarningStatus.AVAILABLE);
        assertThat(snapshot.warnings()).isEmpty();
        verify(port, times(1)).fetchStockWarnings("005930");
    }

    @Test
    void 빈_타입의_경고는_배지로_내보내지_않는다() {
        when(port.fetchStockWarnings("005930")).thenReturn(new StockWarnings("005930", List.of(
                warning(" ", null, null),
                warning(null, null, null),
                warning("OVERHEATED", null, null))));

        assertThat(service.currentWarnings(stock).warnings())
                .extracting(StockDetailResponse.Warning::type)
                .containsExactly("OVERHEATED");
    }

    @Test
    void 캐시_TTL은_양수여야_한다() {
        assertThatThrownBy(() -> new StockWarningQueryService(port, clock, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StockWarningQueryService(port, clock, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private StockWarnings.StockWarning warning(String type, LocalDate start, LocalDate end) {
        return new StockWarnings.StockWarning(type, "KRX", start, end);
    }

    /** TTL 경계와 자정 넘김을 결정적으로 검증하기 위한 테스트용 시계. */
    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        /** 자정 직전으로 옮긴다 — TTL 안에서 날짜만 넘기는 경계를 만든다. */
        private void setNearMidnight(LocalDate date) {
            instant = date.plusDays(1).atStartOfDay().minusMinutes(2).toInstant(ZoneOffset.UTC);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
