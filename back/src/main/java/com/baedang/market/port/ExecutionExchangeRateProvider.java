package com.baedang.market.port;

import java.math.BigDecimal;

/**
 * 시장 데이터 모듈이 거래 모듈에 제공하는 현재 USD/KRW 체결 환율 계약입니다.
 *
 * <p>구현체는 Toss 현재 환율을 1분 TTL로 캐싱해야 합니다 — 매 호출마다 Toss를
 * 부르면 체결 환율 조회가 몰릴 때 rate limit을 소모합니다. 차트 이력용
 * {@code exchange_rate} 테이블의 시간 단위 최신 행을 체결 환율의 폴백으로
 * 쓰지 마세요 — 최대 1시간까지 뒤처진 값일 수 있습니다.
 */
public interface ExecutionExchangeRateProvider {

    BigDecimal currentUsdKrwRate();
}
