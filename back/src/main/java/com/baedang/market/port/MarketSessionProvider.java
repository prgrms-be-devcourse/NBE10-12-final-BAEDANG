package com.baedang.market.port;

import com.baedang.stock.entity.MarketCountry;

import java.time.Instant;

/**
 * 시장 데이터 모듈과 거래 모듈 사이의 세션 조회 계약입니다.
 * 구현체는 Toss market-calendar의 regularMarket 응답을 캐싱·해석하고,
 * 거래 모듈은 저장 방식이나 DST 계산을 알지 않은 채 이 결과만 사용합니다.
 *
 * <p>구현체는 현재 시각의 정규장 운영 여부와 {@code regularMarket} 종료 시각을
 * 함께 반환해야 합니다 — 미국장 DST 시각을 거래 모듈에서 별도로 계산하게 만들지
 * 마세요. 거래 모듈은 종료 시각으로 락 대기 중 장 마감 여부를 다시 검증하므로,
 * 운영 중인 세션의 {@code validUntil}은 반드시 채워야 합니다.
 */
public interface MarketSessionProvider {

    MarketSessionStatus currentSession(MarketCountry marketCountry, Instant now);

    default boolean isOpen(MarketCountry marketCountry, Instant now) {
        return currentSession(marketCountry, now).open();
    }
}
