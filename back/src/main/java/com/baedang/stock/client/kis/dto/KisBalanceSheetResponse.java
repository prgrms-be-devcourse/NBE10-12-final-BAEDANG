package com.baedang.stock.client.kis.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record KisBalanceSheetResponse(
        List<Output> output
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Output(
            @JsonProperty("stac_yymm") String statementYearMonth,
            @JsonProperty("cras") String currentAssets,
            @JsonProperty("fxas") String fixedAssets,
            @JsonProperty("total_aset") String totalAssets,
            @JsonProperty("flow_lblt") String currentLiabilities,
            @JsonProperty("fix_lblt") String fixedLiabilities,
            @JsonProperty("total_lblt") String totalLiabilities,
            @JsonProperty("cpfn") String capitalStock,
            @JsonProperty("cfp_surp") String capitalSurplus,
            @JsonProperty("prfi_surp") String retainedEarnings,
            @JsonProperty("total_cptl") String totalEquity
    ) {
    }
}
