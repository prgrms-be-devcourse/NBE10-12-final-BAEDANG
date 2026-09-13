package com.baedang.trading.scheduler;

import com.baedang.global.error.BusinessException;
import com.baedang.global.error.ErrorCode;
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
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class LimitOrderExecutionWorkerTest {
    private static final Instant NOW = Instant.parse("2026-09-09T01:00:00Z");
    private final LimitExecutionCandidateRepository repository = mock(LimitExecutionCandidateRepository.class);
    private final LimitOrderExecutionService service = mock(LimitOrderExecutionService.class);
    private final LimitExecutionProgress progress = new LimitExecutionProgress();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
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
        return new LimitOrderExecutionWorker(repository, service, progress, meters, clock,
                50, max, perGroup, Duration.ofSeconds(5));
    }

    private double counter(String name, String... tags) {
        var search = meters.find(name);
        for (int index = 0; index < tags.length; index += 2) search = search.tag(tags[index], tags[index + 1]);
        var found = search.counter();
        return found == null ? 0.0 : found.count();
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
    void 접수알림은_실행중인_한건을_마친뒤_같은틱에서_선순위를_재선정한다() {
        LimitOrderExecutionWorker worker = worker(100, 10);
        Candidate newer = new Candidate(3L, new BigDecimal("20"), NOW.atOffset(ZoneOffset.UTC));
        when(service.execute(eq(1L), any())).thenAnswer(invocation -> {
            progress.onAccepted(new LimitOrderAcceptedEvent(1L, OrderSide.BUY, newer.orderId(), newer.price(), newer.orderedAt()));
            when(repository.page(eq(group), isNull(), any(), anyInt())).thenReturn(List.of(newer));
            return LimitExecutionOutcome.deferred(LimitExecutionOutcome.Reason.RESERVED_CASH);
        });
        worker.tick();
        verify(service, never()).execute(eq(2L), any());
        InOrder sequence = inOrder(service);
        sequence.verify(service).execute(eq(1L), any());
        sequence.verify(service).execute(eq(3L), any());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void 조회중이나_체결중에_선순위접수가_계속되어도_매선정마다_한건은_진행한다(boolean duringQuery) {
        LimitOrderExecutionWorker worker = worker(10, 10);
        List<Candidate> active = new ArrayList<>(List.of(first, second));
        List<Long> executed = new ArrayList<>();
        AtomicLong nextId = new AtomicLong(3);
        Runnable accept = () -> {
            long id = nextId.getAndIncrement();
            Candidate added = new Candidate(id, BigDecimal.valueOf(20 + id), NOW.atOffset(ZoneOffset.UTC));
            active.add(added);
            progress.onAccepted(new LimitOrderAcceptedEvent(1L, OrderSide.BUY, id, added.price(), added.orderedAt()));
        };
        Comparator<Candidate> priority = Comparator.comparing(Candidate::price).reversed()
                .thenComparing(Candidate::orderedAt).thenComparing(Candidate::orderId);
        when(repository.page(eq(group), any(), any(), anyInt())).thenAnswer(invocation -> {
            Candidate after = invocation.getArgument(1);
            List<Candidate> selected = active.stream()
                    .filter(candidate -> after == null || priority.compare(candidate, after) > 0)
                    .sorted(priority).toList();
            if (duringQuery) accept.run();
            return selected;
        });
        when(service.execute(anyLong(), any())).thenAnswer(invocation -> {
            Long id = invocation.getArgument(0);
            executed.add(id);
            active.removeIf(candidate -> candidate.orderId().equals(id));
            if (!duringQuery) accept.run();
            return new LimitExecutionOutcome(1, LimitExecutionOutcome.Reason.EXECUTED);
        });

        worker.tick();

        assertThat(executed).containsExactly(1L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L, 11L);
    }

    @Test
    void 매체결마다_후순위접수가_와도_같은틱에서_기존후보를_진행한다() {
        LimitOrderExecutionWorker worker = worker(2, 2);
        when(service.execute(anyLong(), any())).thenAnswer(invocation -> {
            progress.onAccepted(new LimitOrderAcceptedEvent(1L, OrderSide.BUY, 3L,
                    new BigDecimal("0.5"), NOW.atOffset(ZoneOffset.UTC)));
            return new LimitExecutionOutcome(1, LimitExecutionOutcome.Reason.EXECUTED);
        });
        worker.tick();
        InOrder sequence = inOrder(service);
        sequence.verify(service).execute(eq(1L), any());
        sequence.verify(service).execute(eq(2L), any());
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

    /**
     * CB 보류는 그룹 단위로 멈추되 다음 그룹은 같은 틱에서 계속 처리해야 한다. 시장 전체가 멈춘
     * 상황에서 그룹마다 후순위를 다시 시도하면 주문 수만큼 헛된 DB 조회가 발생한다.
     */
    @Test
    void CB보류는_같은그룹_후순위를_막고_다음그룹은_같은틱에_처리한다() {
        Group other = new Group(2L, OrderSide.BUY);
        Candidate third = new Candidate(3L, BigDecimal.TEN, NOW.atOffset(ZoneOffset.UTC));
        when(repository.nextGroup(eq(group), any())).thenReturn(Optional.of(other));
        when(service.prepare(2L, OrderSide.BUY)).thenReturn(market(other, 4L, 0L, "1400"));
        when(repository.page(eq(other), isNull(), any(), anyInt())).thenReturn(List.of(third));
        when(service.execute(eq(1L), any())).thenThrow(new BusinessException(ErrorCode.MARKET_TRADING_HALTED, Map.of(
                "market", "KOSPI",
                "eventType", "CIRCUIT_BREAKER",
                "stage", 1,
                "triggeredAt", OffsetDateTime.parse("2026-07-13T13:28:32+09:00"),
                "haltUntil", OffsetDateTime.parse("2026-07-13T13:48:32+09:00"))));

        worker(100, 10).tick();

        verify(service, times(1)).execute(eq(1L), any());
        verify(service, never()).execute(eq(2L), any());
        verify(service).execute(eq(3L), any());
        assertThat(counter("krx.market_event.order_blocked", "market", "KOSPI", "orderType", "LIMIT_EXECUTION"))
                .isEqualTo(1.0);
        assertThat(counter("trading.limit.execution.attempt", "reason", "ERROR")).isZero();
    }

    /** CB가 아닌 실패는 기존 ERROR 계약을 유지해야 하므로 예상 보류로 분류하지 않는다. */
    @Test
    void CB가_아닌_금융예외는_기존_ERROR_지표를_기록한다() {
        when(service.execute(eq(1L), any())).thenThrow(new IllegalStateException("broken financial state"));

        worker(100, 10).tick();

        assertThat(counter("trading.limit.execution.attempt", "reason", "ERROR")).isEqualTo(1.0);
        assertThat(counter("krx.market_event.order_blocked", "market", "KOSPI", "orderType", "LIMIT_EXECUTION")).isZero();
    }
}
