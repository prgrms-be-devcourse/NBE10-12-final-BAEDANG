package com.baedang.trading.service;

import com.baedang.trading.repository.TradeOrderRepository;
import com.baedang.user.repository.AccountRepository;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LimitOrderExpirationServiceTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void 실행중인_스캔은_중복조회하지_않고_성공실패_후에는_재실행한다(boolean failFirst) throws Exception {
        var orders = mock(TradeOrderRepository.class);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var queries = new AtomicInteger();
        when(orders.expired(any(), anyLong(), any())).thenAnswer(invocation -> {
            if (queries.incrementAndGet() == 1) {
                entered.countDown();
                if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test query timeout");
                if (failFirst) throw new IllegalStateException("database unavailable");
            }
            return List.of();
        });
        var service = new LimitOrderExpirationService(orders, mock(AccountRepository.class),
                mock(LimitOrderTransactionService.class), Clock.fixed(Instant.parse("2026-09-07T07:00:00Z"), ZoneOffset.UTC));
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(service::expireDue);
            try {
                assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
                pool.submit(service::expireDue).get(2, TimeUnit.SECONDS);
                assertThat(queries.get()).isEqualTo(1);
            } finally {
                release.countDown();
            }
            first.get(3, TimeUnit.SECONDS);
        }
        service.expireDue();
        assertThat(queries.get()).isEqualTo(2);
    }
}
