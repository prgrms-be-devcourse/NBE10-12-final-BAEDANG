package com.baedang.market.service;

import com.baedang.market.model.PrevCloseUpdateResult;
import com.baedang.stock.entity.MarketCountry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(OutputCaptureExtension.class)
class PrevCloseUpdateServiceTest {

    private PreviousTradingDayResolver resolver;
    private PrevCloseUpdateTransactionService transactionService;
    private PrevCloseUpdateService service;

    @BeforeEach
    void setUp() {
        resolver = mock(PreviousTradingDayResolver.class);
        transactionService = mock(PrevCloseUpdateTransactionService.class);
        service = new PrevCloseUpdateService(resolver, transactionService);
    }

    @Test
    void 시장별_일괄_갱신_결과를_반환한다() {
        PrevCloseUpdateResult expected = new PrevCloseUpdateResult(100, 98, 3);
        LocalDate tradeDate = LocalDate.of(2026, 8, 28);
        when(resolver.resolve(MarketCountry.KR)).thenReturn(Optional.of(tradeDate));
        when(transactionService.update(MarketCountry.KR, Optional.of(tradeDate)))
                .thenReturn(expected);

        PrevCloseUpdateResult actual = service.update(MarketCountry.KR);

        assertThat(actual).isEqualTo(expected);
        assertThat(actual.skippedCount()).isEqualTo(2);
        verify(transactionService).update(MarketCountry.KR, Optional.of(tradeDate));
    }

    @Test
    void 직전_거래일을_확인할_수_없으면_lastPrice_폴백을_요청한다() {
        PrevCloseUpdateResult expected = new PrevCloseUpdateResult(100, 100, 100);
        when(resolver.resolve(MarketCountry.US)).thenReturn(Optional.empty());
        when(transactionService.update(MarketCountry.US, Optional.empty()))
                .thenReturn(expected);

        PrevCloseUpdateResult actual = service.update(MarketCountry.US);

        assertThat(actual).isEqualTo(expected);
        verify(transactionService).update(MarketCountry.US, Optional.empty());
    }

    @Test
    void 폴백이_전체_대상의_절반을_초과하면_WARN_로그를_남긴다(CapturedOutput output) {
        LocalDate tradeDate = LocalDate.of(2026, 8, 28);
        when(resolver.resolve(MarketCountry.KR)).thenReturn(Optional.of(tradeDate));
        when(transactionService.update(MarketCountry.KR, Optional.of(tradeDate)))
                .thenReturn(new PrevCloseUpdateResult(100, 100, 51));

        service.update(MarketCountry.KR);

        assertThat(output.getOut())
                .contains("폴백 비율 과다")
                .contains("fallback=51/100")
                .contains("expectedTradeDate=2026-08-28");
    }

    @Test
    void 폴백이_전체_대상의_정확히_절반이면_과다_WARN을_남기지_않는다(CapturedOutput output) {
        LocalDate tradeDate = LocalDate.of(2026, 8, 28);
        when(resolver.resolve(MarketCountry.KR)).thenReturn(Optional.of(tradeDate));
        when(transactionService.update(MarketCountry.KR, Optional.of(tradeDate)))
                .thenReturn(new PrevCloseUpdateResult(100, 100, 50));

        service.update(MarketCountry.KR);

        assertThat(output.getOut()).doesNotContain("폴백 비율 과다");
    }

    @Test
    void 시장이_null이면_저장소를_호출하지_않는다() {
        assertThatNullPointerException()
                .isThrownBy(() -> service.update(null))
                .withMessage("marketCountry must not be null");

        verifyNoInteractions(resolver, transactionService);
    }
}
