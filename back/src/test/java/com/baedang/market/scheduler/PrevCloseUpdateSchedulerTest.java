package com.baedang.market.scheduler;
import com.baedang.market.service.PrevCloseUpdateService;
import com.baedang.stock.entity.MarketCountry;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
class PrevCloseUpdateSchedulerTest {
    @Test void startsWithoutDependingOnMissedCronAndRetriesBothMarkets() throws Exception {
        PrevCloseUpdateService service = mock(PrevCloseUpdateService.class);
        doThrow(new IllegalStateException()).when(service).update(MarketCountry.KR);
        new PrevCloseUpdateScheduler(service).recover();
        verify(service).update(MarketCountry.US);
        Scheduled schedule = PrevCloseUpdateScheduler.class.getMethod("recover").getAnnotation(Scheduled.class);
        assertThat(schedule.initialDelayString()).isEqualTo("${trading.reference-recovery.initial-delay:5s}");
        assertThat(schedule.fixedDelayString()).isEqualTo("${trading.reference-recovery.interval:1m}");
        assertThat(schedule.scheduler()).isEqualTo("referenceRecoveryScheduler");
    }
}
