package com.baedang.market.scheduler;

import com.baedang.market.service.PriceLimitLoadService;
import com.baedang.stock.entity.MarketCountry;
import com.baedang.stock.entity.Stock;
import com.baedang.stock.repository.StockRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;

@Component
@ConditionalOnProperty(name = "toss.enabled", havingValue = "true")
public class PriceLimitScheduler {
    private static final Logger log = LoggerFactory.getLogger(PriceLimitScheduler.class);
    private final StockRepository stocks;
    private final PriceLimitLoadService service;
    private final Clock clock;

    public PriceLimitScheduler(StockRepository stocks, PriceLimitLoadService service, Clock clock) {
        this.stocks = stocks;
        this.service = service;
        this.clock = clock;
    }

    /** 시작 60초 후 실행하고, 이전 처리가 완료된 후 5분마다 누락을 확인합니다. */
    @Scheduled(initialDelayString = "60s", fixedDelayString = "5m", scheduler = "priceLimitTaskScheduler")
    public void recover() {
        try {
            long after = 0;
            while (true) {
                List<Stock> page = stocks.findQuoteTargets(MarketCountry.KR, after,
                        clock.instant().atOffset(ZoneOffset.UTC), PageRequest.of(0, 100));
                for (Stock stock : page) service.ensure(stock);
                if (page.size() < 100) return;
                after = page.getLast().getStockId();
            }
        } catch (RuntimeException exception) {
            log.warn("상하한가 대상 조회 실패: type={}", exception.getClass().getSimpleName());
        }
    }
}
