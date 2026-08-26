package com.baedang.trading.service;

import com.baedang.global.error.BusinessException;
import com.baedang.global.error.ErrorCode;
import com.baedang.stock.entity.MarketCountry;
import com.baedang.trading.model.MarketOrderExecutionContext;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MarketOrderPolicyTest {

    private static final Instant CHECKED_AT = Instant.parse("2026-08-26T06:29:59Z");

    private final MarketOrderPolicy policy =
            new MarketOrderPolicy(15, new BigDecimal("1000000"));

    @Test
    void 세션_종료_시각부터는_조회당시_운영중이어도_체결할_수_없다() {
        MarketOrderExecutionContext context = new MarketOrderExecutionContext(
                MarketCountry.KR,
                true,
                Instant.parse("2026-08-26T06:30:00Z"),
                BigDecimal.ONE,
                CHECKED_AT);

        assertThat(context.isMarketOpenAt(Instant.parse("2026-08-26T06:29:59.999Z"))).isTrue();
        assertThat(context.isMarketOpenAt(Instant.parse("2026-08-26T06:30:00Z"))).isFalse();
    }

    @Test
    void 락_대기로_시장정보가_허용시간을_넘기면_재시도_가능_오류로_거절한다() {
        MarketOrderExecutionContext context = new MarketOrderExecutionContext(
                MarketCountry.KR, true, Instant.MAX, BigDecimal.ONE, CHECKED_AT);

        assertThatThrownBy(() -> policy.validateExecutionContextFresh(
                context, CHECKED_AT.plusSeconds(16)))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.MARKET_CONTEXT_EXPIRED));
    }
}
