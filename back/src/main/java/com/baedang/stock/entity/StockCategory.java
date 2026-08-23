package com.baedang.stock.entity;

/**
 * 상품 유형(배타적). 프론트가 유형별 안내 문구를 고르는 1차 키입니다.
 * 배당 여부는 유형과 독립이라 별도 boolean 으로 둡니다 — 개별주에도 ETF 에도 붙습니다.
 */
public enum StockCategory { INDIVIDUAL, PREFERRED, ETF, ETN }
