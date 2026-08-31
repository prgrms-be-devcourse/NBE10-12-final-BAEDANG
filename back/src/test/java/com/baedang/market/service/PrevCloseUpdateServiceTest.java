package com.baedang.market.service;

import com.baedang.market.model.PrevCloseUpdateResult;
import com.baedang.market.repository.PrevCloseUpdateRepository;
import com.baedang.stock.entity.MarketCountry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PrevCloseUpdateServiceTest {

    private PrevCloseUpdateRepository repository;
    private PrevCloseUpdateService service;

    @BeforeEach
    void setUp() {
        repository = mock(PrevCloseUpdateRepository.class);
        service = new PrevCloseUpdateService(repository);
    }

    @Test
    void 시장별_일괄_갱신_결과를_반환한다() {
        PrevCloseUpdateResult expected = new PrevCloseUpdateResult(100, 98, 3);
        when(repository.update(MarketCountry.KR)).thenReturn(expected);

        PrevCloseUpdateResult actual = service.update(MarketCountry.KR);

        assertThat(actual).isEqualTo(expected);
        assertThat(actual.skippedCount()).isEqualTo(2);
        verify(repository).update(MarketCountry.KR);
    }

    @Test
    void 시장이_null이면_저장소를_호출하지_않는다() {
        assertThatNullPointerException()
                .isThrownBy(() -> service.update(null))
                .withMessage("marketCountry must not be null");

        verifyNoInteractions(repository);
    }
}
