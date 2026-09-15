package com.baedang.market.service;

import com.baedang.global.normalizer.DomainNormalizer;
import com.baedang.global.error.BusinessException;
import com.baedang.market.port.ExchangeRateQuote;
import com.baedang.market.port.ExecutionExchangeRateSnapshot;
import com.baedang.market.repository.ExchangeRateRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

import static com.baedang.trading.support.NumericBounds.RATE_LIMIT;

@Service
public class ExchangeRatePersistenceService {

    private static final Logger log = LoggerFactory.getLogger(ExchangeRatePersistenceService.class);

    private final ExchangeRateRepository exchangeRateRepository;

    public ExchangeRatePersistenceService(
            ExchangeRateRepository exchangeRateRepository
    ) {
        this.exchangeRateRepository = exchangeRateRepository;
    }

    @Transactional
    public boolean saveIfValid(ExchangeRateQuote quote, OffsetDateTime collectedAt) {
        if (!isValid(quote, collectedAt)) {
            log.warn("유효하지 않은 환율 응답을 건너뜁니다: quote={}", quote);
            return false;
        }

        String baseCurrency = DomainNormalizer.currency(quote.baseCurrency());
        String quoteCurrency = DomainNormalizer.currency(quote.quoteCurrency());

        int affectedRows = exchangeRateRepository.upsertLatestObservation(
                baseCurrency,
                quoteCurrency,
                quote.rate(),
                quote.midRate(),
                quote.validFrom(),
                quote.validUntil(),
                collectedAt
        );

        if (affectedRows == 0) {
            log.debug(
                    "기존 수신 시각보다 새롭지 않은 환율은 건너뜁니다: base={}, quote={}, validFrom={}",
                    baseCurrency, quoteCurrency, quote.validFrom()
            );
            return false;
        }
        return true;
    }

    private boolean isValid(ExchangeRateQuote quote, OffsetDateTime collectedAt) {
        try {
            ExecutionExchangeRateSnapshot.from(quote, collectedAt);
            return quote.midRate() == null || (quote.midRate().signum() > 0
                    && quote.midRate().stripTrailingZeros().scale() <= 6
                    && quote.midRate().compareTo(RATE_LIMIT) < 0);
        } catch (BusinessException exception) {
            return false;
        }
    }
}
