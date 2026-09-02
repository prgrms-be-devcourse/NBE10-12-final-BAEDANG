package com.baedang.global.clients.toss;

import com.baedang.global.error.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FixedIntervalGateTest {
    private static final long INTERVAL_5TPS = TimeUnit.MILLISECONDS.toNanos(200);

    @Test
    @DisplayName("순차 획득은 TPS 간격으로 예약된다")
    void t1() {
        AtomicLong now = new AtomicLong();
        List<Long> slept = new CopyOnWriteArrayList<>();
        FixedIntervalGate gate = new FixedIntervalGate(5, now::get, slept::add);

        gate.acquire();
        gate.acquire();
        gate.acquire();

        assertThat(slept).containsExactly(0L, INTERVAL_5TPS, INTERVAL_5TPS * 2);
    }

    @Test
    @DisplayName("다중 스레드 경합에도 요청 간격이 보장")
    void t2() throws InterruptedException {
        AtomicLong now = new AtomicLong();
        List<Long> slept = new CopyOnWriteArrayList<>();
        FixedIntervalGate gate =
                new FixedIntervalGate(5, now::get, slept::add);

        int threads = 10;

        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        List<Throwable> failures = new CopyOnWriteArrayList<>();

        for (int i = 0; i < threads; i++) {
            Thread thread = new Thread(() -> {
                ready.countDown();

                try {
                    start.await();
                    gate.acquire();
                } catch (Throwable throwable) {
                    failures.add(throwable);
                } finally {
                    done.countDown();
                }
            });

            thread.start();
        }

        boolean allReady = ready.await(5, TimeUnit.SECONDS);
        start.countDown();

        assertThat(allReady).isTrue();
        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(failures).isEmpty();

        List<Long> sorted = slept.stream()
                .sorted()
                .toList();

        assertThat(sorted).hasSize(threads);

        for (int i = 1; i < sorted.size(); i++) {
            assertThat(sorted.get(i) - sorted.get(i - 1))
                    .isGreaterThanOrEqualTo(INTERVAL_5TPS);
        }
    }

    @Test
    @DisplayName("tryAcquire는 즉시 불가하면 실패하고 슬롯을 소비하지 않는다")
    void t3() {
        AtomicLong now = new AtomicLong();
        FixedIntervalGate gate = new FixedIntervalGate(5, now::get, nanos -> {
        });

        assertThat(gate.tryAcquire()).isTrue();
        assertThat(gate.tryAcquire()).isFalse();

        now.set(INTERVAL_5TPS);
        assertThat(gate.tryAcquire()).isTrue();
    }

    @Test
    @DisplayName("대기 중 인터럽트는 상태를 복원하고 예외를 전파한다")
    void t4() {
        FixedIntervalGate gate = new FixedIntervalGate(5, () -> 0L, nanos -> {
            if (nanos > 0) throw new InterruptedException();
        });

        gate.acquire();
        assertThatThrownBy(gate::acquire).isInstanceOf(BusinessException.class);
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
        Thread.interrupted(); // 테스트 스레드 상태 정리
    }

}
