package com.baedang.stock.client.kis.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record KisStockInfoResponse(
        Output output
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Output(
            @JsonProperty("std_idst_clsf_cd") String standardIndustryCode,
            @JsonProperty("std_idst_clsf_cd_name") String standardIndustryName,
            @JsonProperty("idx_bztp_lcls_cd") String largeIndustryCode,
            @JsonProperty("idx_bztp_lcls_cd_name") String largeIndustryName,
            @JsonProperty("idx_bztp_mcls_cd") String mediumIndustryCode,
            @JsonProperty("idx_bztp_mcls_cd_name") String mediumIndustryName,
            @JsonProperty("idx_bztp_scls_cd") String smallIndustryCode,
            @JsonProperty("idx_bztp_scls_cd_name") String smallIndustryName
    ) {
    }
}
