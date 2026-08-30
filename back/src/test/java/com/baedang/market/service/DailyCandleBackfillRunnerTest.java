package com.baedang.market.service;

import com.baedang.stock.entity.MarketCountry;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.concurrent.Executor;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;

class DailyCandleBackfillRunnerTest {

    @Test
    void 전용_Executor에서_KR_US_순서로_백필한다() {
        DailyCandleCollectionService service = mock(DailyCandleCollectionService.class);
        Executor directExecutor = Runnable::run;
        DailyCandleBackfillRunner runner = new DailyCandleBackfillRunner(service, directExecutor);

        runner.onApplicationReady();

        InOrder order = inOrder(service);
        order.verify(service).backfill(MarketCountry.KR);
        order.verify(service).backfill(MarketCountry.US);
    }
}
