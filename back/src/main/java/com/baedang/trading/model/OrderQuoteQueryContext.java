package com.baedang.trading.model;

import com.baedang.market.entity.QuoteSnapshot;
import com.baedang.stock.entity.Stock;
import com.baedang.user.entity.Account;

import java.math.BigDecimal;

/** 견적 계산 전에 읽기 전용 트랜잭션에서 조회한 DB 스냅샷입니다. */
public record OrderQuoteQueryContext(
        Account account,
        Stock stock,
        QuoteSnapshot quote,
        BigDecimal availableQuantity
) {
}
