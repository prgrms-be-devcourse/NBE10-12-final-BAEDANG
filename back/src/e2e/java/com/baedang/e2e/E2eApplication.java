package com.baedang.e2e;

import com.baedang.TradingApplication;
import com.baedang.global.clients.kis.KisProperties;
import com.baedang.global.config.JpaConfig;
import com.baedang.global.config.SchedulingConfig;
import com.baedang.global.config.TimeConfig;
import com.baedang.market.config.QuoteCollectionProperties;
import com.baedang.stock.port.RankingPort;
import com.baedang.stock.port.RankingSnapshot;
import com.baedang.stock.port.StockFinancialInfoPort;
import com.baedang.stock.entity.FinancialPeriodType;
import com.baedang.trading.scheduler.LimitOrderExpirationScheduler;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.data.auditing.DateTimeProvider;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

/** 실제 도메인·HTTP 보안은 유지하고 외부 어댑터와 자동 작업 등록만 제외합니다. */
@SpringBootConfiguration
@EnableAutoConfiguration
@EntityScan("com.baedang")
@EnableJpaRepositories("com.baedang")
@EnableJpaAuditing(dateTimeProviderRef = "auditingDateTimeProvider")
@EnableConfigurationProperties({QuoteCollectionProperties.class, KisProperties.class})
@ComponentScan(basePackages = "com.baedang", excludeFilters = {
        @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = {
                TradingApplication.class, SchedulingConfig.class, TimeConfig.class, JpaConfig.class,
                LimitOrderExpirationScheduler.class}),
        @ComponentScan.Filter(type = FilterType.REGEX, pattern = {
                "com\\.baedang\\..*\\.client\\..*", "com\\.baedang\\.global\\.clients\\.(toss|kis)\\..*",
                "com\\.baedang\\.stock\\.runner\\..*"})
})
public class E2eApplication {
    @Bean public E2eClock clock() { return new E2eClock(); }
    @Bean public DateTimeProvider auditingDateTimeProvider(E2eClock clock) {
        return () -> Optional.of(clock.instant().atOffset(ZoneOffset.UTC));
    }
    @Bean(name = {"quoteCollectionExecutor", "exchangeRateRefreshExecutor", "dailyCandleTaskExecutor", "stockFinancialTaskExecutor"})
    public SyncTaskExecutor deterministicExecutor() { return new SyncTaskExecutor(); }
    @Bean public RankingPort rankings() { return country -> new RankingSnapshot(List.of(), null); }
    @Bean public StockFinancialInfoPort financials() {
        return new StockFinancialInfoPort() {
            @Override public IndustryData fetchIndustry(String symbol) { return new IndustryData(null, null, null, null); }
            @Override public List<PeriodData> fetchFinancials(String symbol, FinancialPeriodType type) { return List.of(); }
        };
    }
}
