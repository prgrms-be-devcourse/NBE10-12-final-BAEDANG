package com.baedang.stock.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;

import com.baedang.global.error.BusinessException;
import com.baedang.global.error.ErrorCode;
import com.baedang.stock.service.StockFinancialSyncService;
import com.baedang.stock.service.StockFinancialSyncService.SyncTrigger;

class StockFinancialCollectionSchedulerTest {

    private static final LocalDate MONDAY = LocalDate.of(2026, 8, 31);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final StockFinancialSyncService syncService = mock(StockFinancialSyncService.class);
    private final StockFinancialCollectionScheduler scheduler =
            new StockFinancialCollectionScheduler(syncService);

    @Test
    void weekly_trigger_delegates_to_sync_service_with_scheduled_trigger() {
        scheduler.scheduleWeeklyRefresh();

        verify(syncService).refreshRankedTargets(SyncTrigger.SCHEDULED);
    }

    @Test
    void weekly_trigger_fires_at_08_10_KST_every_monday() throws NoSuchMethodException {
        Scheduled schedule = StockFinancialCollectionScheduler.class
                .getMethod("scheduleWeeklyRefresh")
                .getAnnotation(Scheduled.class);

        assertThat(schedule).isNotNull();
        assertThat(schedule.zone()).isEqualTo("Asia/Seoul");

        ZoneId zone = ZoneId.of(schedule.zone());
        ZonedDateTime next = CronExpression.parse(schedule.cron())
                .next(ZonedDateTime.of(MONDAY, LocalTime.MIDNIGHT, zone));

        assertThat(next).isNotNull();
        assertThat(next.toInstant())
                .isEqualTo(ZonedDateTime.of(MONDAY, LocalTime.of(8, 10), KST).toInstant());
    }

    @Test
    void sync_service_failure_is_not_propagated_out_of_scheduler() {
        doThrow(new BusinessException(ErrorCode.KIS_API_ERROR, "배치 실패"))
                .when(syncService).refreshRankedTargets(SyncTrigger.SCHEDULED);

        assertThatCode(scheduler::scheduleWeeklyRefresh).doesNotThrowAnyException();
    }
}
