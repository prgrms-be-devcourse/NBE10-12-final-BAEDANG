package com.baedang.trading.scheduler;

import com.baedang.stock.entity.MarketCountry;
import com.baedang.trading.entity.OrderSide;
import com.baedang.trading.model.ExecutionRateEvidence;
import com.baedang.trading.model.LimitExecutionBook;
import com.baedang.trading.model.LimitExecutionOutcome;
import com.baedang.trading.model.LimitExecutionPreparation;
import com.baedang.trading.model.LimitOrderAcceptedEvent;
import com.baedang.trading.model.OrderMarketContext;
import com.baedang.trading.repository.LimitExecutionCandidateRepository;
import com.baedang.trading.repository.LimitExecutionCandidateRepository.Candidate;
import com.baedang.trading.repository.LimitExecutionCandidateRepository.Group;
import com.baedang.trading.service.LimitOrderExecutionService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class LimitOrderExecutionWorkerTest {
    private static final Instant NOW = Instant.parse("2026-09-09T01:00:00Z");
    private final LimitExecutionCandidateRepository repository = mock(LimitExecutionCandidateRepository.class);
    private final LimitOrderExecutionService service = mock(LimitOrderExecutionService.class);
    private final LimitExecutionProgress progress = new LimitExecutionProgress();
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final Group group = new Group(1L, OrderSide.BUY);
    private final Candidate first = new Candidate(1L, BigDecimal.TEN, NOW.atOffset(ZoneOffset.UTC));
    private final Candidate second = new Candidate(2L, BigDecimal.ONE, NOW.atOffset(ZoneOffset.UTC));

    @BeforeEach
    void setup() {
        when(repository.nextGroup(isNull(), any())).thenReturn(Optional.of(group));
        when(repository.nextGroup(eq(group), any())).thenReturn(Optional.empty());
        when(service.prepare(1L, OrderSide.BUY)).thenReturn(market(3L, 0L, "1400"));
        when(service.execute(anyLong(), any())).thenReturn(LimitExecutionOutcome.deferred(LimitExecutionOutcome.Reason.RESERVED_CASH));
        when(repository.page(eq(group), isNull(), any(), anyInt())).thenReturn(List.of(first, second));
        when(repository.page(eq(group), eq(first), any(), anyInt())).thenReturn(List.of(second));
        when(repository.page(eq(group), eq(second), any(), anyInt())).thenReturn(List.of());
    }

    private LimitOrderExecutionWorker worker(int max, int perGroup) {
        return new LimitOrderExecutionWorker(repository, service, progress, new SimpleMeterRegistry(), clock,
                50, max, perGroup, Duration.ofSeconds(5));
    }

    private LimitExecutionPreparation market(long version, long revision, String rate) {
        return market(group, version, revision, rate);
    }

    private LimitExecutionPreparation market(Group target, long version, long revision, String rate) {
        ExecutionRateEvidence evidence = new ExecutionRateEvidence(new BigDecimal(rate), NOW.atOffset(ZoneOffset.UTC),
                NOW.atOffset(ZoneOffset.UTC), NOW.plusSeconds(60).atOffset(ZoneOffset.UTC));
        return LimitExecutionPreparation.available(target.stockId(), target.side(),
                new LimitExecutionBook(version, revision, NOW, NOW, List.of()),
                new OrderMarketContext(MarketCountry.US, true, NOW.plusSeconds(3600), evidence, NOW));
    }

    @Test
    void 같은버전과_같은환율이면_틱을_넘겨_후순위를_검토한다() {
        LimitOrderExecutionWorker worker = worker(1, 1);
        worker.tick();
        when(service.prepare(1L, OrderSide.BUY)).thenReturn(market(3L, 1L, "1400.000000"));
        worker.tick();
        InOrder sequence = inOrder(service);
        sequence.verify(service).execute(eq(1L), any());
        sequence.verify(service).execute(eq(2L), any());
    }

    @Test
    void 새호가에서는_부분체결된_선순위부터_다시_시도한다() {
        LimitOrderExecutionWorker worker = worker(1, 1);
        when(service.execute(eq(1L), any())).thenReturn(new LimitExecutionOutcome(1, LimitExecutionOutcome.Reason.EXECUTED));
        worker.tick();
        when(service.prepare(1L, OrderSide.BUY)).thenReturn(market(4L, 0L, "1400"));
        worker.tick();
        verify(service, times(2)).execute(eq(1L), any());
        verify(service, never()).execute(eq(2L), any());
    }

    @Test
    void 환율변경은_이전에_동결부족이던_선순위도_재평가한다() {
        LimitOrderExecutionWorker worker = worker(1, 1);
        worker.tick();
        when(service.prepare(1L, OrderSide.BUY)).thenReturn(market(3L, 0L, "1300"));
        worker.tick();
        verify(service, times(2)).execute(eq(1L), any());
        verify(service, never()).execute(eq(2L), any());
    }

    @Test
    void 접수알림은_같은페이지의_후순위진행과_오래된커서_저장을_막는다() {
        LimitOrderExecutionWorker worker = worker(100, 10);
        Candidate newer = new Candidate(3L, new BigDecimal("20"), NOW.atOffset(ZoneOffset.UTC));
        when(service.execute(eq(1L), any())).thenAnswer(invocation -> {
            progress.onAccepted(new LimitOrderAcceptedEvent(1L, OrderSide.BUY, newer.orderId(), newer.price(), newer.orderedAt()));
            when(repository.page(eq(group), isNull(), any(), anyInt())).thenReturn(List.of(newer));
            return LimitExecutionOutcome.deferred(LimitExecutionOutcome.Reason.RESERVED_CASH);
        });
        worker.tick();
        verify(service, never()).execute(eq(2L), any());
        worker.tick();
        verify(service).execute(eq(3L), any());
    }

    @Test
    void 그룹예산을_다쓰면_남은주문이_있어도_다른종목을_처리한다() {
        LimitOrderExecutionWorker worker = worker(2, 1);
        Group another = new Group(2L, OrderSide.BUY);
        Candidate third = new Candidate(3L, BigDecimal.TEN, NOW.atOffset(ZoneOffset.UTC));
        when(repository.nextGroup(eq(group), any())).thenReturn(Optional.of(another));
        when(service.prepare(2L, OrderSide.BUY)).thenReturn(market(another, 4L, 0L, "1400"));
        when(repository.page(eq(another), isNull(), any(), anyInt())).thenReturn(List.of(third));
        worker.tick();
        InOrder sequence = inOrder(service);
        sequence.verify(service).execute(eq(1L), any());
        sequence.verify(service).execute(eq(3L), any());
        verify(service, never()).execute(eq(2L), any());
    }

    @Test
    void 페이지를_이어가되_불가능한_주문은_후순위를_막지않는다() {
        LimitOrderExecutionWorker worker = worker(100, 10);
        when(repository.page(eq(group), isNull(), any(), eq(50))).thenReturn(List.of(first));
        worker.tick();
        verify(service).execute(eq(1L), any());
        verify(service).execute(eq(2L), any());
    }

    @ParameterizedTest
    @EnumSource(value = LimitExecutionOutcome.Reason.class, names = {"PRIORITY_CHANGED", "BOOK_CHANGED", "LOCK_BUSY"})
    void 버전변경이나_미해결경합은_후순위를_보류하고_다음방문에_재선정한다(LimitExecutionOutcome.Reason reason) {
        LimitOrderExecutionWorker worker = worker(100, 10);
        when(service.execute(eq(1L), any())).thenReturn(LimitExecutionOutcome.deferred(reason));
        worker.tick();
        verify(service, never()).execute(eq(2L), any());
        worker.tick();
        verify(service, times(2)).execute(eq(1L), any());
    }

    @Test
    void 금융예외도_후순위_체결을_막고_다음방문에_선순위를_재시도한다() {
        LimitOrderExecutionWorker worker = worker(100, 10);
        when(service.execute(eq(1L), any())).thenThrow(new IllegalStateException("broken financial state"));
        worker.tick();
        worker.tick();
        verify(service, times(2)).execute(eq(1L), any());
        verify(service, never()).execute(eq(2L), any());
    }

    @Test
    void 더이상_활성주문이_없는_그룹의_진행위치를_정리한다() {
        LimitOrderExecutionWorker worker = worker(1, 1);
        worker.tick();
        when(repository.nextGroup(isNull(), any())).thenReturn(Optional.empty());
        worker.tick();
        // 다음 전체 순회까지 관찰되지 않으면 제거됩니다.
        worker.tick();
        when(repository.nextGroup(isNull(), any())).thenReturn(Optional.of(group));
        worker.tick();
        verify(service, times(2)).execute(eq(1L), any());
    }

    @Test
    void 후순위_신규접수는_진행위치를_처음으로_되돌리지않는다() {
        LimitOrderExecutionWorker worker = worker(1, 1);
        worker.tick();
        progress.onAccepted(new LimitOrderAcceptedEvent(1L, OrderSide.BUY, 3L, new BigDecimal("0.5"), NOW.atOffset(ZoneOffset.UTC)));
        worker.tick();
        verify(service, times(1)).execute(eq(1L), any());
        verify(service).execute(eq(2L), any());
    }
}
