package com.baedang.global.clients.toss;

import com.baedang.global.error.BusinessException;
import com.baedang.global.error.ErrorCode;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;

/**
 * 한 API 그룹의 permit을 고정 간격으로 반환한다.
 *
 * <p>동일 그룹의 스레드는 공정한 lock 안에서 순서대로 대기한다.
 * permit 반환 시각을 기준으로 burst를 허용하지 않는다.
 */
class FixedIntervalGate {

    @FunctionalInterface
    interface NanoSleeper {
        void sleep(long nanos) throws InterruptedException;
    }

    private final long intervalNanos;
    private final LongSupplier nanoTimeSource;
    private final NanoSleeper sleeper;
    private final ReentrantLock lock = new ReentrantLock(true);

    private long nextPermitNanos;

    FixedIntervalGate(int tps) {
        this(tps, System::nanoTime, TimeUnit.NANOSECONDS::sleep);
    }

    FixedIntervalGate(
            int tps,
            LongSupplier nanoTimeSource,
            NanoSleeper sleeper
    ) {
        if (tps <= 0) {
            throw new BusinessException(
                    ErrorCode.INTERNAL_ERROR,
                    "TPS는 1 이상이어야 함: " + tps
            );
        }

        this.intervalNanos =
                (TimeUnit.SECONDS.toNanos(1) + tps - 1) / tps;
        this.nanoTimeSource = nanoTimeSource;
        this.sleeper = sleeper;
        this.nextPermitNanos = nanoTimeSource.getAsLong();
    }

    void acquire() {
        try {
            lock.lockInterruptibly();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BusinessException(
                    ErrorCode.INTERNAL_ERROR,
                    "Rate Limit lock 대기 중 인터럽트"
            );
        }

        try {
            long now = nanoTimeSource.getAsLong();
            long delayNanos = Math.max(0, nextPermitNanos - now);

            sleeper.sleep(delayNanos);

            // 실제 대기가 예상보다 길어진 경우 해당 시각부터 다음 간격을 계산한다.
            long grantedAt = Math.max(
                    nextPermitNanos,
                    nanoTimeSource.getAsLong()
            );
            nextPermitNanos = grantedAt + intervalNanos;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BusinessException(
                    ErrorCode.INTERNAL_ERROR,
                    "Rate Limit 대기 중 인터럽트"
            );
        } finally {
            lock.unlock();
        }
    }

    boolean tryAcquire() {
        if (!lock.tryLock()) {
            return false;
        }

        try {
            long now = nanoTimeSource.getAsLong();

            if (nextPermitNanos > now) {
                return false;
            }

            nextPermitNanos = now + intervalNanos;
            return true;
        } finally {
            lock.unlock();
        }
    }
}