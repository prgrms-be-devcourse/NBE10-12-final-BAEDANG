package com.baedang.account.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/** 중복 클릭과 네트워크 재시도를 구분하기 위해 화면에 표시된 현재 계좌 ID를 받습니다. */
public record AccountResetRequest(
        @NotNull(message = "현재 계좌 ID는 필수입니다")
        @Positive(message = "현재 계좌 ID는 양수여야 합니다")
        Long accountId
) {
}
