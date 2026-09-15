package com.baedang.trading.model;

import java.util.UUID;

/** 주문 유형에 독립적인 검증 완료 공통 입력. 각 유스케이스가 자신의 Command를 생성합니다. */
public record OrderInput(Long accountId, UUID clientOrderId, OrderTerms terms) {}
