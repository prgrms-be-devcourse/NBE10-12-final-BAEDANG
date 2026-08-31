package com.baedang.market.scheduler;

import com.baedang.global.error.BusinessException;
import com.baedang.global.error.ErrorCode;
import com.baedang.market.service.ExchangeRateLoadService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
public class ExchangeRateSchedulerTest {

    @Mock
    private ExchangeRateLoadService loadService;

    @Test
    @DisplayName("스케줄 실행 시 환율 적재를 호출한다")
    void t1() {
        ExchangeRateScheduler scheduler = new ExchangeRateScheduler(loadService);

        scheduler.collect();

        verify(loadService).syncExchangeRate();
    }

    @Test
    @DisplayName("환율 적재 실패가 스케줄러를 중단시키지 않는다")
    void t2() {
        ExchangeRateScheduler scheduler = new ExchangeRateScheduler(loadService);

        doThrow(new BusinessException(ErrorCode.TOSS_API_ERROR)).when(loadService).syncExchangeRate();

        assertThatCode(scheduler::collect).doesNotThrowAnyException();
    }
}
