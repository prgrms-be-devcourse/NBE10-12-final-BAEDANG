package com.baedang.trading.entity;

/**
 * 주문의 생애주기.
 *
 * <p><b>시장가 주문은 PENDING 을 거치지 않습니다.</b> 접수와 체결이
 * 한 트랜잭션에서 연속 실행되므로 FILLED 또는 REJECTED 로 직행합니다.
 * 지정가 주문은 PENDING에서 시작하여 부분 체결 또는 종료 상태로 전이합니다.
 */
public enum OrderStatus { PENDING, PARTIALLY_FILLED, FILLED, REJECTED, CANCELED, EXPIRED }
