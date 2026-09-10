package com.baedang.stock.entity;

import java.math.BigDecimal;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.baedang.global.entity.BaseEntity;
import com.baedang.stock.port.StockFinancialInfoPort.BalanceSheet;
import com.baedang.stock.port.StockFinancialInfoPort.IncomeStatement;
import com.baedang.stock.port.StockFinancialInfoPort.Ratios;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

@Entity
@Table(name = "stock_financial_period")
@IdClass(StockFinancialPeriod.Pk.class)
public class StockFinancialPeriod extends BaseEntity {

    @Id
    @Column(name = "stock_id", nullable = false)
    private Long stockId;

    @Id
    @Enumerated(EnumType.STRING)
    @Column(name = "period_type", nullable = false, length = 10)
    private FinancialPeriodType periodType;

    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "statement_year_month", nullable = false, length = 6)
    private String statementYearMonth;

    @Column(name = "current_assets", precision = 30, scale = 6) private BigDecimal currentAssets;
    @Column(name = "total_assets", precision = 30, scale = 6) private BigDecimal totalAssets;
    @Column(name = "fixed_assets", precision = 30, scale = 6) private BigDecimal fixedAssets;
    @Column(name = "current_liabilities", precision = 30, scale = 6) private BigDecimal currentLiabilities;
    @Column(name = "fixed_liabilities", precision = 30, scale = 6) private BigDecimal fixedLiabilities;
    @Column(name = "total_liabilities", precision = 30, scale = 6) private BigDecimal totalLiabilities;
    @Column(name = "capital_stock", precision = 30, scale = 6) private BigDecimal capitalStock;
    @Column(name = "capital_surplus", precision = 30, scale = 6) private BigDecimal capitalSurplus;
    @Column(name = "retained_earnings", precision = 30, scale = 6) private BigDecimal retainedEarnings;
    @Column(name = "total_equity", precision = 30, scale = 6) private BigDecimal totalEquity;
    @Column(name = "sales", precision = 30, scale = 6) private BigDecimal sales;
    @Column(name = "operating_profit", precision = 30, scale = 6) private BigDecimal operatingProfit;
    @Column(name = "net_income", precision = 30, scale = 6) private BigDecimal netIncome;
    @Column(name = "sales_growth_rate", precision = 30, scale = 6) private BigDecimal salesGrowthRate;
    @Column(name = "operating_profit_growth_rate", precision = 30, scale = 6) private BigDecimal operatingProfitGrowthRate;
    @Column(name = "net_income_growth_rate", precision = 30, scale = 6) private BigDecimal netIncomeGrowthRate;
    @Column(name = "roe", precision = 30, scale = 6) private BigDecimal roe;
    @Column(name = "eps", precision = 30, scale = 6) private BigDecimal eps;
    @Column(name = "sales_per_share", precision = 30, scale = 6) private BigDecimal salesPerShare;
    @Column(name = "bps", precision = 30, scale = 6) private BigDecimal bps;
    @Column(name = "reserve_ratio", precision = 30, scale = 6) private BigDecimal reserveRatio;
    @Column(name = "debt_ratio", precision = 30, scale = 6) private BigDecimal debtRatio;
    @Column(name = "net_profit_margin", precision = 30, scale = 6) private BigDecimal netProfitMargin;

    protected StockFinancialPeriod() {
    }

    public static StockFinancialPeriod create(Long stockId, FinancialPeriodType periodType, String statementYearMonth) {
        if (stockId == null || periodType == null
                || statementYearMonth == null || !statementYearMonth.matches("[0-9]{6}")) {
            throw new IllegalArgumentException("Valid financial period keys are required");
        }
        StockFinancialPeriod period = new StockFinancialPeriod();
        period.stockId = stockId;
        period.periodType = periodType;
        period.statementYearMonth = statementYearMonth;
        return period;
    }

    public void applyBalanceSheet(BalanceSheet values) {
        if (values == null) return;
        currentAssets = values.currentAssets();
        fixedAssets = values.fixedAssets();
        totalAssets = values.totalAssets();
        currentLiabilities = values.currentLiabilities();
        fixedLiabilities = values.fixedLiabilities();
        totalLiabilities = values.totalLiabilities();
        capitalStock = values.capitalStock();
        capitalSurplus = values.capitalSurplus();
        retainedEarnings = values.retainedEarnings();
        totalEquity = values.totalEquity();
    }

    public void applyIncomeStatement(IncomeStatement values) {
        if (values == null) return;
        sales = values.sales();
        operatingProfit = values.operatingProfit();
        netIncome = values.netIncome();
    }

    public void applyFinancialRatios(Ratios values) {
        if (values == null) return;
        salesGrowthRate = values.salesGrowthRate();
        operatingProfitGrowthRate = values.operatingProfitGrowthRate();
        netIncomeGrowthRate = values.netIncomeGrowthRate();
        roe = values.roe();
        eps = values.eps();
        salesPerShare = values.salesPerShare();
        bps = values.bps();
        reserveRatio = values.reserveRatio();
        debtRatio = values.debtRatio();
    }

    public void applyProfitRatios(Ratios values) {
        if (values == null) return;
        netProfitMargin = values.netProfitMargin();
    }

    public Long getStockId() { return stockId; }
    public FinancialPeriodType getPeriodType() { return periodType; }
    public String getStatementYearMonth() { return statementYearMonth; }
    public BigDecimal getCurrentAssets() { return currentAssets; }
    public BigDecimal getFixedAssets() { return fixedAssets; }
    public BigDecimal getTotalAssets() { return totalAssets; }
    public BigDecimal getCurrentLiabilities() { return currentLiabilities; }
    public BigDecimal getFixedLiabilities() { return fixedLiabilities; }
    public BigDecimal getTotalLiabilities() { return totalLiabilities; }
    public BigDecimal getCapitalStock() { return capitalStock; }
    public BigDecimal getCapitalSurplus() { return capitalSurplus; }
    public BigDecimal getRetainedEarnings() { return retainedEarnings; }
    public BigDecimal getTotalEquity() { return totalEquity; }
    public BigDecimal getSales() { return sales; }
    public BigDecimal getOperatingProfit() { return operatingProfit; }
    public BigDecimal getNetIncome() { return netIncome; }
    public BigDecimal getSalesGrowthRate() { return salesGrowthRate; }
    public BigDecimal getOperatingProfitGrowthRate() { return operatingProfitGrowthRate; }
    public BigDecimal getNetIncomeGrowthRate() { return netIncomeGrowthRate; }
    public BigDecimal getRoe() { return roe; }
    public BigDecimal getEps() { return eps; }
    public BigDecimal getSalesPerShare() { return salesPerShare; }
    public BigDecimal getBps() { return bps; }
    public BigDecimal getReserveRatio() { return reserveRatio; }
    public BigDecimal getDebtRatio() { return debtRatio; }
    public BigDecimal getNetProfitMargin() { return netProfitMargin; }

    public static class Pk {
        private Long stockId;
        private FinancialPeriodType periodType;
        private String statementYearMonth;

        public Pk() {
        }

        public Pk(Long stockId, FinancialPeriodType periodType, String statementYearMonth) {
            this.stockId = stockId;
            this.periodType = periodType;
            this.statementYearMonth = statementYearMonth;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Pk that)) return false;
            return java.util.Objects.equals(stockId, that.stockId)
                    && periodType == that.periodType
                    && java.util.Objects.equals(statementYearMonth, that.statementYearMonth);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(stockId, periodType, statementYearMonth);
        }
    }
}
