package com.baedang.global.clients.toss;

import com.baedang.global.error.BusinessException;
import com.baedang.global.error.ErrorCode;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * 한 API 그룹의 요청을 고정 간격으로 배출한다.
 *
 * <p>다음 허가 시각 하나를 원자적으로 예약한다 — 여러 스레드가 동시에 들어와도
 * CAS 에 성공한 순서대로 서로 다른 시각을 받으므로 요청 시작 간격이 보장된다.
 * burst 는 허용하지 않는다.
 */
class FixedIntervalGate {

    @FunctionalInterface
    interface NanoSleeper {
        void sleep(long nanos) throws InterruptedException;
    }

    private final long intervalNanos;
    private final LongSupplier nanoTimeSource;
    private final NanoSleeper sleeper;
    private final AtomicLong nextPermitNanos = new AtomicLong(0);

    FixedIntervalGate(int tps) {
        this(tps, System::nanoTime, TimeUnit.NANOSECONDS::sleep);
    }

    /**
     * 테스트용 — 시각과 대기를 주입해 실제 sleep 없이 동작을 검증한다.
     */
    FixedIntervalGate(int tps, LongSupplier nanoTimeSource, NanoSleeper sleeper) {
        if (tps <= 0) throw new BusinessException(ErrorCode.INTERNAL_ERROR, "TPS는 1 이상이어야 함: " + tps);
        this.intervalNanos = (TimeUnit.SECONDS.toNanos(1) + tps - 1) / tps;
        this.nanoTimeSource = nanoTimeSource;
        this.sleeper = sleeper;
    }

    void acquire() {
        long permitAt = reserve();
        long delayNanos = Math.max(0, permitAt - nanoTimeSource.getAsLong());
        try {
            sleeper.sleep(delayNanos); // 0이면 즉시 반환 - 호출 생략하지 않음(테스트에서 획득 횟수로 사용)
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "Rate Limit 대기 중 인터럽트");
        }
    }

    boolean tryAcquire() {
        while (true) {
            long now = nanoTimeSource.getAsLong();
            long next = nextPermitNanos.get();
            if (next > now) return false;
            if (nextPermitNanos.compareAndSet(next, now + intervalNanos)) return true;
        }
    }

    private long reserve() {
        while (true) {
            long now = nanoTimeSource.getAsLong();
            long next = nextPermitNanos.get();
            long permitAt = Math.max(now, next);
            if (nextPermitNanos.compareAndSet(next, permitAt + intervalNanos)) return permitAt;
        }
    }
}
