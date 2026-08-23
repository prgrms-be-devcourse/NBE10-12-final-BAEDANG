package com.baedang.trading.entity;

/**
 * MVP 는 MARKET 고정입니다.
 * 컬럼을 미리 둬서 2주차에 LIMIT 를 추가할 때 스키마 변경이 없게 했습니다.
 */
public enum OrderType { MARKET, LIMIT }
