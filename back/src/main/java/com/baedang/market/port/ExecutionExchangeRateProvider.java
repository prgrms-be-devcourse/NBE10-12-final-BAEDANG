package com.baedang.market.port;

import java.math.BigDecimal;

/**
 * 시장 데이터 모듈이 거래 모듈에 제공하는 현재 USD/KRW 체결 환율 계약입니다.
 *
 * <p>스케줄러가 1분마다 적재한 {@code exchange_rate} 최신 행의 원본 유효기간을
 * 검증하여 제공합니다. 거래 요청에서는 외부 조회나 만료 환율 폴백을 하지 않습니다.
 */
public interface ExecutionExchangeRateProvider {

    BigDecimal currentUsdKrwRate();

    /** 금융 트랜잭션 전에 DB 스냅샷을 준비하고, 잠금 후에도 유효성을 재검증합니다. */
    ExecutionExchangeRateSnapshot currentUsdKrwSnapshot();
}
