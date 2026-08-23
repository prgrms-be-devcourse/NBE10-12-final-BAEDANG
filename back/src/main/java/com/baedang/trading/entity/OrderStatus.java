package com.baedang.trading.entity;

/**
 * 주문의 생애주기.
 *
 * <p><b>1주차 시장가 주문은 PENDING 을 거치지 않습니다.</b> 접수와 체결이
 * 한 트랜잭션에서 연속 실행되므로 FILLED 또는 REJECTED 로 직행합니다.
 * PENDING 이 실제로 저장되는 건 지정가를 붙이는 2주차부터입니다.
 */
public enum OrderStatus { PENDING, FILLED, REJECTED, CANCELED, EXPIRED }
