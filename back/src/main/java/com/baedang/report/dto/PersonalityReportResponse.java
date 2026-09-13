package com.baedang.report.dto;

import com.baedang.report.support.InvestmentProfile;
import com.baedang.user.entity.Account;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import static com.baedang.global.formatter.FinancialDecimalFormatter.krw;
import static com.baedang.global.formatter.FinancialDecimalFormatter.plain;

/**
 * 투자 성향 리포트. {@code GET /api/reports/me} 의 응답이다(현재 활성 계좌=라운드 기준).
 *
 * <p>금액은 원(KRW) 문자열, 비율은 0~1 소수 문자열이다(프론트 배정밀도 오차 회피).
 * 보유 종목이 부족하면 {@code classified=false} 이고 {@code typeCode}·{@code typeLabel} 은
 * null 이다("미분류/신규"). 비중은 분류 여부와 무관하게 계산된 값을 담는다.
 *
 * <p><b>4주 회차 게이트(§6.1):</b> 현재 ACTIVE 계좌 개설 후 {@code unlockAt}({@code opened_at + N주})
 * 이전이면 {@code locked=true} 이고 성향·성과 본문은 담지 않는다(프론트가 잠금·잔여 진행 표시).
 * 리셋은 새 계좌를 열어 게이트가 자동 재시작한다. 발급(열림) 후에는 열 때마다 재계산한다(freeze 아님).
 */
public record PersonalityReportResponse(
        Long accountId,
        Integer roundNo,
        boolean locked,
        OffsetDateTime unlockAt,
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
        int holdingPeriodWeeks,
        List<LongHeldStock> longHeldStocks,
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

    /**
     * N주 이상 보유한 종목 한 건의 성과. 등락률은 <b>내 보유 수익률</b>
     * {@code (현재가 − 평단가)/평단가}(종목 통화 기준)이다. 시세가 없으면 {@code returnRate} 는 null.
     */
    public record LongHeldStock(
            String symbol,
            String name,
            String currency,
            String avgBuyPrice,
            String lastPrice,
            String returnRate,
            OffsetDateTime heldSince
    ) {
    }

    /** 4주 회차를 채우기 전 잠김 응답. 본문은 비우고 잠금·해제 예정 시각만 담는다(§6.1). */
    public static PersonalityReportResponse locked(
            Account account, OffsetDateTime unlockAt, int holdingPeriodWeeks, OffsetDateTime asOf
    ) {
        return new PersonalityReportResponse(
                account.getAccountId(), account.getRoundNo(), true, unlockAt,
                null, null, null, null, null, null,
                false, null, null, null, 0, holdingPeriodWeeks, List.of(), asOf);
    }

    public static PersonalityReportResponse of(
            Account account,
            OffsetDateTime unlockAt,
            BigDecimal stockValue,
            BigDecimal totalAsset,
            BigDecimal totalPnl,
            BigDecimal returnRate,
            InvestmentProfile profile,
            int holdingPeriodWeeks,
            List<LongHeldStock> longHeldStocks,
            OffsetDateTime asOf
    ) {
        return new PersonalityReportResponse(
                account.getAccountId(),
                account.getRoundNo(),
                false,
                unlockAt,
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
                holdingPeriodWeeks,
                longHeldStocks,
                asOf
        );
    }
}
