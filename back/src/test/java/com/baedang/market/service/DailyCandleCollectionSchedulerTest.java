package com.baedang.market.service;

import com.baedang.stock.entity.MarketCountry;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.concurrent.Executor;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.assertj.core.api.Assertions.assertThat;

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

    @Test
    void 미국_수집은_뉴욕_현지_16시10분에_실행한다() throws NoSuchMethodException {
        Scheduled scheduled = DailyCandleCollectionScheduler.class
                .getMethod("collectUs")
                .getAnnotation(Scheduled.class);

        assertThat(scheduled.cron()).isEqualTo("0 10 16 * * MON-FRI");
        assertThat(scheduled.zone()).isEqualTo("America/New_York");
    }
}
