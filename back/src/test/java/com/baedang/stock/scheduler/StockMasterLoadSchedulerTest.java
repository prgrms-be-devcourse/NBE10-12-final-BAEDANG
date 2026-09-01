package com.baedang.stock.scheduler;

import com.baedang.stock.service.StockMasterDetailLoadService;
import com.baedang.stock.service.StockMasterLoadService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StockMasterLoadSchedulerTest {
    @Mock
    private StockMasterLoadService stockMasterLoadService;

    @Mock
    private StockMasterDetailLoadService stockMasterDetailLoadService;

    @Test
    @DisplayName("매주 월요일 07시 KST 스케줄을 사용한다")
    void t1 () throws NoSuchMethodException{
        Scheduled scheduled = StockMasterLoadScheduler.class.getMethod("load").getAnnotation(Scheduled.class);

        assertThat(scheduled.cron()).isEqualTo("0 0 7 * * MON");

        assertThat(scheduled.zone()).isEqualTo("Asia/Seoul");

    }

    @Test
    @DisplayName("Toss 활성화 조건으로만 스케줄러 빈 등록한다")
    void t2 () {
        ConditionalOnProperty conditional = StockMasterLoadScheduler.class.getAnnotation(ConditionalOnProperty.class);

        assertThat(conditional.prefix()).isEqualTo("toss");
        assertThat(conditional.name()).containsExactly("enabled");
        assertThat(conditional.havingValue()).isEqualTo("true");
        assertThat(conditional.matchIfMissing()).isFalse();
    }

    @Test
    @DisplayName("1단계 완료 후 2단계를 순서대로 실행한다")
    void t3 () {
        StockMasterLoadScheduler scheduler =
                new StockMasterLoadScheduler(stockMasterLoadService, stockMasterDetailLoadService);

        scheduler.load();

        InOrder order = inOrder(stockMasterLoadService,stockMasterDetailLoadService);

        order.verify(stockMasterLoadService).loadAll();
        order.verify(stockMasterDetailLoadService).loadAll();
    }

    @Test
    @DisplayName("1단계 실패 시 2단계를 실행하지 않는다")
    void t4 () {
        StockMasterLoadScheduler scheduler =
                new StockMasterLoadScheduler(stockMasterLoadService, stockMasterDetailLoadService);

        doThrow(new RuntimeException("master load failed"))
                .when(stockMasterLoadService).loadAll();

        assertThatCode(scheduler::load).doesNotThrowAnyException();

        verify(stockMasterLoadService).loadAll();
        verifyNoInteractions(stockMasterDetailLoadService);
    }

    @Test
    @DisplayName("2단계 실패도 스케줄러 외부로 전파하지 않는다")
    void t5 () {
        StockMasterLoadScheduler scheduler =
                new StockMasterLoadScheduler(stockMasterLoadService, stockMasterDetailLoadService);

        doThrow(new RuntimeException("detail load failed")).when(stockMasterDetailLoadService).loadAll();

        assertThatCode(scheduler::load).doesNotThrowAnyException();

        verify(stockMasterLoadService).loadAll();
        verify(stockMasterDetailLoadService).loadAll();
    }
}
