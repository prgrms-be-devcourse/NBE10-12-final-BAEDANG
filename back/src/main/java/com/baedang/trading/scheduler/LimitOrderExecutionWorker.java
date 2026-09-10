package com.baedang.trading.scheduler;

import com.baedang.trading.model.LimitExecutionOutcome;
import com.baedang.trading.model.LimitExecutionPreparation;
import com.baedang.trading.repository.LimitExecutionCandidateRepository;
import com.baedang.trading.repository.LimitExecutionCandidateRepository.Candidate;
import com.baedang.trading.repository.LimitExecutionCandidateRepository.Group;
import com.baedang.trading.scheduler.LimitExecutionProgress.Position;
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 단일 인스턴스 전용. 그룹별로 실행 기회를 나누며 시간 예산은 진행 중 정산을 중단하지 않습니다. */
@Component
public class LimitOrderExecutionWorker {
    private static final Logger log = LoggerFactory.getLogger(LimitOrderExecutionWorker.class);
    private final LimitExecutionCandidateRepository candidates;
    private final LimitOrderExecutionService service;
    private final LimitExecutionProgress progress;
    private final MeterRegistry meters;
    private final Clock clock;
    private final int pageSize;
    private final int maxAttempts;
    private final int groupAttempts;
    private final long budgetNanos;
    private final Set<Group> observedGroups = new HashSet<>();
    private Group afterGroup;

    public LimitOrderExecutionWorker(LimitExecutionCandidateRepository candidates, LimitOrderExecutionService service,
            LimitExecutionProgress progress, MeterRegistry meters, Clock clock,
            @Value("${trading.limit-execution.page-size:50}") int pageSize,
            @Value("${trading.limit-execution.max-attempts:100}") int maxAttempts,
            @Value("${trading.limit-execution.group-attempts:10}") int groupAttempts,
            @Value("${trading.limit-execution.start-budget:5s}") Duration budget) {
        if (pageSize < 1 || pageSize > 200 || maxAttempts < 1 || groupAttempts < 1
                || groupAttempts > maxAttempts || budget == null || budget.isNegative() || budget.isZero()) {
            throw new IllegalArgumentException("체결 워커 설정이 올바르지 않습니다");
        }
        this.candidates = candidates;
        this.service = service;
        this.progress = progress;
        this.meters = meters;
        this.clock = clock;
        this.pageSize = pageSize;
        this.maxAttempts = maxAttempts;
        this.groupAttempts = groupAttempts;
        this.budgetNanos = budget.toNanos();
    }

    @Scheduled(scheduler = "limitExecutionTaskScheduler", fixedDelayString = "${trading.limit-execution.interval:3s}",
            initialDelayString = "${trading.limit-execution.initial-delay:3s}")
    public synchronized void tick() {
        long started = System.nanoTime();
        int attempts = 0;
        boolean visited = false;
        boolean wrapped = false;
        try {
            while (attempts < maxAttempts && withinBudget(started)) {
                Group group = candidates.nextGroup(afterGroup, clock.instant().atOffset(ZoneOffset.UTC)).orElse(null);
                if (group == null) {
                    progress.retainGroups(observedGroups);
                    observedGroups.clear();
                    afterGroup = null;
                    if (visited || wrapped) return;
                    wrapped = true;
                    continue;
                }
                visited = true;
                observedGroups.add(group);
                // 도중 예산 소진/실패가 발생해도 다음 틱에는 다른 그룹에 먼저 기회를 줍니다.
                afterGroup = group;
                attempts += visit(group, Math.min(groupAttempts, maxAttempts - attempts), started);
            }
        } catch (RuntimeException exception) {
            log.warn("지정가 체결 대상 조회 실패, 그룹 진행 위치 유지", exception);
        } finally {
            meters.timer("trading.limit.execution.tick").record(Duration.ofNanos(System.nanoTime() - started));
        }
    }

    private int visit(Group group, int allowance, long started) {
        int attempts = 0;
        try {
            LimitExecutionPreparation market = service.prepare(group.stockId(), group.side());
            if (!market.available()) {
                progress.reset(group);
                meters.counter("trading.limit.execution.preparation", "reason", market.reason().name()).increment();
                return 0;
            }
            List<Candidate> page = List.of();
            int pageIndex = 0;
            long pageToken = -1;
            while (attempts < allowance && withinBudget(started)) {
                // 한 건의 선정 경계입니다. 이후 도착하는 접수는 다음 경계에서 반영합니다.
                Position position = progress.position(group, market);
                if (pageToken != position.token() || pageIndex >= page.size()) {
                    page = candidates.page(group, position.after(), clock.instant().atOffset(ZoneOffset.UTC), pageSize);
                    pageIndex = 0;
                    pageToken = position.token();
                }
                if (page.isEmpty() || !withinBudget(started)) return attempts;
                Candidate candidate = page.get(pageIndex++);
                attempts++;
                Duration queued = Duration.between(candidate.orderedAt().toInstant(), clock.instant());
                if (!queued.isNegative()) meters.timer("trading.limit.execution.queue.age").record(queued);
                LimitExecutionOutcome result = service.execute(candidate.orderId(), market);
                meters.counter("trading.limit.execution.attempt", "reason", result.reason().name()).increment();
                if (result.executionCount() > 0) {
                    meters.counter("trading.limit.execution.fills").increment(result.executionCount());
                    log.info("지정가 체결: orderId={} fills={}", candidate.orderId(), result.executionCount());
                }
                boolean stopDirection = switch (result.reason()) {
                    case PRIORITY_CHANGED, BOOK_CHANGED, LOCK_BUSY, ORDER_CHANGED, STATUS_UNAVAILABLE, CONTEXT_EXPIRED,
                            NO_BOOK, STALE_BOOK, MARKET_CLOSED, NOT_TRADABLE -> true;
                    default -> false;
                };
                if (stopDirection) {
                    progress.reset(group);
                    return attempts;
                }
                // 접수 알림은 보류되어 있으므로 이번 진행을 저장한 뒤 다음 선정 때 함께 반영합니다.
                if (!progress.advance(group, position, candidate)) return attempts;
            }
        } catch (RuntimeException exception) {
            progress.reset(group);
            log.error("지정가 체결 실패, 방향 순회 보류: stockId={} side={}", group.stockId(), group.side(), exception);
            meters.counter("trading.limit.execution.attempt", "reason", "ERROR").increment();
        }
        return attempts;
    }

    private boolean withinBudget(long started) { return System.nanoTime() - started < budgetNanos; }
}
