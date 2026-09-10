package com.baedang.stock.port;

import java.math.BigDecimal;
import java.util.List;

import com.baedang.stock.entity.FinancialPeriodType;

public interface StockFinancialInfoPort {

    IndustryData fetchIndustry(String symbol);

    List<PeriodData> fetchFinancials(String symbol, FinancialPeriodType periodType);

    record IndustryData(
            IndustryClassification standard,
            IndustryClassification large,
            IndustryClassification medium,
            IndustryClassification small
    ) {
    }

    record IndustryClassification(String code, String name) {
    }

    record PeriodData(
            String statementYearMonth,
            BalanceSheet balanceSheet,
            IncomeStatement incomeStatement,
            Ratios ratios,
            boolean financialRatiosPresent,
            boolean profitRatiosPresent
    ) {
        public PeriodData {
            boolean ratioSourcePresent = financialRatiosPresent || profitRatiosPresent;
            if (ratioSourcePresent != (ratios != null)) {
                throw new IllegalArgumentException("Ratio values and source presence must agree");
            }
        }

        public PeriodData(
                String statementYearMonth,
                BalanceSheet balanceSheet,
                IncomeStatement incomeStatement,
                Ratios ratios
        ) {
            this(statementYearMonth, balanceSheet, incomeStatement, ratios,
                    ratios != null, ratios != null);
        }
    }

    record BalanceSheet(
            BigDecimal currentAssets,
            BigDecimal fixedAssets,
            BigDecimal totalAssets,
            BigDecimal currentLiabilities,
            BigDecimal fixedLiabilities,
            BigDecimal totalLiabilities,
            BigDecimal capitalStock,
            BigDecimal capitalSurplus,
            BigDecimal retainedEarnings,
            BigDecimal totalEquity
    ) {
    }

    record IncomeStatement(
            BigDecimal sales,
            BigDecimal operatingProfit,
            BigDecimal netIncome
    ) {
    }

    record Ratios(
            BigDecimal salesGrowthRate,
            BigDecimal operatingProfitGrowthRate,
            BigDecimal netIncomeGrowthRate,
            BigDecimal roe,
            BigDecimal eps,
            BigDecimal salesPerShare,
            BigDecimal bps,
            BigDecimal reserveRatio,
            BigDecimal debtRatio,
            BigDecimal netProfitMargin
    ) {
    }
}
