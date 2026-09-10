package com.baedang.market.service;

import com.baedang.global.error.BusinessException;
import com.baedang.global.error.ErrorCode;
import com.baedang.market.port.ExchangeRateQuote;
import com.baedang.market.port.MarketCalendarPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

@Service
public class ExchangeRateLoadService {

    private static final Logger log = LoggerFactory.getLogger(ExchangeRateLoadService.class);

    private final MarketCalendarPort marketCalendarPort;
    private final ExchangeRatePersistenceService exchangeRatePersistenceService;
    private final Clock clock;
    private final ReentrantLock refreshLock = new ReentrantLock();
    private volatile long generation;
    private long completedAtNanos;
    private boolean lastSaved;

    public ExchangeRateLoadService(
            MarketCalendarPort marketCalendarPort,
            ExchangeRatePersistenceService exchangeRatePersistenceService,
            Clock clock
    ) {
        this.marketCalendarPort = marketCalendarPort;
        this.exchangeRatePersistenceService = exchangeRatePersistenceService;
        this.clock = clock;
    }

    /** 정기 수집과 시장가 복구를 직렬화합니다. 5초 재호출 제한은 환율 유효기간과 무관합니다. */
    @Transactional(propagation = Propagation.NEVER)
    public boolean syncExchangeRate() {
        long observed = generation;
        boolean acquired = false;
        try {
            acquired = refreshLock.tryLock(5, TimeUnit.SECONDS);
            if (!acquired) throw new BusinessException(ErrorCode.EXCHANGE_RATE_NOT_FOUND);
            if (generation != observed || (generation > 0
                    && System.nanoTime() - completedAtNanos < TimeUnit.SECONDS.toNanos(5))) return lastSaved;
            lastSaved = false;
            try {
                lastSaved = collect();
                return lastSaved;
            } finally {
                completedAtNanos = System.nanoTime();
                generation++;
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.EXCHANGE_RATE_NOT_FOUND);
        } finally {
            if (acquired) refreshLock.unlock();
        }
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
