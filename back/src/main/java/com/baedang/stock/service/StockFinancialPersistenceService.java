package com.baedang.stock.service;

import com.baedang.stock.entity.FinancialPeriodType;
import com.baedang.stock.entity.StockFinancialPeriod;
import com.baedang.stock.entity.StockFinancialSync;
import com.baedang.stock.entity.StockIndustry;
import com.baedang.stock.port.StockFinancialInfoPort.IndustryData;
import com.baedang.stock.port.StockFinancialInfoPort.PeriodData;
import com.baedang.stock.repository.StockFinancialPeriodRepository;
import com.baedang.stock.repository.StockFinancialSyncRepository;
import com.baedang.stock.repository.StockIndustryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class StockFinancialPersistenceService {

    private final StockIndustryRepository industryRepository;
    private final StockFinancialPeriodRepository periodRepository;
    private final StockFinancialSyncRepository syncRepository;

    public StockFinancialPersistenceService(
            StockIndustryRepository industryRepository,
            StockFinancialPeriodRepository periodRepository,
            StockFinancialSyncRepository syncRepository) {
        this.industryRepository = industryRepository;
        this.periodRepository = periodRepository;
        this.syncRepository = syncRepository;
    }

    @Transactional
    public void saveIndustry(long stockId, IndustryData data, Instant syncedAt) {
        if (data == null || syncedAt == null) {
            throw new IllegalArgumentException("Industry data and sync time are required");
        }
        StockIndustry industry = industryRepository.findById(stockId)
                .map(existing -> {
                    existing.update(data, syncedAt);
                    return existing;
                })
                .orElseGet(() -> StockIndustry.create(stockId, data, syncedAt));
        industryRepository.save(industry);

        StockFinancialSync sync = syncRepository.findById(stockId)
                .orElseGet(() -> StockFinancialSync.create(stockId));
        sync.markIndustrySynced(syncedAt);
        syncRepository.save(sync);
    }

    @Transactional
    public void saveFinancials(long stockId, FinancialPeriodType periodType,
                               List<PeriodData> data, Instant syncedAt) {
        if (periodType == null || syncedAt == null) {
            throw new IllegalArgumentException("Period type and sync time are required");
        }
        Map<String, StockFinancialPeriod> existing = new HashMap<>();
        for (StockFinancialPeriod period : periodRepository
                .findByStockIdAndPeriodTypeOrderByStatementYearMonthDesc(stockId, periodType)) {
            existing.put(period.getStatementYearMonth(), period);
        }

        for (PeriodData source : Objects.requireNonNull(data, "data")) {
            Objects.requireNonNull(source, "period data");
            StockFinancialPeriod period = existing.computeIfAbsent(
                    source.statementYearMonth(),
                    month -> StockFinancialPeriod.create(stockId, periodType, month));
            applySource(period, source);
            periodRepository.save(period);
        }

        StockFinancialSync sync = syncRepository.findById(stockId)
                .orElseGet(() -> StockFinancialSync.create(stockId));
        sync.markFinancialSynced(periodType, syncedAt);
        syncRepository.save(sync);
    }

    private static void applySource(StockFinancialPeriod period, PeriodData source) {
        if (source.balanceSheet() != null) {
            period.applyBalanceSheet(source.balanceSheet());
        }
        if (source.incomeStatement() != null) {
            period.applyIncomeStatement(source.incomeStatement());
        }
        if (source.financialRatiosPresent()) {
            period.applyFinancialRatios(source.ratios());
        }
        if (source.profitRatiosPresent()) {
            period.applyProfitRatios(source.ratios());
        }
    }
}
