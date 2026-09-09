package com.baedang.trading.scheduler;

import com.baedang.trading.entity.OrderSide;
import com.baedang.trading.model.LimitExecutionOutcome;
import com.baedang.trading.repository.LimitExecutionCandidateRepository;
import com.baedang.trading.repository.LimitExecutionCandidateRepository.Candidate;
import com.baedang.trading.repository.LimitExecutionCandidateRepository.Group;
import com.baedang.trading.service.LimitOrderExecutionService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class LimitOrderExecutionWorkerTest {
    private final LimitExecutionCandidateRepository repository = mock(LimitExecutionCandidateRepository.class);
    private final LimitOrderExecutionService service = mock(LimitOrderExecutionService.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-09T01:00:00Z"),ZoneOffset.UTC);
    private final Group group = new Group(1L,OrderSide.BUY);
    private final Candidate first = new Candidate(1L,BigDecimal.TEN,clock.instant().atOffset(ZoneOffset.UTC));
    private final Candidate second = new Candidate(2L,BigDecimal.ONE,clock.instant().atOffset(ZoneOffset.UTC));

    private LimitOrderExecutionWorker worker(int max) {
        when(repository.upperBound()).thenReturn(2L);
        when(repository.nextGroup(isNull(),eq(2L),any())).thenReturn(Optional.of(group));
        when(repository.nextGroup(eq(group),eq(2L),any())).thenReturn(Optional.empty());
        when(service.execute(anyLong())).thenReturn(LimitExecutionOutcome.deferred(LimitExecutionOutcome.Reason.RESERVED_CASH));
        return new LimitOrderExecutionWorker(repository,service,new SimpleMeterRegistry(),clock,50,max,Duration.ofSeconds(5));
    }

    @Test
    void 틱_한도에_도달하면_상한과_가격커서를_다음틱으로_이월한다() {
        LimitOrderExecutionWorker worker = worker(1);
        when(repository.page(eq(group),isNull(),eq(2L),any(),eq(50))).thenReturn(List.of(first,second));
        when(repository.page(eq(group),eq(first),eq(2L),any(),eq(50))).thenReturn(List.of(second));
        worker.tick();
        worker.tick();
        verify(repository,times(1)).upperBound();
        InOrder sequence = inOrder(service);
        sequence.verify(service).execute(1L);
        sequence.verify(service).execute(2L);
        sequence.verifyNoMoreInteractions();
    }

    @Test
    void 페이지끝에서_후속페이지로_진행하고_동결부족은_후순위를_막지않는다() {
        LimitOrderExecutionWorker worker = worker(100);
        when(repository.page(eq(group),isNull(),eq(2L),any(),eq(50))).thenReturn(List.of(first));
        when(repository.page(eq(group),eq(first),eq(2L),any(),eq(50))).thenReturn(List.of(second));
        when(repository.page(eq(group),eq(second),eq(2L),any(),eq(50))).thenReturn(List.of());
        worker.tick();
        verify(service).execute(1L);
        verify(service).execute(2L);
    }

    @Test
    void 해결안된_락경합은_동일방향_후순위를_건너뛰고_다음방향으로_넘긴다() {
        LimitOrderExecutionWorker worker = worker(100);
        when(repository.page(eq(group),isNull(),eq(2L),any(),eq(50))).thenReturn(List.of(first,second));
        when(service.execute(1L)).thenReturn(LimitExecutionOutcome.deferred(LimitExecutionOutcome.Reason.LOCK_BUSY));
        Group next = new Group(1L,OrderSide.SELL);
        when(repository.nextGroup(eq(group),eq(2L),any())).thenReturn(Optional.of(next));
        when(repository.page(eq(next),isNull(),eq(2L),any(),eq(50))).thenReturn(List.of(second));
        when(repository.page(eq(next),eq(second),eq(2L),any(),eq(50))).thenReturn(List.of());
        worker.tick();
        verify(service).execute(2L);
        verify(repository,never()).page(eq(group),eq(first),anyLong(),any(),anyInt());
    }

    @Test
    void 금융예외도_동일방향_후순위를_실행하지않는다() {
        LimitOrderExecutionWorker worker = worker(100);
        when(repository.page(eq(group),isNull(),eq(2L),any(),eq(50))).thenReturn(List.of(first,second));
        when(service.execute(1L)).thenThrow(new IllegalStateException("broken financial state"));
        worker.tick();
        verify(service,never()).execute(2L);
    }
}
