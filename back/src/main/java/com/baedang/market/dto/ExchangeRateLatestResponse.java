package com.baedang.market.dto;

import java.time.OffsetDateTime;

/**
 * {@code GET /api/exchange-rates/latest} 응답 — 랭킹 화면 환율 배너용 (docs/api-spec.md 참고).
 *
 * <p>{@code rate}는 화면 표시용 매매기준율({@link com.baedang.market.entity.ExchangeRate#getMidRate()})이다.
 * 실제 체결에 쓰는 스프레드 포함 환율({@code ExchangeRate.rate})과는 다른 값이므로 헷갈리지 말 것.
 *
 * <p>{@code changeAmount}/{@code changeRate}는 "전일 자정(00:00 KST)" 시점 환율 대비 등락이다 —
 * 종목의 changeRate가 하루 내내 고정된 prev_close 대비이듯, 환율도 하루 동안 같은 기준값을 쓴다.
 */
public record ExchangeRateLatestResponse(
        String baseCurrency,
        String quoteCurrency,
        String rate,
        String changeAmount,
        String changeRate,
        OffsetDateTime rateAt
) {
}
