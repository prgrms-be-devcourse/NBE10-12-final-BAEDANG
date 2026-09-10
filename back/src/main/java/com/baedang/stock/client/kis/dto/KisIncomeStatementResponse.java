package com.baedang.stock.client.kis.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record KisIncomeStatementResponse(
        List<Output> output
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Output(
            @JsonProperty("stac_yymm") String statementYearMonth,
            @JsonProperty("sale_account") String sales,
            @JsonProperty("bsop_prti") String operatingProfit,
            @JsonProperty("thtr_ntin") String netIncome
    ) {
    }
}
