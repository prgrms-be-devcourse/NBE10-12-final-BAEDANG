package com.baedang.stock.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.baedang.global.clients.kis.KisProperties;
import com.baedang.global.error.BusinessException;
import com.baedang.global.error.ErrorCode;
import com.baedang.stock.entity.FinancialPeriodType;
import com.baedang.stock.entity.MarketCountry;
import com.baedang.stock.entity.Stock;
import com.baedang.stock.entity.StockCategory;
import com.baedang.stock.entity.StockFinancialSync;
import com.baedang.stock.port.StockFinancialInfoPort;
import com.baedang.stock.port.StockFinancialInfoPort.IndustryData;
import com.baedang.stock.port.StockFinancialInfoPort.PeriodData;
import com.baedang.stock.repository.StockFinancialSyncRepository;
import com.baedang.stock.repository.StockRepository;
import io.micrometer.core.instrument.Timer;

import io.micrometer.core.instrument.MeterRegistry;

@Service
public class StockFinancialSyncService {

    private static final Logger log = LoggerFactory.getLogger(StockFinancialSyncService.class);

    private final StockRepository stockRepository;
    private final Optional<StockFinancialInfoPort> port;
    private final StockFinancialSyncRepository syncRepository;
    private final StockFinancialPersistenceService persistenceService;
    private final Duration financialTtl;
    private final Duration industryTtl;
    private final Clock clock;
    private final MeterRegistry meterRegistry;
    private final Timer batchDurationTimer;
    private final ConcurrentHashMap<Long, SyncFlight> inFlight = new ConcurrentHashMap<>();

    public StockFinancialSyncService(
            StockRepository stockRepository,
            Optional<StockFinancialInfoPort> port,
            StockFinancialSyncRepository syncRepository,
            StockFinancialPersistenceService persistenceService,
            KisProperties properties,
            Clock clock,
            MeterRegistry meterRegistry
    ) {
        this.stockRepository = Objects.requireNonNull(stockRepository, "stockRepository");
        this.port = Objects.requireNonNull(port, "port");
        this.syncRepository = Objects.requireNonNull(syncRepository, "syncRepository");
        this.persistenceService = Objects.requireNonNull(persistenceService, "persistenceService");
        this.financialTtl = Objects.requireNonNull(properties, "properties").financialCacheTtl();
        this.industryTtl = properties.industryCacheTtl();
        this.clock = Objects.requireNonNull(clock, "clock");
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry");
        this.batchDurationTimer = Timer.builder("kis.financial.batch.duration")
                .description("KIS 재무정보 배치 소요시간")
                .register(meterRegistry);
    }

    public SyncResult ensureFresh(Stock stock, SyncTrigger trigger) {
        return execute(stock, trigger, false);
    }

    public SyncResult refresh(Stock stock, SyncTrigger trigger) {
        return execute(stock, trigger, true);
    }

    public BatchSummary refreshRankedTargets(SyncTrigger trigger) {
        Objects.requireNonNull(trigger, "trigger");
        long startedNanos = System.nanoTime();
        List<Stock> targets = stockRepository.findKisFinancialCollectionTargets();
        int total = targets.size();
        int success = 0;
        int empty = 0;
        int failure = 0;
        int skipped = 0;

        for (Stock stock : targets) {
            try {
                SyncResult result = refresh(stock, trigger);
                if (result.stale()) {
                    failure++;
                } else if (result.empty()) {
                    empty++;
                } else {
                    success++;
                }
            } catch (BusinessException exception) {
                if (exception.getErrorCode() == ErrorCode.FINANCIALS_NOT_SUPPORTED) {
                    skipped++;
                } else {
                    failure++;
                }
                log.warn("KIS batch target failed stockId={} symbol={} errorCode={}",
                        stock.getStockId(), stock.getSymbol(), exception.getErrorCode());
            } catch (Exception exception) {
                failure++;
                log.warn("KIS batch target unexpected error stockId={} symbol={}",
                        stock.getStockId(), stock.getSymbol(), exception);
            }
        }

        Duration duration = Duration.ofNanos(System.nanoTime() - startedNanos);
        batchDurationTimer.record(duration);
        log.info("KIS financial batch completed trigger={} targets={} success={} empty={} failure={} skipped={} durationMs={}",
                trigger, total, success, empty, failure, skipped, duration.toMillis());
        return new BatchSummary(total, success, empty, failure, skipped, duration);
    }

    private SyncResult execute(Stock stock, SyncTrigger trigger, boolean forceFinancials) {
        Objects.requireNonNull(stock, "stock");
        Objects.requireNonNull(trigger, "trigger");
        if (!isSupported(stock)) {
            record(trigger, "skipped");
            throw new BusinessException(ErrorCode.FINANCIALS_NOT_SUPPORTED);
        }
        Long stockId = Objects.requireNonNull(stock.getStockId(), "stockId");

        SyncFlight owned = new SyncFlight(forceFinancials, new CompletableFuture<>());
        while (true) {
            SyncFlight existing = inFlight.putIfAbsent(stockId, owned);
            if (existing == null) {
                break;
            }
            SyncResult shared = await(existing.future());
            if (!forceFinancials
                    || existing.forceFinancials()
                    || financialGroupsUpdated(shared)) {
                return shared;
            }
            inFlight.remove(stockId, existing);
        }

        try {
            SyncResult result = synchronize(stock, forceFinancials);
            owned.future().complete(result);
            record(trigger, result.stale() ? "error" : result.empty() ? "empty" : "success");
            return result;
        } catch (RuntimeException exception) {
            owned.future().completeExceptionally(exception);
            record(trigger, "error");
            throw exception;
        } finally {
            inFlight.remove(stockId, owned);
        }
    }

    private SyncResult synchronize(Stock stock, boolean forceFinancials) {
        Instant now = clock.instant();
        StockFinancialSync previous = syncRepository.findById(stock.getStockId()).orElse(null);
        boolean refreshIndustry = !isFresh(
                previous == null ? null : previous.getIndustrySyncedAt(), industryTtl, now);
        boolean refreshAnnual = forceFinancials || !isFresh(
                previous == null ? null : previous.getAnnualSyncedAt(), financialTtl, now);
        boolean refreshQuarterly = forceFinancials || !isFresh(
                previous == null ? null : previous.getQuarterlySyncedAt(), financialTtl, now);

        if (port.isEmpty() && (refreshIndustry || refreshAnnual || refreshQuarterly)) {
            throw new BusinessException(ErrorCode.KIS_API_UNAVAILABLE);
        }

        GroupResult industry = refreshIndustry
                ? synchronizeIndustry(stock)
                : GroupResult.fresh();
        GroupResult annual = refreshAnnual
                ? synchronizeFinancials(stock, FinancialPeriodType.ANNUAL)
                : GroupResult.fresh();
        GroupResult quarterly = refreshQuarterly
                ? synchronizeFinancials(stock, FinancialPeriodType.QUARTERLY)
                : GroupResult.fresh();
        return new SyncResult(industry, annual, quarterly);
    }

    private GroupResult synchronizeIndustry(Stock stock) {
        IndustryData data;
        try {
            data = port.orElseThrow().fetchIndustry(stock.getSymbol());
            if (data == null) {
                return failed(stock, "industry", ErrorCode.KIS_API_ERROR, "null_response");
            }
        } catch (RuntimeException exception) {
            return failed(stock, "industry", exception);
        }
        persistenceService.saveIndustry(stock.getStockId(), data, clock.instant());
        return GroupResult.updated(isEmpty(data));
    }

    private GroupResult synchronizeFinancials(Stock stock, FinancialPeriodType periodType) {
        List<PeriodData> data;
        try {
            data = port.orElseThrow().fetchFinancials(stock.getSymbol(), periodType);
            if (data == null) {
                return failed(stock, periodType.name().toLowerCase(),
                        ErrorCode.KIS_API_ERROR, "null_response");
            }
        } catch (RuntimeException exception) {
            return failed(stock, periodType.name().toLowerCase(), exception);
        }
        persistenceService.saveFinancials(stock.getStockId(), periodType, data, clock.instant());
        return GroupResult.updated(data.isEmpty());
    }

    private GroupResult failed(Stock stock, String group, RuntimeException exception) {
        if (exception instanceof BusinessException businessException) {
            ErrorCode errorCode = businessException.getErrorCode();
            if (errorCode == ErrorCode.KIS_API_ERROR || errorCode == ErrorCode.KIS_RATE_LIMITED) {
                return failed(stock, group, errorCode,
                        businessException.getClass().getSimpleName(), businessException.getDetail());
            }
            throw businessException;
        }
        return failed(stock, group, ErrorCode.KIS_API_ERROR,
                exception.getClass().getSimpleName());
    }

    private GroupResult failed(
            Stock stock, String group, ErrorCode errorCode, String failureType) {
        return failed(stock, group, errorCode, failureType, null);
    }

    private GroupResult failed(
            Stock stock, String group, ErrorCode errorCode, String failureType,
            String providerContext) {
        log.warn("KIS financial sync failed stockId={} symbol={} group={} errorCode={} failureType={} providerContext={}",
                stock.getStockId(), stock.getSymbol(), group, errorCode, failureType, providerContext);
        return GroupResult.failed(errorCode);
    }

    private void record(SyncTrigger trigger, String result) {
        meterRegistry.counter("kis.financial.sync",
                "trigger", trigger.tag,
                "result", result).increment();
    }

    private static boolean isSupported(Stock stock) {
        StockCategory category = stock.getStockCategory();
        return stock.getMarketCountry() == MarketCountry.KR
                && category != StockCategory.ETF
                && category != StockCategory.ETN
                && stock.getSymbol() != null
                && stock.getSymbol().matches("[0-9]{6}");
    }

    private static boolean isFresh(OffsetDateTime syncedAt, Duration ttl, Instant now) {
        return syncedAt != null && syncedAt.toInstant().plus(ttl).isAfter(now);
    }

    private static boolean isEmpty(IndustryData data) {
        return data.standard() == null
                && data.large() == null
                && data.medium() == null
                && data.small() == null;
    }

    private static boolean financialGroupsUpdated(SyncResult result) {
        return result.annual().status() == GroupStatus.UPDATED
                && result.quarterly().status() == GroupStatus.UPDATED;
    }

    private static SyncResult await(CompletableFuture<SyncResult> future) {
        try {
            return future.join();
        } catch (CompletionException exception) {
            if (exception.getCause() instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw exception;
        }
    }

    private record SyncFlight(
            boolean forceFinancials,
            CompletableFuture<SyncResult> future
    ) {
    }

    public enum SyncTrigger {
        SCHEDULED("scheduled"),
        ON_DEMAND("on_demand"),
        MANUAL("manual");

        private final String tag;

        SyncTrigger(String tag) {
            this.tag = tag;
        }
    }

    public enum GroupStatus {
        UPDATED,
        FRESH,
        FAILED
    }

    public record GroupResult(GroupStatus status, boolean empty, ErrorCode errorCode) {

        public static GroupResult fresh() {
            return new GroupResult(GroupStatus.FRESH, false, null);
        }

        public static GroupResult updated(boolean empty) {
            return new GroupResult(GroupStatus.UPDATED, empty, null);
        }

        public static GroupResult failed(ErrorCode errorCode) {
            return new GroupResult(GroupStatus.FAILED, false, errorCode);
        }
    }

    public record SyncResult(
            GroupResult industry,
            GroupResult annual,
            GroupResult quarterly
    ) {
        public boolean stale() {
            return industry.status() == GroupStatus.FAILED
                    || annual.status() == GroupStatus.FAILED
                    || quarterly.status() == GroupStatus.FAILED;
        }

        public boolean empty() {
            boolean updated = false;
            for (GroupResult group : List.of(industry, annual, quarterly)) {
                if (group.status() == GroupStatus.UPDATED) {
                    updated = true;
                    if (!group.empty()) return false;
                }
            }
            return updated;
        }
    }

    public record BatchSummary(
            int totalTargets,
            int successCount,
            int emptyCount,
            int failureCount,
            int skippedCount,
            Duration duration
    ) {
    }
}
