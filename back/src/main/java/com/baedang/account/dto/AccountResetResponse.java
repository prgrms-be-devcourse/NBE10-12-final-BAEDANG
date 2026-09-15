package com.baedang.account.dto;

import com.baedang.user.entity.Account;

import static com.baedang.global.formatter.FinancialDecimalFormatter.krw;

/** 포트폴리오 초기화로 개설된 새 회차 계좌입니다. 금액은 문자열로 응답합니다. */
public record AccountResetResponse(
        Long accountId,
        Integer roundNo,
        String initialCash,
        String cashBalance
) {

    /** 재시도 시 현재 잔액이 변했더라도 최초 초기화 직후 응답을 재현합니다. */
    public static AccountResetResponse fromReset(Account account) {
        return new AccountResetResponse(
                account.getAccountId(),
                account.getRoundNo(),
                krw(account.getInitialCash()),
                krw(account.getInitialCash()));
    }
}
