package com.baedang.market.provider;

import com.baedang.global.error.BusinessException;
import com.baedang.global.error.ErrorCode;
import com.baedang.market.entity.ExchangeRate;
import com.baedang.market.port.ExecutionExchangeRateProvider;
import com.baedang.market.port.ExecutionExchangeRateSnapshot;
import com.baedang.market.repository.ExchangeRateRepository;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.ZoneOffset;

/** 1분 주기로 DB에 적재된 최신 환율을 거래 스냅샷으로 제공합니다. 외부 호출·메모리 캐시는 없습니다. */
@Component
public class ExecutionExchangeRateProviderBridge implements ExecutionExchangeRateProvider {
    private final ExchangeRateRepository repository;
    private final Clock clock;

    public ExecutionExchangeRateProviderBridge(ExchangeRateRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Override
    public BigDecimal currentUsdKrwRate() {
        return currentUsdKrwSnapshot().rate();
    }

    @Override
    public ExecutionExchangeRateSnapshot currentUsdKrwSnapshot() {
        ExchangeRate row = repository.findTopByBaseCurrencyAndQuoteCurrencyOrderByValidFromDesc("USD", "KRW")
                .orElseThrow(() -> new BusinessException(ErrorCode.EXCHANGE_RATE_NOT_FOUND));
        ExecutionExchangeRateSnapshot snapshot = new ExecutionExchangeRateSnapshot(
                row.getRate(), row.getCollectedAt(), row.getValidFrom(), row.getValidUntil());
        if (!snapshot.isValidAt(clock.instant().atOffset(ZoneOffset.UTC))) {
            throw new BusinessException(ErrorCode.EXCHANGE_RATE_NOT_FOUND);
        }
        return snapshot;
    }
}
