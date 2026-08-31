package com.baedang.market.scheduler;

import com.baedang.market.service.PrevCloseUpdateService;
import com.baedang.stock.entity.MarketCountry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class PrevCloseUpdateSchedulerTest {

    private PrevCloseUpdateService service;
    private PrevCloseUpdateScheduler scheduler;

    @BeforeEach
    void setUp() {
        service = mock(PrevCloseUpdateService.class);
        scheduler = new PrevCloseUpdateScheduler(service);
    }

    @Test
    void 국내_스케줄은_평일_08시50분_KST이다() throws NoSuchMethodException {
        Scheduled scheduled = PrevCloseUpdateScheduler.class
                .getMethod("updateKr")
                .getAnnotation(Scheduled.class);

        assertThat(scheduled.cron()).isEqualTo("0 50 8 * * MON-FRI");
        assertThat(scheduled.zone()).isEqualTo("Asia/Seoul");

        scheduler.updateKr();
        verify(service).update(MarketCountry.KR);
    }

    @Test
    void 미국_스케줄은_평일_09시_뉴욕_현지시간이다() throws NoSuchMethodException {
        Scheduled scheduled = PrevCloseUpdateScheduler.class
                .getMethod("updateUs")
                .getAnnotation(Scheduled.class);

        assertThat(scheduled.cron()).isEqualTo("0 0 9 * * MON-FRI");
        assertThat(scheduled.zone()).isEqualTo("America/New_York");

        scheduler.updateUs();
        verify(service).update(MarketCountry.US);
    }

    @Test
    void toss가_활성화된_경우에만_스케줄러가_등록된다() {
        ConditionalOnProperty condition = PrevCloseUpdateScheduler.class
                .getAnnotation(ConditionalOnProperty.class);

        assertThat(condition.prefix()).isEqualTo("toss");
        assertThat(condition.name()).containsExactly("enabled");
        assertThat(condition.havingValue()).isEqualTo("true");
    }

    @Test
    void 한_시장_갱신_실패는_스케줄러_밖으로_전파하지_않는다() {
        doThrow(new IllegalStateException("DB unavailable"))
                .when(service).update(MarketCountry.KR);

        assertThatCode(scheduler::updateKr).doesNotThrowAnyException();
    }
}
