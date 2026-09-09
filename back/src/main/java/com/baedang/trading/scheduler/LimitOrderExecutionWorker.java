package com.baedang.trading.scheduler;

import com.baedang.trading.model.LimitExecutionOutcome;
import com.baedang.trading.repository.LimitExecutionCandidateRepository;
import com.baedang.trading.repository.LimitExecutionCandidateRepository.Candidate;
import com.baedang.trading.repository.LimitExecutionCandidateRepository.Group;
import com.baedang.trading.service.LimitOrderExecutionService;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;

/** 단일 인스턴스, 전용 단일 스레드. 시간 예산은 새 작업 시작만 제한하며 실행 중인 정산은 중단하지 않습니다. */
@Component
public class LimitOrderExecutionWorker {
    private static final Logger log = LoggerFactory.getLogger(LimitOrderExecutionWorker.class);
    private final LimitExecutionCandidateRepository candidates;
    private final LimitOrderExecutionService service;
    private final MeterRegistry meters;
    private final Clock clock;
    private final int pageSize;
    private final int maxAttempts;
    private final long budgetNanos;
    private long upper;
    private Group group;
    private Group afterGroup;
    private Candidate after;

    public LimitOrderExecutionWorker(LimitExecutionCandidateRepository candidates, LimitOrderExecutionService service,
            MeterRegistry meters, Clock clock, @Value("${trading.limit-execution.page-size:50}") int pageSize,
            @Value("${trading.limit-execution.max-attempts:100}") int maxAttempts,
            @Value("${trading.limit-execution.start-budget:5s}") Duration budget) {
        if (pageSize < 1 || pageSize > 200 || maxAttempts < 1 || budget.isNegative() || budget.isZero()) {
            throw new IllegalArgumentException("체결 워커 설정이 올바르지 않습니다");
        }
        this.candidates = candidates; this.service = service; this.meters = meters; this.clock = clock;
        this.pageSize = pageSize; this.maxAttempts = maxAttempts; this.budgetNanos = budget.toNanos();
    }

    @Scheduled(scheduler = "limitExecutionTaskScheduler", fixedDelayString = "${trading.limit-execution.interval:3s}",
            initialDelayString = "${trading.limit-execution.initial-delay:3s}")
    public synchronized void tick() {
        long started = System.nanoTime();
        int attempts = 0;
        try {
            if (upper == 0) upper = candidates.upperBound();
            if (upper == 0) return;
            while (attempts < maxAttempts && System.nanoTime() - started < budgetNanos) {
                if (group == null) {
                    group = candidates.nextGroup(afterGroup, upper, clock.instant().atOffset(ZoneOffset.UTC)).orElse(null);
                    if (group == null) { resetCycle(); return; }
                    after = null;
                }
                List<Candidate> page = candidates.page(group, after, upper, clock.instant().atOffset(ZoneOffset.UTC), pageSize);
                if (page.isEmpty()) { nextGroup(); continue; }
                for (Candidate candidate : page) {
                    if (attempts >= maxAttempts || System.nanoTime() - started >= budgetNanos) return;
                    attempts++;
                    after = candidate;
                    Duration queued = Duration.between(candidate.orderedAt().toInstant(), clock.instant());
                    if (!queued.isNegative()) meters.timer("trading.limit.execution.queue.age").record(queued);
                    try {
                        LimitExecutionOutcome result = service.execute(candidate.orderId());
                        meters.counter("trading.limit.execution.attempt", "reason", result.reason().name()).increment();
                        if (result.executionCount() > 0) {
                            meters.counter("trading.limit.execution.fills").increment(result.executionCount());
                            log.info("지정가 체결: orderId={} fills={}", candidate.orderId(), result.executionCount());
                        }
                        boolean stopDirection = switch (result.reason()) {
                            case BOOK_CHANGED, LOCK_BUSY, ORDER_CHANGED, STATUS_UNAVAILABLE, CONTEXT_EXPIRED,
                                    NO_BOOK, STALE_BOOK, MARKET_CLOSED, NOT_TRADABLE -> true;
                            default -> false;
                        };
                        if (stopDirection) {
                            nextGroup(); break;
                        }
                    } catch (RuntimeException exception) {
                        // 선순위의 판단이 실패했으므로 같은 방향 후순위는 이번 순회에서 실행하지 않습니다.
                        log.error("지정가 체결 실패, 방향 순회 보류: stockId={} side={} orderId={}",
                                group.stockId(), group.side(), candidate.orderId(), exception);
                        meters.counter("trading.limit.execution.attempt", "reason", "ERROR").increment();
                        nextGroup(); break;
                    }
                }
            }
        } catch (RuntimeException exception) {
            log.warn("지정가 체결 대상 조회 실패, 커서 유지", exception);
        } finally {
            meters.timer("trading.limit.execution.tick").record(Duration.ofNanos(System.nanoTime() - started));
        }
    }

    private void nextGroup() { afterGroup = group; group = null; after = null; }
    private void resetCycle() { upper = 0; group = null; afterGroup = null; after = null; }
}
