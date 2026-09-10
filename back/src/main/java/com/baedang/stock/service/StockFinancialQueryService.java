package com.baedang.stock.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.baedang.global.clients.kis.KisProperties;
import com.baedang.global.error.BusinessException;
import com.baedang.global.error.ErrorCode;
import com.baedang.global.formatter.FinancialDecimalFormatter;
import com.baedang.stock.dto.StockFinancialResponse;
import com.baedang.stock.dto.StockFinancialResponse.BalanceSheet;
import com.baedang.stock.dto.StockFinancialResponse.Classification;
import com.baedang.stock.dto.StockFinancialResponse.IncomeStatement;
import com.baedang.stock.dto.StockFinancialResponse.Industry;
import com.baedang.stock.dto.StockFinancialResponse.Period;
import com.baedang.stock.dto.StockFinancialResponse.Ratios;
import com.baedang.stock.dto.StockFinancialResponse.SyncedAt;
import com.baedang.stock.entity.FinancialPeriodType;
import com.baedang.stock.entity.MarketCountry;
import com.baedang.stock.entity.Stock;
import com.baedang.stock.entity.StockCategory;
import com.baedang.stock.entity.StockFinancialPeriod;
import com.baedang.stock.entity.StockFinancialSync;
import com.baedang.stock.entity.StockIndustry;
import com.baedang.stock.repository.StockFinancialPeriodRepository;
import com.baedang.stock.repository.StockFinancialSyncRepository;
import com.baedang.stock.repository.StockIndustryRepository;
import com.baedang.stock.repository.StockRepository;
import com.baedang.stock.service.StockFinancialSyncService.GroupStatus;
import com.baedang.stock.service.StockFinancialSyncService.SyncResult;
import com.baedang.stock.service.StockFinancialSyncService.SyncTrigger;

@Service
public class StockFinancialQueryService {

    private final StockRepository stockRepository;
    private final StockFinancialSyncService syncService;
    private final StockIndustryRepository industryRepository;
    private final StockFinancialPeriodRepository periodRepository;
    private final StockFinancialSyncRepository syncRepository;
    private final TransactionTemplate snapshotTransaction;
    private final boolean kisEnabled;
    private final Duration financialTtl;
    private final Duration industryTtl;
    private final Clock clock;

    public StockFinancialQueryService(
            StockRepository stockRepository,
            StockFinancialSyncService syncService,
            StockIndustryRepository industryRepository,
            StockFinancialPeriodRepository periodRepository,
            StockFinancialSyncRepository syncRepository,
            PlatformTransactionManager transactionManager,
            KisProperties kisProperties,
            Clock clock
    ) {
        this.stockRepository = Objects.requireNonNull(stockRepository, "stockRepository");
        this.syncService = Objects.requireNonNull(syncService, "syncService");
        this.industryRepository = Objects.requireNonNull(industryRepository, "industryRepository");
        this.periodRepository = Objects.requireNonNull(periodRepository, "periodRepository");
        this.syncRepository = Objects.requireNonNull(syncRepository, "syncRepository");
        this.snapshotTransaction = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "transactionManager"));
        this.snapshotTransaction.setReadOnly(true);
        this.snapshotTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        Objects.requireNonNull(kisProperties, "kisProperties");
        this.kisEnabled = kisProperties.enabled();
        this.financialTtl = kisProperties.financialCacheTtl();
        this.industryTtl = kisProperties.industryCacheTtl();
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public StockFinancialResponse getFinancials(String symbol, String marketCountryStr) {
        if (symbol == null || symbol.isBlank() || marketCountryStr == null || marketCountryStr.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
        MarketCountry marketCountry;
        try {
            marketCountry = MarketCountry.valueOf(marketCountryStr.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }

        validateRequestSupported(symbol, marketCountry);

        Stock stock = stockRepository.findBySymbolIgnoreCaseAndMarketCountry(symbol, marketCountry)
                .orElseThrow(() -> new BusinessException(ErrorCode.STOCK_NOT_FOUND));

        validateSupportedCategory(stock);

        Long stockId = stock.getStockId();
        String dataStatus = resolveDataStatus(stock, stockId);

        return snapshotTransaction.execute(ignored -> assembleResponse(stock, stockId, dataStatus));
    }

    private static void validateRequestSupported(String symbol, MarketCountry marketCountry) {
        if (marketCountry != MarketCountry.KR || !symbol.matches("[0-9]{6}")) {
            throw new BusinessException(ErrorCode.FINANCIALS_NOT_SUPPORTED);
        }
    }

    private static void validateSupportedCategory(Stock stock) {
        StockCategory category = stock.getStockCategory();
        if (category == StockCategory.ETF || category == StockCategory.ETN) {
            throw new BusinessException(ErrorCode.FINANCIALS_NOT_SUPPORTED);
        }
    }

    private String resolveDataStatus(Stock stock, Long stockId) {
        Instant now = clock.instant();
        StockFinancialSync sync = syncRepository.findById(stockId).orElse(null);

        if (!kisEnabled) {
            boolean hasIndustry = sync != null && sync.getIndustrySyncedAt() != null;
            boolean hasAnnual = sync != null && sync.getAnnualSyncedAt() != null;
            boolean hasQuarterly = sync != null && sync.getQuarterlySyncedAt() != null;
            if (!hasIndustry || !hasAnnual || !hasQuarterly) {
                throw new BusinessException(ErrorCode.KIS_API_UNAVAILABLE);
            }
            boolean fresh = isFresh(sync.getIndustrySyncedAt(), industryTtl, now)
                    && isFresh(sync.getAnnualSyncedAt(), financialTtl, now)
                    && isFresh(sync.getQuarterlySyncedAt(), financialTtl, now);
            return fresh ? "FRESH" : "STALE";
        }

        boolean hasPreviousIndustry = sync != null && sync.getIndustrySyncedAt() != null;
        boolean hasPreviousAnnual = sync != null && sync.getAnnualSyncedAt() != null;
        boolean hasPreviousQuarterly = sync != null && sync.getQuarterlySyncedAt() != null;

        SyncResult syncResult = syncService.ensureFresh(stock, SyncTrigger.ON_DEMAND);

        if (syncResult.stale()) {
            ErrorCode unbackedError = null;
            if (syncResult.industry().status() == GroupStatus.FAILED && !hasPreviousIndustry) {
                unbackedError = prioritizeError(unbackedError, syncResult.industry().errorCode());
            }
            if (syncResult.annual().status() == GroupStatus.FAILED && !hasPreviousAnnual) {
                unbackedError = prioritizeError(unbackedError, syncResult.annual().errorCode());
            }
            if (syncResult.quarterly().status() == GroupStatus.FAILED && !hasPreviousQuarterly) {
                unbackedError = prioritizeError(unbackedError, syncResult.quarterly().errorCode());
            }
            if (unbackedError != null) {
                throw new BusinessException(unbackedError);
            }
            return "STALE";
        }

        return "FRESH";
    }

    private static ErrorCode prioritizeError(ErrorCode existing, ErrorCode next) {
        if (existing == ErrorCode.KIS_RATE_LIMITED || next == ErrorCode.KIS_RATE_LIMITED) {
            return ErrorCode.KIS_RATE_LIMITED;
        }
        return next != null ? next : (existing != null ? existing : ErrorCode.KIS_API_ERROR);
    }

    private static boolean isFresh(OffsetDateTime syncedAt, Duration ttl, Instant now) {
        return syncedAt != null && syncedAt.toInstant().plus(ttl).isAfter(now);
    }

    private StockFinancialResponse assembleResponse(Stock stock, Long stockId, String dataStatus) {
        StockIndustry industryEntity = industryRepository.findById(stockId).orElse(null);
        Industry industry = toIndustry(industryEntity);

        List<StockFinancialPeriod> annualEntities = periodRepository
                .findByStockIdAndPeriodTypeOrderByStatementYearMonthDesc(stockId, FinancialPeriodType.ANNUAL);
        List<Period> annual = annualEntities.stream().map(this::toPeriod).toList();

        List<StockFinancialPeriod> quarterlyEntities = periodRepository
                .findByStockIdAndPeriodTypeOrderByStatementYearMonthDesc(stockId, FinancialPeriodType.QUARTERLY);
        List<Period> quarterly = quarterlyEntities.stream().map(this::toPeriod).toList();

        StockFinancialSync sync = syncRepository.findById(stockId).orElse(null);
        SyncedAt syncedAt = new SyncedAt(
                sync == null ? null : sync.getIndustrySyncedAt(),
                sync == null ? null : sync.getAnnualSyncedAt(),
                sync == null ? null : sync.getQuarterlySyncedAt()
        );

        return new StockFinancialResponse(
                stock.getSymbol(),
                stock.getMarketCountry().name(),
                dataStatus,
                industry,
                annual,
                quarterly,
                syncedAt
        );
    }

    private static Industry toIndustry(StockIndustry entity) {
        if (entity == null) {
            return null;
        }
        Classification standard = toClassification(entity.getStandardIndustryCode(), entity.getStandardIndustryName());
        Classification large = toClassification(entity.getIndexIndustryLargeCode(), entity.getIndexIndustryLargeName());
        Classification medium = toClassification(entity.getIndexIndustryMediumCode(), entity.getIndexIndustryMediumName());
        Classification small = toClassification(entity.getIndexIndustrySmallCode(), entity.getIndexIndustrySmallName());
        if (standard == null && large == null && medium == null && small == null) {
            return null;
        }
        return new Industry(standard, large, medium, small);
    }

    private static Classification toClassification(String code, String name) {
        if (code == null && name == null) {
            return null;
        }
        return new Classification(code, name);
    }

    private Period toPeriod(StockFinancialPeriod entity) {
        BalanceSheet balanceSheet = new BalanceSheet(
                FinancialDecimalFormatter.plain(entity.getCurrentAssets()),
                FinancialDecimalFormatter.plain(entity.getFixedAssets()),
                FinancialDecimalFormatter.plain(entity.getTotalAssets()),
                FinancialDecimalFormatter.plain(entity.getCurrentLiabilities()),
                FinancialDecimalFormatter.plain(entity.getFixedLiabilities()),
                FinancialDecimalFormatter.plain(entity.getTotalLiabilities()),
                FinancialDecimalFormatter.plain(entity.getCapitalStock()),
                FinancialDecimalFormatter.plain(entity.getCapitalSurplus()),
                FinancialDecimalFormatter.plain(entity.getRetainedEarnings()),
                FinancialDecimalFormatter.plain(entity.getTotalEquity())
        );

        IncomeStatement incomeStatement = new IncomeStatement(
                FinancialDecimalFormatter.plain(entity.getSales()),
                FinancialDecimalFormatter.plain(entity.getOperatingProfit()),
                FinancialDecimalFormatter.plain(entity.getNetIncome())
        );

        BigDecimal operatingProfitMargin = calculateOperatingProfitMargin(
                entity.getOperatingProfit(), entity.getSales());

        Ratios ratios = new Ratios(
                FinancialDecimalFormatter.plain(entity.getSalesGrowthRate()),
                FinancialDecimalFormatter.plain(entity.getOperatingProfitGrowthRate()),
                FinancialDecimalFormatter.plain(entity.getNetIncomeGrowthRate()),
                FinancialDecimalFormatter.plain(entity.getRoe()),
                FinancialDecimalFormatter.plain(entity.getEps()),
                FinancialDecimalFormatter.plain(entity.getSalesPerShare()),
                FinancialDecimalFormatter.plain(entity.getBps()),
                FinancialDecimalFormatter.plain(entity.getReserveRatio()),
                FinancialDecimalFormatter.plain(entity.getDebtRatio()),
                FinancialDecimalFormatter.plain(entity.getNetProfitMargin()),
                FinancialDecimalFormatter.plain(operatingProfitMargin)
        );

        return new Period(
                entity.getStatementYearMonth(),
                balanceSheet,
                incomeStatement,
                ratios
        );
    }

    static BigDecimal calculateOperatingProfitMargin(BigDecimal operatingProfit, BigDecimal sales) {
        if (operatingProfit == null || sales == null || sales.signum() == 0) {
            return null;
        }
        return operatingProfit.multiply(new BigDecimal("100")).divide(sales, 6, RoundingMode.HALF_UP);
    }
}
