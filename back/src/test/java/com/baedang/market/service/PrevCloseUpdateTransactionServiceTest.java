package com.baedang.market.service;

import com.baedang.market.model.PrevCloseUpdateResult;
import com.baedang.market.repository.PrevCloseUpdateRepository;
import com.baedang.stock.entity.MarketCountry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class PrevCloseUpdateTransactionServiceTest {

    private PrevCloseUpdateRepository repository;
    private PrevCloseUpdateTransactionService service;

    @BeforeEach
    void setUp() {
        repository = mock(PrevCloseUpdateRepository.class);
        service = new PrevCloseUpdateTransactionService(repository);
    }

    @Test
    void 직전_거래일이_있으면_해당_날짜의_일봉만_사용한다() {
        LocalDate tradeDate = LocalDate.of(2026, 8, 28);
        PrevCloseUpdateResult expected = new PrevCloseUpdateResult(100, 99, 1);
        when(repository.updateForTradeDate(MarketCountry.KR, tradeDate)).thenReturn(expected);

        PrevCloseUpdateResult actual = service.update(MarketCountry.KR, Optional.of(tradeDate));

        assertThat(actual).isEqualTo(expected);
        verify(repository).updateForTradeDate(MarketCountry.KR, tradeDate);
        verifyNoMoreInteractions(repository);
    }

    @Test
    void 직전_거래일이_없으면_lastPrice로_전체_폴백한다() {
        PrevCloseUpdateResult expected = new PrevCloseUpdateResult(100, 100, 100);
        when(repository.updateFromLastPrice(MarketCountry.US)).thenReturn(expected);

        PrevCloseUpdateResult actual = service.update(MarketCountry.US, Optional.empty());

        assertThat(actual).isEqualTo(expected);
        verify(repository).updateFromLastPrice(MarketCountry.US);
        verifyNoMoreInteractions(repository);
    }

    @Test
    void 외부_캘린더_오케스트레이션과_DB_트랜잭션_경계가_분리되어_있다()
            throws NoSuchMethodException {
        assertThat(PrevCloseUpdateService.class.isAnnotationPresent(Transactional.class)).isFalse();
        assertThat(PrevCloseUpdateService.class
                .getMethod("update", MarketCountry.class)
                .isAnnotationPresent(Transactional.class)).isFalse();
        assertThat(PrevCloseUpdateTransactionService.class
                .getMethod("update", MarketCountry.class, Optional.class)
                .isAnnotationPresent(Transactional.class)).isTrue();
    }
}
