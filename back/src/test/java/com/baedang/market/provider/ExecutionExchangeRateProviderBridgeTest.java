package com.baedang.market.provider;

import com.baedang.global.error.BusinessException;
import com.baedang.global.error.ErrorCode;
import com.baedang.market.entity.ExchangeRate;
import com.baedang.market.port.ExecutionExchangeRateSnapshot;
import com.baedang.market.repository.ExchangeRateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ExecutionExchangeRateProviderBridgeTest {
    private static final OffsetDateTime AT = OffsetDateTime.parse("2026-09-04T01:00:00Z");
    private final ExchangeRateRepository repository = mock(ExchangeRateRepository.class);
    private final Clock clock = mock(Clock.class);
    private final com.baedang.market.service.ExchangeRateLoadService loadService = mock(com.baedang.market.service.ExchangeRateLoadService.class);
    private ExecutionExchangeRateProviderBridge provider;

    @BeforeEach
    void setup() {
        when(clock.instant()).thenReturn(AT.toInstant());
        provider = new ExecutionExchangeRateProviderBridge(repository, clock, loadService);
    }

    @Test
    void DB_적재후_60초가_지나도_원본유효기간_안에서는_정밀도를_보존해_사용한다() {
        when(clock.instant()).thenReturn(AT.plusMinutes(5).toInstant());
        given(row("1383.601234", AT, AT.plusHours(1), AT));
        ExecutionExchangeRateSnapshot snapshot = provider.currentUsdKrwSnapshot();
        assertThat(snapshot.rate()).isEqualTo(new BigDecimal("1383.601234"));
        assertThat(snapshot.fetchedAt()).isEqualTo(AT);
        assertThat(snapshot.validFrom()).isEqualTo(AT);
        assertThat(snapshot.validUntil()).isEqualTo(AT.plusHours(1));
    }

    @Test
    void 매조회는_DB의_새환율을_읽고_이미준비한_스냅샷은_바뀌지않는다() {
        given(row("1400", AT, AT.plusHours(1), AT));
        ExecutionExchangeRateSnapshot first = provider.currentUsdKrwSnapshot();
        given(row("1300", AT, AT.plusHours(2), AT));
        assertThat(provider.currentUsdKrwRate()).isEqualByComparingTo("1300");
        assertThat(first.rate()).isEqualByComparingTo("1400");
        verify(repository, times(2)).findTopByBaseCurrencyAndQuoteCurrencyOrderByValidFromDesc("USD", "KRW");
    }

    @Test
    void 유효기한_직전은_허용하고_기한에_도달하면_거절한다() {
        given(row("1400", AT, AT.plusMinutes(1), AT));
        when(clock.instant()).thenReturn(AT.plusSeconds(59).toInstant(), AT.plusMinutes(1).toInstant());
        assertThat(provider.currentUsdKrwSnapshot()).isNotNull();
        assertUnavailable();
    }

    @ParameterizedTest
    @ValueSource(strings = {"absent", "missingUntil", "missingFrom", "missingCollected", "future",
            "futureCollected", "expired", "inverted", "nullRate", "zero", "negative", "scale", "overflow"})
    void 누락_만료_미래_잘못된DB값은_대체조회없이_거절한다(String kind) {
        OffsetDateTime from = AT;
        OffsetDateTime until = AT.plusHours(1);
        OffsetDateTime collected = AT;
        String rate = "1400";
        switch (kind) {
            case "missingUntil" -> until = null;
            case "missingFrom" -> from = null;
            case "missingCollected" -> collected = null;
            case "future" -> from = AT.plusSeconds(1);
            case "futureCollected" -> collected = AT.plusSeconds(1);
            case "expired" -> until = AT;
            case "inverted" -> until = AT.minusSeconds(1);
            case "nullRate" -> rate = null;
            case "zero" -> rate = "0";
            case "negative" -> rate = "-1";
            case "scale" -> rate = "1400.1234567";
            case "overflow" -> rate = "10000000000000";
        }
        when(repository.findTopByBaseCurrencyAndQuoteCurrencyOrderByValidFromDesc("USD", "KRW"))
                .thenReturn(kind.equals("absent") ? Optional.empty() : Optional.of(row(rate, from, until, collected)));
        assertUnavailable();
    }

    @Test
    void 시장가_복구는_만료행을_갱신하고_DB에_저장된_새환율을_사용한다() {
        given(row("1400", AT.minusHours(1), AT, AT.minusHours(1)));
        when(loadService.syncExchangeRate()).thenAnswer(invocation -> {
            given(row("1401", AT, AT.plusHours(1), AT));
            return true;
        });
        provider.refreshUnavailableForMarketOrder();
        assertThat(provider.currentUsdKrwSnapshot().rate()).isEqualByComparingTo("1401");
        verify(loadService).syncExchangeRate();
    }

    @Test
    void 시장가_복구도_유효한_행이_있으면_외부조회하지_않는다() {
        given(row("1400", AT, AT.plusHours(1), AT));
        provider.refreshUnavailableForMarketOrder();
        verifyNoInteractions(loadService);
    }

    @Test
    void 시장가_복구는_누락행에도_한번_시도하지만_실패를_환율오류로_변환한다() {
        when(loadService.syncExchangeRate()).thenThrow(new IllegalStateException("upstream failed"));
        assertThatThrownBy(provider::refreshUnavailableForMarketOrder)
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.EXCHANGE_RATE_NOT_FOUND));
        verify(loadService).syncExchangeRate();
    }

    @Test
    void 성공응답이어도_DB환율이_계속_만료상태이면_거절한다() {
        given(row("1400", AT.minusHours(1), AT, AT.minusHours(1)));
        when(loadService.syncExchangeRate()).thenReturn(true);
        provider.refreshUnavailableForMarketOrder();
        assertUnavailable();
    }

    private void given(ExchangeRate row) {
        when(repository.findTopByBaseCurrencyAndQuoteCurrencyOrderByValidFromDesc("USD", "KRW")).thenReturn(Optional.of(row));
    }

    private ExchangeRate row(String rate, OffsetDateTime from, OffsetDateTime until, OffsetDateTime collected) {
        return new ExchangeRate("USD", "KRW", rate == null ? null : new BigDecimal(rate),
                new BigDecimal("1200"), from, until, collected);
    }

    private void assertUnavailable() {
        assertThatThrownBy(provider::currentUsdKrwSnapshot).isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.EXCHANGE_RATE_NOT_FOUND));
    }
}
