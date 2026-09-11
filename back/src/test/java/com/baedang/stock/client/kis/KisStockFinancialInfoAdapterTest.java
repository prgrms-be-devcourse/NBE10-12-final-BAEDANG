package com.baedang.stock.client.kis;

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
import com.baedang.stock.port.StockFinancialInfoPort.BalanceSheet;
import com.baedang.stock.port.StockFinancialInfoPort.IndustryData;
import com.baedang.stock.port.StockFinancialInfoPort.PeriodData;
import com.baedang.stock.port.StockFinancialInfoPort.Ratios;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KisStockFinancialInfoAdapterTest {

    private static final String SYMBOL = "005930";
    private static final String INDUSTRY_PATH = "/uapi/domestic-stock/v1/quotations/search-stock-info";
    private static final String BALANCE_PATH = "/uapi/domestic-stock/v1/finance/balance-sheet";
    private static final String INCOME_PATH = "/uapi/domestic-stock/v1/finance/income-statement";
    private static final String FINANCIAL_RATIO_PATH = "/uapi/domestic-stock/v1/finance/financial-ratio";
    private static final String PROFIT_RATIO_PATH = "/uapi/domestic-stock/v1/finance/profit-ratio";

    private KisSecuritiesClient client;
    private KisStockFinancialInfoAdapter adapter;

    @BeforeEach
    void setUp() {
        client = Mockito.mock(KisSecuritiesClient.class);
        adapter = new KisStockFinancialInfoAdapter(client);
    }

    @Test
    void industry_maps_all_eight_code_and_name_fields() {
        when(client.get(
                eq(INDUSTRY_PATH),
                eq(Map.of("PRDT_TYPE_CD", "300", "PDNO", SYMBOL)),
                eq(KisStockInfoResponse.class)))
                .thenReturn(new KisStockInfoResponse(new KisStockInfoResponse.Output(
                        "STD", "표준산업", "L", "대", "M", "중", "S", "소")));

        IndustryData result = adapter.fetchIndustry(SYMBOL);

        assertThat(result.standard().code()).isEqualTo("STD");
        assertThat(result.standard().name()).isEqualTo("표준산업");
        assertThat(result.large().code()).isEqualTo("L");
        assertThat(result.large().name()).isEqualTo("대");
        assertThat(result.medium().code()).isEqualTo("M");
        assertThat(result.medium().name()).isEqualTo("중");
        assertThat(result.small().code()).isEqualTo("S");
        assertThat(result.small().name()).isEqualTo("소");
    }

    @Test
    void annual_financials_use_zero_and_merge_periods_descending() {
        Map<String, String> params = Map.of(
                "FID_DIV_CLS_CODE", "0",
                "fid_cond_mrkt_div_code", "J",
                "fid_input_iscd", SYMBOL);
        when(client.get(eq(BALANCE_PATH), eq(params), eq(KisBalanceSheetResponse.class)))
                .thenReturn(new KisBalanceSheetResponse(List.of(
                        balance("202512", "100", "200", "300", "40", "50", "90", "10", "99.99", "70", "210"),
                        balance("202412", "1", "2", "3", "", "", "", "", "", "", ""))));
        when(client.get(eq(INCOME_PATH), eq(params), eq(KisIncomeStatementResponse.class)))
                .thenReturn(new KisIncomeStatementResponse(List.of(income("202512", "1000", "100", "80"))));
        when(client.get(eq(FINANCIAL_RATIO_PATH), eq(params), eq(KisFinancialRatioResponse.class)))
                .thenReturn(new KisFinancialRatioResponse(List.of(ratio("202512", "1", "2", "3", "99.99", "5", "6", "7", "8", "9"))));
        when(client.get(eq(PROFIT_RATIO_PATH), eq(params), eq(KisProfitRatioResponse.class)))
                .thenReturn(new KisProfitRatioResponse(List.of(profit("202512", "11", "12", "13", "14"))));

        List<PeriodData> result = adapter.fetchFinancials(SYMBOL, FinancialPeriodType.ANNUAL);

        assertThat(result).extracting(PeriodData::statementYearMonth)
                .containsExactly("202512", "202412");
        PeriodData latest = result.get(0);
        assertThat(latest.balanceSheet().currentAssets()).isEqualByComparingTo("100");
        assertThat(latest.balanceSheet().capitalSurplus()).isNull();
        assertThat(latest.incomeStatement().operatingProfit()).isEqualByComparingTo("100");
        assertThat(latest.ratios().roe()).isEqualByComparingTo("99.99");
        assertThat(latest.ratios().netProfitMargin()).isEqualByComparingTo("13");
        assertThat(result.get(1).incomeStatement()).isNull();
        assertThat(latest.financialRatiosPresent()).isTrue();
        assertThat(latest.profitRatiosPresent()).isTrue();
        verify(client).get(eq(BALANCE_PATH), eq(params), eq(KisBalanceSheetResponse.class));
        verify(client).get(eq(INCOME_PATH), eq(params), eq(KisIncomeStatementResponse.class));
        verify(client).get(eq(FINANCIAL_RATIO_PATH), eq(params), eq(KisFinancialRatioResponse.class));
        verify(client).get(eq(PROFIT_RATIO_PATH), eq(params), eq(KisProfitRatioResponse.class));
    }

    @Test
    void quarterly_financials_use_one_and_pass_market_and_symbol() {
        Map<String, String> params = Map.of(
                "FID_DIV_CLS_CODE", "1",
                "fid_cond_mrkt_div_code", "J",
                "fid_input_iscd", SYMBOL);
        when(client.get(eq(BALANCE_PATH), eq(params), eq(KisBalanceSheetResponse.class)))
                .thenReturn(new KisBalanceSheetResponse(List.of()));
        when(client.get(eq(INCOME_PATH), eq(params), eq(KisIncomeStatementResponse.class)))
                .thenReturn(new KisIncomeStatementResponse(List.of()));
        when(client.get(eq(FINANCIAL_RATIO_PATH), eq(params), eq(KisFinancialRatioResponse.class)))
                .thenReturn(new KisFinancialRatioResponse(List.of()));
        when(client.get(eq(PROFIT_RATIO_PATH), eq(params), eq(KisProfitRatioResponse.class)))
                .thenReturn(new KisProfitRatioResponse(List.of()));

        assertThat(adapter.fetchFinancials(SYMBOL, FinancialPeriodType.QUARTERLY)).isEmpty();
        verify(client).get(eq(BALANCE_PATH), eq(params), eq(KisBalanceSheetResponse.class));
    }

    @Test
    void malformed_number_is_kis_api_error() {
        Map<String, String> params = financialParams("0");
        stubRemainingFinancialResponses(params);
        when(client.get(eq(BALANCE_PATH), eq(params), eq(KisBalanceSheetResponse.class)))
                .thenReturn(new KisBalanceSheetResponse(List.of(
                        balance("202512", "not-a-number", "", "", "", "", "", "", "", "", ""))));

        assertKisApiError(() -> adapter.fetchFinancials(SYMBOL, FinancialPeriodType.ANNUAL));
    }

    @Test
    void malformed_period_is_kis_api_error() {
        Map<String, String> params = financialParams("0");
        stubRemainingFinancialResponses(params);
        when(client.get(eq(BALANCE_PATH), eq(params), eq(KisBalanceSheetResponse.class)))
                .thenReturn(new KisBalanceSheetResponse(List.of(
                        balance("20251", "1", "", "", "", "", "", "", "", "", ""))));

        assertKisApiError(() -> adapter.fetchFinancials(SYMBOL, FinancialPeriodType.ANNUAL));
    }

    @Test
    void numeric_values_outside_database_scale_are_rejected() {
        Map<String, String> params = financialParams("0");
        stubRemainingFinancialResponses(params);
        when(client.get(eq(BALANCE_PATH), eq(params), eq(KisBalanceSheetResponse.class)))
                .thenReturn(new KisBalanceSheetResponse(List.of(
                        balance("202512", "1.0000001", "", "", "", "", "", "", "", "", ""))));

        assertKisApiError(() -> adapter.fetchFinancials(SYMBOL, FinancialPeriodType.ANNUAL));
    }

    @Test
    void disjoint_ratio_sources_retain_their_presence() {
        Map<String, String> params = financialParams("0");
        when(client.get(eq(BALANCE_PATH), eq(params), eq(KisBalanceSheetResponse.class)))
                .thenReturn(new KisBalanceSheetResponse(List.of()));
        when(client.get(eq(INCOME_PATH), eq(params), eq(KisIncomeStatementResponse.class)))
                .thenReturn(new KisIncomeStatementResponse(List.of()));
        when(client.get(eq(FINANCIAL_RATIO_PATH), eq(params), eq(KisFinancialRatioResponse.class)))
                .thenReturn(new KisFinancialRatioResponse(List.of(
                        ratio("202512", "1", "2", "3", "4", "5", "6", "7", "8", "9"))));
        when(client.get(eq(PROFIT_RATIO_PATH), eq(params), eq(KisProfitRatioResponse.class)))
                .thenReturn(new KisProfitRatioResponse(List.of(profit("202412", "", "", "13", ""))));

        List<PeriodData> result = adapter.fetchFinancials(SYMBOL, FinancialPeriodType.ANNUAL);

        assertThat(result.get(0).financialRatiosPresent()).isTrue();
        assertThat(result.get(0).profitRatiosPresent()).isFalse();
        assertThat(result.get(0).ratios().netProfitMargin()).isNull();
        assertThat(result.get(1).financialRatiosPresent()).isFalse();
        assertThat(result.get(1).profitRatiosPresent()).isTrue();
        assertThat(result.get(1).ratios().roe()).isNull();
        assertThat(result.get(1).ratios().netProfitMargin()).isEqualByComparingTo("13");
    }

    @Test
    void failure_in_one_financial_api_is_propagated_without_partial_result() {
        Map<String, String> params = Map.of(
                "FID_DIV_CLS_CODE", "0",
                "fid_cond_mrkt_div_code", "J",
                "fid_input_iscd", SYMBOL);
        when(client.get(eq(BALANCE_PATH), eq(params), eq(KisBalanceSheetResponse.class)))
                .thenReturn(new KisBalanceSheetResponse(List.of(balance("202512", "1", "", "", "", "", "", "", "", "", ""))));
        when(client.get(eq(INCOME_PATH), eq(params), eq(KisIncomeStatementResponse.class)))
                .thenThrow(new BusinessException(ErrorCode.KIS_API_ERROR));

        assertThatThrownBy(() -> adapter.fetchFinancials(SYMBOL, FinancialPeriodType.ANNUAL))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(ErrorCode.KIS_API_ERROR);
    }

    private static Map<String, String> financialParams(String periodCode) {
        return Map.of(
                "FID_DIV_CLS_CODE", periodCode,
                "fid_cond_mrkt_div_code", "J",
                "fid_input_iscd", SYMBOL);
    }

    private void stubRemainingFinancialResponses(Map<String, String> params) {
        when(client.get(eq(INCOME_PATH), eq(params), eq(KisIncomeStatementResponse.class)))
                .thenReturn(new KisIncomeStatementResponse(List.of()));
        when(client.get(eq(FINANCIAL_RATIO_PATH), eq(params), eq(KisFinancialRatioResponse.class)))
                .thenReturn(new KisFinancialRatioResponse(List.of()));
        when(client.get(eq(PROFIT_RATIO_PATH), eq(params), eq(KisProfitRatioResponse.class)))
                .thenReturn(new KisProfitRatioResponse(List.of()));
    }

    private static void assertKisApiError(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(ErrorCode.KIS_API_ERROR);
    }

    private static KisBalanceSheetResponse.Output balance(
            String period, String currentAssets, String fixedAssets, String totalAssets,
            String currentLiabilities, String fixedLiabilities, String totalLiabilities,
            String capitalStock, String capitalSurplus, String retainedEarnings, String totalEquity) {
        return new KisBalanceSheetResponse.Output(period, currentAssets, fixedAssets, totalAssets,
                currentLiabilities, fixedLiabilities, totalLiabilities, capitalStock,
                capitalSurplus, retainedEarnings, totalEquity);
    }

    private static KisIncomeStatementResponse.Output income(
            String period, String sales, String operatingProfit, String netIncome) {
        return new KisIncomeStatementResponse.Output(period, sales, operatingProfit, netIncome);
    }

    private static KisFinancialRatioResponse.Output ratio(
            String period, String salesGrowth, String operatingGrowth, String netIncomeGrowth,
            String roe, String eps, String salesPerShare, String bps,
            String reserveRatio, String debtRatio) {
        return new KisFinancialRatioResponse.Output(period, salesGrowth, operatingGrowth,
                netIncomeGrowth, roe, eps, salesPerShare, bps, reserveRatio, debtRatio);
    }

    private static KisProfitRatioResponse.Output profit(
            String period, String capitalNetIncome, String equityNetIncome,
            String salesNetIncome, String salesGross) {
        return new KisProfitRatioResponse.Output(period, capitalNetIncome, equityNetIncome,
                salesNetIncome, salesGross);
    }
}
