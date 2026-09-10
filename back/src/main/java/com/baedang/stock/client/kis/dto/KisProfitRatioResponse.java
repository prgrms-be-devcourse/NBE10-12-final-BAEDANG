package com.baedang.stock.client.kis.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record KisProfitRatioResponse(
        List<Output> output
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Output(
            @JsonProperty("stac_yymm") String statementYearMonth,
            @JsonProperty("cptl_ntin_rate") String capitalNetIncomeRate,
            @JsonProperty("self_cptl_ntin_inrt") String equityNetIncomeRate,
            @JsonProperty("sale_ntin_rate") String salesNetIncomeRate,
            @JsonProperty("sale_totl_rate") String salesGrossRate
    ) {
    }
}
