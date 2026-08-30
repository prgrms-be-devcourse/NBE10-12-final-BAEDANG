package com.baedang.market.service;

import com.baedang.stock.entity.MarketCountry;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Executor;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class DailyCandleCollectionSchedulerTest {

    private final DailyCandleCollectionService service = mock(DailyCandleCollectionService.class);
    private final Executor directExecutor = Runnable::run;
    private final DailyCandleCollectionScheduler scheduler =
            new DailyCandleCollectionScheduler(service, directExecutor);

    @Test
    void 국내_수집을_전용_Executor에_제출한다() {
        scheduler.collectKr();
        verify(service).collect(MarketCountry.KR);
    }

    @Test
    void 미국_수집을_전용_Executor에_제출한다() {
        scheduler.collectUs();
        verify(service).collect(MarketCountry.US);
    }
}
