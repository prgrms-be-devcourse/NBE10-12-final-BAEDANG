package com.baedang.market.service;

import com.baedang.global.error.BusinessException;
import com.baedang.global.error.ErrorCode;
import com.baedang.market.port.ExchangeRateQuote;
import com.baedang.market.port.MarketCalendarPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

@Service
public class ExchangeRateLoadService {

    private static final Logger log = LoggerFactory.getLogger(ExchangeRateLoadService.class);

    private final MarketCalendarPort marketCalendarPort;
    private final ExchangeRatePersistenceService exchangeRatePersistenceService;
    private final Clock clock;
    private final Executor executor;
    private final long waitNanos;
    private CompletableFuture<Boolean> inFlight;
    private long completedAtNanos;

    public ExchangeRateLoadService(
            MarketCalendarPort marketCalendarPort,
            ExchangeRatePersistenceService exchangeRatePersistenceService,
            Clock clock,
            @Qualifier("exchangeRateRefreshExecutor") Executor executor,
            @Value("${trading.exchange-rate-refresh-wait:5s}") Duration wait
    ) {
        this.marketCalendarPort = marketCalendarPort;
        this.exchangeRatePersistenceService = exchangeRatePersistenceService;
        this.clock = clock;
        if (wait == null || wait.isNegative() || wait.isZero()) throw new IllegalArgumentException("환율 갱신 대기는 양수여야 합니다");
        this.executor = executor;
        this.waitNanos = wait.toNanos();
    }

    /** 정기 수집과 시장가 복구를 직렬화합니다. 5초 재호출 제한은 환율 유효기간과 무관합니다. */
    @Transactional(propagation = Propagation.NEVER)
    public boolean syncExchangeRate() {
        long started = System.nanoTime();
        CompletableFuture<Boolean> future = sharedRefresh();
        try {
            return future.get(Math.max(0, waitNanos - (System.nanoTime() - started)), TimeUnit.NANOSECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.EXCHANGE_RATE_NOT_FOUND);
        } catch (ExecutionException | TimeoutException exception) {
            // 대기자 하나의 시간 초과로 공유 작업을 취소하지 않습니다. 완료된 환율은 후속 요청이 사용합니다.
            throw new BusinessException(ErrorCode.EXCHANGE_RATE_NOT_FOUND);
        }
    }

    private synchronized CompletableFuture<Boolean> sharedRefresh() {
        if (inFlight != null && (!inFlight.isDone()
                || System.nanoTime() - completedAtNanos < TimeUnit.SECONDS.toNanos(5))) return inFlight;
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        inFlight = future;
        try {
            executor.execute(() -> {
                try {
                    boolean saved = collect();
                    complete(future, saved, null);
                } catch (Throwable exception) {
                    log.warn("환율 갱신 작업 실패: type={}", exception.getClass().getSimpleName());
                    complete(future, false, exception);
                }
            });
        } catch (RuntimeException exception) {
            complete(future, false, exception);
        }
        return future;
    }

    private synchronized void complete(CompletableFuture<Boolean> future, boolean saved, Throwable failure) {
        completedAtNanos = System.nanoTime();
        if (failure == null) future.complete(saved);
        else future.completeExceptionally(failure);
    }

    private boolean collect() {
        ExchangeRateQuote quote = marketCalendarPort.fetchExchangeRate();

        if (quote == null) {
            log.warn("외부 환율 응답이 null입니다.");
            return false;
        }

        OffsetDateTime collectedAt = clock.instant().atOffset(ZoneOffset.UTC);

        boolean saved = exchangeRatePersistenceService.saveIfValid(quote, collectedAt);

        log.info("환율 동기화 완료: base={}, quote={}, validFrom={}, saved={}",
                quote.baseCurrency(), quote.quoteCurrency(), quote.validFrom(), saved);

        return saved;
    }

}
