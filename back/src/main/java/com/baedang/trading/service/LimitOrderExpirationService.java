package com.baedang.trading.service;

import com.baedang.trading.entity.TradeOrder;
import com.baedang.trading.repository.TradeOrderRepository;
import com.baedang.user.entity.Account;
import com.baedang.user.repository.AccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** 저장된 만료 시각으로 주문을 순차 복구하며 실패 건은 다음 스캔에서 재시도합니다. */
@Service
public class LimitOrderExpirationService {

    private static final Logger log = LoggerFactory.getLogger(LimitOrderExpirationService.class);

    private final AtomicBoolean running = new AtomicBoolean();
    private final TradeOrderRepository orders;
    private final AccountRepository accounts;
    private final LimitOrderTransactionService transactions;
    private final Clock clock;

    public LimitOrderExpirationService(
            TradeOrderRepository orders,
            AccountRepository accounts,
            LimitOrderTransactionService transactions,
            Clock clock
    ) {
        this.orders = orders;
        this.accounts = accounts;
        this.transactions = transactions;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.NEVER)
    public void expireDue() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            OffsetDateTime now = clock.instant().atOffset(ZoneOffset.UTC);
            long after = 0;
            while (true) {
                List<TradeOrder> batch = orders.expired(now, after, PageRequest.of(0, 100));
                if (batch.isEmpty()) {
                    break;
                }
                for (TradeOrder order : batch) {
                    try {
                        Account account = accounts.findById(order.getAccountId()).orElseThrow();
                        transactions.close(account.getUserId(), account.getAccountId(), order.getOrderId(), true);
                    } catch (RuntimeException e) {
                        log.warn("지정가 만료 실패: orderId={}", order.getOrderId(), e);
                    }
                }
                after = batch.getLast().getOrderId();
            }
        } catch (RuntimeException e) {
            // 시작 시 DB 장애도 다음 스캔에서 복구합니다. 애플리케이션 기동을 중단하지 않습니다.
            log.error("지정가 만료 대상 조회 실패: 다음 주기에 재시도합니다", e);
        } finally {
            running.set(false);
        }
    }
}
