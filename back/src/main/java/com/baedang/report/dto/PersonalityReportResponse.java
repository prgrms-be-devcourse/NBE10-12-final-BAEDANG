package com.baedang.report.dto;

import com.baedang.report.support.InvestmentProfile;
import com.baedang.user.entity.Account;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import static com.baedang.global.formatter.FinancialDecimalFormatter.krw;
import static com.baedang.global.formatter.FinancialDecimalFormatter.plain;

/**
 * 투자 성향 리포트. {@code GET /api/reports/me} 의 응답이다(현재 활성 계좌=라운드 기준).
 *
 * <p>금액은 원(KRW) 문자열, 비율은 0~1 소수 문자열이다(프론트 배정밀도 오차 회피).
 * 보유 종목이 부족하면 {@code classified=false} 이고 {@code typeCode}·{@code typeLabel} 은
 * null 이다("미분류/신규"). 비중은 분류 여부와 무관하게 계산된 값을 담는다.
 */
public record PersonalityReportResponse(
        Long accountId,
        Integer roundNo,
        String initialCash,
        String cashBalance,
        String stockValue,
        String totalAsset,
        String totalPnl,
        String returnRate,
        boolean classified,
        String typeCode,
        String typeLabel,
        Shares shares,
        int holdingCount,
        OffsetDateTime asOf
) {

    /** 각 축을 가른 근거 비중(0~1). 프론트가 "왜 이 유형인지" 막대로 보여줄 수 있게 함께 내린다. */
    public record Shares(
            String concentration,
            String domestic,
            String individual,
            String aggressive
    ) {
    }

    public static PersonalityReportResponse of(
            Account account,
            BigDecimal stockValue,
            BigDecimal totalAsset,
            BigDecimal totalPnl,
            BigDecimal returnRate,
            InvestmentProfile profile,
            OffsetDateTime asOf
    ) {
        return new PersonalityReportResponse(
                account.getAccountId(),
                account.getRoundNo(),
                krw(account.getInitialCash()),
                krw(account.getCashBalance()),
                krw(stockValue),
                krw(totalAsset),
                krw(totalPnl),
                plain(returnRate),
                profile.classified(),
                profile.classified() ? profile.type().code() : null,
                profile.classified() ? profile.type().label() : null,
                new Shares(
                        plain(profile.top1Share()),
                        plain(profile.domesticShare()),
                        plain(profile.individualShare()),
                        plain(profile.aggressiveShare())),
                profile.holdingCount(),
                asOf
        );
    }
}
