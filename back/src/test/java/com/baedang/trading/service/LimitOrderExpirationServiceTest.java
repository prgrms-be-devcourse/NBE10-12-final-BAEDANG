package com.baedang.trading.service;

import com.baedang.trading.entity.TradeOrder;
import com.baedang.trading.repository.TradeOrderRepository;
import com.baedang.user.entity.Account;
import com.baedang.user.repository.AccountRepository;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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

    @ParameterizedTest
    @ValueSource(strings = {"lock", "connection", "missing-account"})
    void 실패주문을_건너뛰고_다음스캔에서_재시도한다(String failure) {
        TradeOrderRepository orders = mock(TradeOrderRepository.class);
        AccountRepository accounts = mock(AccountRepository.class);
        LimitOrderTransactionService transactions = mock(LimitOrderTransactionService.class);
        TradeOrder first = mock(TradeOrder.class);
        TradeOrder second = mock(TradeOrder.class);
        when(first.getOrderId()).thenReturn(100L);
        when(second.getOrderId()).thenReturn(101L);
        when(first.getAccountId()).thenReturn(50L);
        when(second.getAccountId()).thenReturn(50L);
        Account account = mock(Account.class);
        when(account.getUserId()).thenReturn(1L);
        when(account.getAccountId()).thenReturn(50L);
        when(orders.expired(any(), ArgumentMatchers.eq(101L), any())).thenReturn(List.of());
        when(orders.expired(any(), ArgumentMatchers.eq(100L), any())).thenReturn(List.of());
        Mockito.doReturn(List.of(first, second), List.of(first))
                .when(orders).expired(any(), ArgumentMatchers.eq(0L), any());
        if ("missing-account".equals(failure)) {
            when(accounts.findById(50L)).thenReturn(Optional.empty(), Optional.of(account));
        } else {
            when(accounts.findById(50L)).thenReturn(Optional.of(account));
            RuntimeException error = "lock".equals(failure)
                    ? new CannotAcquireLockException("lock timeout")
                    : new DataAccessResourceFailureException("connection unavailable");
            when(transactions.close(1L, 50L, 100L, true)).thenThrow(error).thenReturn(null);
        }
        LimitOrderExpirationService service = new LimitOrderExpirationService(orders, accounts,
                transactions, Clock.fixed(Instant.parse("2026-09-07T07:00:00Z"), ZoneOffset.UTC));
        service.expireDue();
        verify(transactions).close(1L, 50L, 101L, true);
        service.expireDue();
        verify(transactions, Mockito.times("missing-account".equals(failure) ? 1 : 2))
                .close(1L, 50L, 100L, true);
        verify(first, never()).expire(any());
        verify(second, never()).expire(any());
    }
}
