package com.baedang.stock.client.kis.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record KisFinancialRatioResponse(
        List<Output> output
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Output(
            @JsonProperty("stac_yymm") String statementYearMonth,
            @JsonProperty("grs") String salesGrowthRate,
            @JsonProperty("bsop_prfi_inrt") String operatingProfitGrowthRate,
            @JsonProperty("ntin_inrt") String netIncomeGrowthRate,
            @JsonProperty("roe_val") String roe,
            @JsonProperty("eps") String eps,
            @JsonProperty("sps") String salesPerShare,
            @JsonProperty("bps") String bps,
            @JsonProperty("rsrv_rate") String reserveRatio,
            @JsonProperty("lblt_rate") String debtRatio
    ) {
    }
}
