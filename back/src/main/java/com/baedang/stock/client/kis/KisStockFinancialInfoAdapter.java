package com.baedang.stock.client.kis;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.baedang.global.clients.kis.KisSecuritiesClient;
import com.baedang.global.error.BusinessException;
import com.baedang.global.error.ErrorCode;
import com.baedang.stock.client.kis.dto.KisBalanceSheetResponse;
import com.baedang.stock.client.kis.dto.KisFinancialRatioResponse;
import com.baedang.stock.client.kis.dto.KisIncomeStatementResponse;
import com.baedang.stock.client.kis.dto.KisProfitRatioResponse;
import com.baedang.stock.client.kis.dto.KisStockInfoResponse;
import com.baedang.stock.entity.FinancialPeriodType;
import com.baedang.stock.port.StockFinancialInfoPort;

@Component
public class KisStockFinancialInfoAdapter implements StockFinancialInfoPort {

    private static final String INDUSTRY_PATH = "/uapi/domestic-stock/v1/quotations/search-stock-info";
    private static final String BALANCE_PATH = "/uapi/domestic-stock/v1/finance/balance-sheet";
    private static final String INCOME_PATH = "/uapi/domestic-stock/v1/finance/income-statement";
    private static final String FINANCIAL_RATIO_PATH = "/uapi/domestic-stock/v1/finance/financial-ratio";
    private static final String PROFIT_RATIO_PATH = "/uapi/domestic-stock/v1/finance/profit-ratio";

    private final KisSecuritiesClient client;

    public KisStockFinancialInfoAdapter(KisSecuritiesClient client) {
        this.client = client;
    }

    @Override
    public IndustryData fetchIndustry(String symbol) {
        KisStockInfoResponse response = client.get(
                INDUSTRY_PATH,
                Map.of("PRDT_TYPE_CD", "300", "PDNO", symbol),
                KisStockInfoResponse.class);
        KisStockInfoResponse.Output output = response == null ? null : response.output();
        if (output == null) {
            return new IndustryData(null, null, null, null);
        }
        return new IndustryData(
                new IndustryClassification(output.standardIndustryCode(), output.standardIndustryName()),
                new IndustryClassification(output.largeIndustryCode(), output.largeIndustryName()),
                new IndustryClassification(output.mediumIndustryCode(), output.mediumIndustryName()),
                new IndustryClassification(output.smallIndustryCode(), output.smallIndustryName()));
    }

    @Override
    public List<PeriodData> fetchFinancials(String symbol, FinancialPeriodType periodType) {
        if (symbol == null || symbol.isBlank() || periodType == null) {
            throw new BusinessException(ErrorCode.KIS_API_ERROR);
        }
        Map<String, String> params = Map.of(
                "FID_DIV_CLS_CODE", periodType.kisCode(),
                "fid_cond_mrkt_div_code", "J",
                "fid_input_iscd", symbol);

        KisBalanceSheetResponse balance = client.get(BALANCE_PATH, params, KisBalanceSheetResponse.class);
        KisIncomeStatementResponse income = client.get(INCOME_PATH, params, KisIncomeStatementResponse.class);
        KisFinancialRatioResponse financialRatio = client.get(
                FINANCIAL_RATIO_PATH, params, KisFinancialRatioResponse.class);
        KisProfitRatioResponse profitRatio = client.get(
                PROFIT_RATIO_PATH, params, KisProfitRatioResponse.class);

        Map<String, MutablePeriod> periods = new LinkedHashMap<>();
        for (KisBalanceSheetResponse.Output output : outputs(balance)) {
            MutablePeriod period = period(periods, output.statementYearMonth());
            period.balanceSheet = new BalanceSheet(
                    number(output.currentAssets()),
                    number(output.fixedAssets()),
                    number(output.totalAssets()),
                    number(output.currentLiabilities()),
                    number(output.fixedLiabilities()),
                    number(output.totalLiabilities()),
                    number(output.capitalStock()),
                    number(output.capitalSurplus(), true),
                    number(output.retainedEarnings(), true),
                    number(output.totalEquity()));
        }
        for (KisIncomeStatementResponse.Output output : outputs(income)) {
            MutablePeriod period = period(periods, output.statementYearMonth());
            period.incomeStatement = new IncomeStatement(
                    number(output.sales()),
                    number(output.operatingProfit()),
                    number(output.netIncome()));
        }
        for (KisFinancialRatioResponse.Output output : outputs(financialRatio)) {
            MutablePeriod period = period(periods, output.statementYearMonth());
            Ratios existing = period.ratios == null ? emptyRatios() : period.ratios;
            period.ratios = new Ratios(
                    number(output.salesGrowthRate()),
                    number(output.operatingProfitGrowthRate()),
                    number(output.netIncomeGrowthRate()),
                    number(output.roe()),
                    number(output.eps()),
                    number(output.salesPerShare()),
                    number(output.bps()),
                    number(output.reserveRatio()),
                    number(output.debtRatio()),
                    existing.netProfitMargin());
            period.financialRatiosPresent = true;
        }
        for (KisProfitRatioResponse.Output output : outputs(profitRatio)) {
            MutablePeriod period = period(periods, output.statementYearMonth());
            Ratios existing = period.ratios == null ? emptyRatios() : period.ratios;
            period.ratios = new Ratios(
                    existing.salesGrowthRate(),
                    existing.operatingProfitGrowthRate(),
                    existing.netIncomeGrowthRate(),
                    existing.roe(),
                    existing.eps(),
                    existing.salesPerShare(),
                    existing.bps(),
                    existing.reserveRatio(),
                    existing.debtRatio(),
                    number(output.salesNetIncomeRate()));
            period.profitRatiosPresent = true;
        }

        return periods.values().stream()
                .sorted(Comparator.comparing(MutablePeriod::statementYearMonth).reversed())
                .map(MutablePeriod::toPeriodData)
                .toList();
    }

    private static MutablePeriod period(Map<String, MutablePeriod> periods, String statementYearMonth) {
        if (statementYearMonth == null || !statementYearMonth.matches("\\d{6}")) {
            throw new BusinessException(ErrorCode.KIS_API_ERROR);
        }
        return periods.computeIfAbsent(statementYearMonth, MutablePeriod::new);
    }

    private static Ratios emptyRatios() {
        return new Ratios(null, null, null, null, null, null, null, null, null, null);
    }

    private static BigDecimal number(String raw) {
        return number(raw, false);
    }

    private static BigDecimal number(String raw, boolean nullSentinel99) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String normalized = raw.trim();
        if (nullSentinel99 && normalized.equals("99.99")) {
            return null;
        }
        try {
            BigDecimal value = new BigDecimal(normalized);
            BigDecimal canonical = value.stripTrailingZeros();
            if (canonical.scale() > 6 || canonical.precision() - canonical.scale() > 24) {
                throw new BusinessException(ErrorCode.KIS_API_ERROR);
            }
            return value;
        } catch (NumberFormatException exception) {
            throw new BusinessException(ErrorCode.KIS_API_ERROR);
        }
    }

    private static List<KisBalanceSheetResponse.Output> outputs(KisBalanceSheetResponse response) {
        return response == null || response.output() == null ? List.of() : response.output();
    }

    private static List<KisIncomeStatementResponse.Output> outputs(KisIncomeStatementResponse response) {
        return response == null || response.output() == null ? List.of() : response.output();
    }

    private static List<KisFinancialRatioResponse.Output> outputs(KisFinancialRatioResponse response) {
        return response == null || response.output() == null ? List.of() : response.output();
    }

    private static List<KisProfitRatioResponse.Output> outputs(KisProfitRatioResponse response) {
        return response == null || response.output() == null ? List.of() : response.output();
    }

    private static final class MutablePeriod {
        private final String statementYearMonth;
        private BalanceSheet balanceSheet;
        private IncomeStatement incomeStatement;
        private Ratios ratios;
        private boolean financialRatiosPresent;
        private boolean profitRatiosPresent;

        private MutablePeriod(String statementYearMonth) {
            this.statementYearMonth = statementYearMonth;
        }

        private String statementYearMonth() {
            return statementYearMonth;
        }

        private PeriodData toPeriodData() {
            return new PeriodData(
                    statementYearMonth,
                    balanceSheet,
                    incomeStatement,
                    ratios,
                    financialRatiosPresent,
                    profitRatiosPresent);
        }
    }
}
