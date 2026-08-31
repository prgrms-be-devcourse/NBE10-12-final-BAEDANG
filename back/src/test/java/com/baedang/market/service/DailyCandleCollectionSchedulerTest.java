package com.baedang.market.service;

import com.baedang.stock.entity.MarketCountry;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.concurrent.Executor;
import java.util.Arrays;

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
    void 미국_수집은_뉴욕_현지_16시10분부터_30분마다_재시도한다() throws NoSuchMethodException {
        Scheduled[] schedules = DailyCandleCollectionScheduler.class
                .getMethod("collectUs")
                .getAnnotationsByType(Scheduled.class);

        assertThat(Arrays.stream(schedules).map(Scheduled::cron))
                .containsExactlyInAnyOrder(
                        "0 10,40 16 * * MON-FRI",
                        "0 10 17 * * MON-FRI");
        assertThat(schedules).allMatch(schedule -> schedule.zone().equals("America/New_York"));
    }

    @Test
    void 국내_수집은_15시40분부터_30분마다_재시도한다() throws NoSuchMethodException {
        Scheduled[] schedules = DailyCandleCollectionScheduler.class
                .getMethod("collectKr")
                .getAnnotationsByType(Scheduled.class);

        assertThat(Arrays.stream(schedules).map(Scheduled::cron))
                .containsExactlyInAnyOrder(
                        "0 40 15 * * MON-FRI",
                        "0 10,40 16 * * MON-FRI",
                        "0 10 17 * * MON-FRI");
        assertThat(schedules).allMatch(schedule -> schedule.zone().equals("Asia/Seoul"));
    }
}
