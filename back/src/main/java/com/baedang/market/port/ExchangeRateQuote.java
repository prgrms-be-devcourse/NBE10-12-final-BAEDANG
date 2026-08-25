package com.baedang.market.port;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * {@link MarketCalendarPort#fetchExchangeRate()} 의 반환 타입.
 *
 * <p>{@code com.baedang.market.entity.ExchangeRate}(영속 엔티티)와 이름이 겹치지 않도록
 * 일부러 {@code Quote} 를 붙였습니다 — 이건 "방금 Toss 에서 받아온 값"이고, 엔티티는
 * "DB 에 저장된 행"입니다. Service 가 이 값을 받아 엔티티로 변환해 저장합니다.
 *
 * @param rate     실제 매수 시 적용 환율 (환전 스프레드 포함)
 * @param midRate  은행간 매매기준율 — 화면 표시용
 * @param validFrom 이 환율이 유효해지는 시각 (Toss 응답의 validFrom, 그대로 rate_at 으로 씀)
 * @param validUntil 이 환율의 유효 종료 시각
 */
public record ExchangeRateQuote(
        String baseCurrency,
        String quoteCurrency,
        BigDecimal rate,
        BigDecimal midRate,
        OffsetDateTime validFrom,
        OffsetDateTime validUntil
) {
}
