package com.baedang.market.service;

import com.baedang.market.port.ExchangeRateQuote;
import com.baedang.market.port.MarketCalendarPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

@Service
public class ExchangeRateLoadService {

    private static final Logger log = LoggerFactory.getLogger(ExchangeRateLoadService.class);

    private final MarketCalendarPort marketCalendarPort;
    private final ExchangeRatePersistenceService exchangeRatePersistenceService;
    private final Clock clock;

    public ExchangeRateLoadService(
            MarketCalendarPort marketCalendarPort,
            ExchangeRatePersistenceService exchangeRatePersistenceService,
            Clock clock
    ) {
        this.marketCalendarPort = marketCalendarPort;
        this.exchangeRatePersistenceService = exchangeRatePersistenceService;
        this.clock = clock;
    }

    public boolean syncExchangeRate() {
        ExchangeRateQuote quote = marketCalendarPort.fetchExchangeRate();

        if (quote == null) {
            log.warn("외부 환율 응답이 null입니다.");
            return false;
        }

        OffsetDateTime collectedAt = clock.instant().atOffset(ZoneOffset.UTC);

        boolean inserted = exchangeRatePersistenceService.saveIfValid(quote, collectedAt);

        log.info("환율 동기화 완료: base={}, quote={}, rateAt={}, inserted={}",
                quote.baseCurrency(), quote.quoteCurrency(), quote.validFrom(), inserted);

        return inserted;
    }

}
