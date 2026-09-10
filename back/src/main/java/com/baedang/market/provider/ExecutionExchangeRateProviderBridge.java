package com.baedang.market.provider;

import com.baedang.global.error.BusinessException;
import com.baedang.global.error.ErrorCode;
import com.baedang.market.entity.ExchangeRate;
import com.baedang.market.port.ExecutionExchangeRateProvider;
import com.baedang.market.port.ExecutionExchangeRateSnapshot;
import com.baedang.market.repository.ExchangeRateRepository;
import com.baedang.market.service.ExchangeRateLoadService;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.ZoneOffset;

/** 기본 조회는 DB 전용입니다. 시장가의 명시적 복구 경로만 외부 갱신을 허용합니다. */
@Component
public class ExecutionExchangeRateProviderBridge implements ExecutionExchangeRateProvider {
    private final ExchangeRateRepository repository;
    private final Clock clock;
    private final ExchangeRateLoadService loadService;

    public ExecutionExchangeRateProviderBridge(ExchangeRateRepository repository, Clock clock, ExchangeRateLoadService loadService) {
        this.repository = repository;
        this.clock = clock;
        this.loadService = loadService;
    }

    @Override
    @Transactional(propagation = Propagation.NEVER)
    public void refreshUnavailableForMarketOrder() {
        ExchangeRate row = repository.findTopByBaseCurrencyAndQuoteCurrencyOrderByValidFromDesc("USD", "KRW").orElse(null);
        // 다른 요청이 이미 복구했다면 호출하지 않습니다. 미래/손상 데이터도 외부 갱신으로 우회하지 않습니다.
        if (row != null && (row.getValidUntil() == null
                || clock.instant().isBefore(row.getValidUntil().toInstant()))) return;
        try {
            loadService.syncExchangeRate();
        } catch (RuntimeException exception) {
            throw new BusinessException(ErrorCode.EXCHANGE_RATE_NOT_FOUND);
        }
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
