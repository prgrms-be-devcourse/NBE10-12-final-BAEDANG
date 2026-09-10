package com.baedang.stock.dto;

import java.time.OffsetDateTime;
import java.util.List;

public record StockFinancialResponse(
        String symbol,
        String marketCountry,
        String dataStatus,
        Industry industry,
        List<Period> annual,
        List<Period> quarterly,
        SyncedAt syncedAt
) {

    public record Industry(
            Classification standard,
            Classification large,
            Classification medium,
            Classification small
    ) {
    }

    public record Classification(
            String code,
            String name
    ) {
    }

    public record Period(
            String statementYearMonth,
            BalanceSheet balanceSheet,
            IncomeStatement incomeStatement,
            Ratios ratios
    ) {
    }

    public record BalanceSheet(
            String currentAssets,
            String fixedAssets,
            String totalAssets,
            String currentLiabilities,
            String fixedLiabilities,
            String totalLiabilities,
            String capitalStock,
            String capitalSurplus,
            String retainedEarnings,
            String totalEquity
    ) {
    }

    public record IncomeStatement(
            String sales,
            String operatingProfit,
            String netIncome
    ) {
    }

    public record Ratios(
            String salesGrowthRate,
            String operatingProfitGrowthRate,
            String netIncomeGrowthRate,
            String roe,
            String eps,
            String salesPerShare,
            String bps,
            String reserveRatio,
            String debtRatio,
            String netProfitMargin,
            String operatingProfitMargin
    ) {
    }

    public record SyncedAt(
            OffsetDateTime industry,
            OffsetDateTime annual,
            OffsetDateTime quarterly
    ) {
    }
}
