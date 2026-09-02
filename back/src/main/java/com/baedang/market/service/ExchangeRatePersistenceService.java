package com.baedang.market.service;

import com.baedang.global.normalizer.DomainNormalizer;
import com.baedang.market.port.ExchangeRateQuote;
import com.baedang.market.repository.ExchangeRateRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

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
        if (!isValid(quote)) {
            log.warn("유효하지 않은 환율 응답을 건너뜁니다: quote={}", quote);
            return false;
        }

        String baseCurrency = DomainNormalizer.currency(quote.baseCurrency());
        String quoteCurrency = DomainNormalizer.currency(quote.quoteCurrency());

        int inserted = exchangeRateRepository.insertIgnoreDuplicate(
                baseCurrency,
                quoteCurrency,
                quote.rate(),
                quote.midRate(),
                quote.validFrom(),
                collectedAt
        );

        if (inserted == 0) {
            log.debug(
                    "이미 적재된 환율 시점이라 건너뜁니다: base={}, quote={}, rateAt={}",
                    baseCurrency, quoteCurrency, quote.validFrom()
            );
            return false;
        }
        return true;
    }

    private boolean isValid(ExchangeRateQuote quote) {
        return quote != null
                && quote.baseCurrency() != null
                && !quote.baseCurrency().isBlank()
                && quote.quoteCurrency() != null
                && !quote.quoteCurrency().isBlank()
                && quote.rate() != null
                && quote.rate().signum() > 0
                && (quote.midRate() == null || quote.midRate().signum() > 0)
                && quote.validFrom() != null;
    }
}
