package com.baedang.account.dto;

import com.baedang.user.entity.Account;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import static com.baedang.global.formatter.FinancialDecimalFormatter.krw;
import static com.baedang.global.formatter.FinancialDecimalFormatter.plain;
import static com.baedang.global.formatter.FinancialDecimalFormatter.preserveScale;

/**
 * 마이페이지 상단 계좌 요약. {@code GET /accounts/me} 의 응답입니다.
 *
 * <p>금액은 전부 <b>원(KRW) 문자열</b>입니다 — 프론트 number 의 배정밀도 오차를 피하려고
 * 토스 API 처럼 문자열로 내립니다. 값이 없는 필드(손익률·환율)는 null 이면 응답에서 빠집니다.
 */
public record AccountSummaryResponse(
        Long accountId,
        Integer roundNo,
        String initialCash,
        String cashBalance,
        String stockValue,
        String totalAsset,
        String unrealizedPnl,
        String unrealizedPnlRate,
        String exchangeRate,
        OffsetDateTime asOf
) {

    public static AccountSummaryResponse of(
            Account account,
            BigDecimal stockValue,
            BigDecimal totalAsset,
            BigDecimal unrealizedPnl,
            BigDecimal unrealizedPnlRate,
            BigDecimal exchangeRate,
            OffsetDateTime asOf
    ) {
        return new AccountSummaryResponse(
                account.getAccountId(),
                account.getRoundNo(),
                krw(account.getInitialCash()),
                krw(account.getCashBalance()),
                krw(stockValue),
                krw(totalAsset),
                krw(unrealizedPnl),
                plain(unrealizedPnlRate),
                preserveScale(exchangeRate),
                asOf
        );
    }
}
