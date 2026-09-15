package com.baedang.market.port;

import java.math.BigDecimal;

/**
 * 시장 데이터 모듈이 거래 모듈에 제공하는 현재 USD/KRW 체결 환율 계약입니다.
 *
 * <p>스케줄러가 1분마다 적재한 {@code exchange_rate} 최신 행의 원본 유효기간을
 * 검증하여 제공합니다. 시장가의 명시적 복구 경로만 외부 갱신을 허용하며 만료 환율 폴백은 없습니다.
 */
public interface ExecutionExchangeRateProvider {

    BigDecimal currentUsdKrwRate();

    /** 금융 트랜잭션 전에 DB 스냅샷을 준비하고, 잠금 후에도 유효성을 재검증합니다. */
    ExecutionExchangeRateSnapshot currentUsdKrwSnapshot();

    /** 시장가 준비 단계 전용. DB 환율이 없거나 만료된 경우에만 갱신하며 이후 DB 재조회가 필요합니다. */
    void refreshUnavailableForMarketOrder();
}
