package com.baedang.trading.service;

import com.baedang.global.error.BusinessException;
import com.baedang.global.error.ErrorCode;
import com.baedang.stock.entity.MarketCountry;
import com.baedang.trading.model.MarketOrderExecutionContext;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MarketOrderPolicyTest {

    private static final Instant CHECKED_AT = Instant.parse("2026-08-26T06:29:59Z");

    private final MarketOrderPolicy policy =
            new MarketOrderPolicy(15, 15, new BigDecimal("1000000"));

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

    @Test
    void 시장정보_만료는_같은_clientOrderId_재시도_정책을_제공한다() {
        MarketOrderExecutionContext context = new MarketOrderExecutionContext(
                MarketCountry.KR, true, Instant.MAX, BigDecimal.ONE, CHECKED_AT);

        assertThatThrownBy(() -> policy.validateExecutionContextFresh(
                context, CHECKED_AT.plusSeconds(16)))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getData())
                                .containsEntry("retryPolicy", "SAME_CLIENT_ORDER_ID"));
    }

    @Test
    void 주문의_누락필드는_필드명과_같은_ID_재시도_정책을_제공한다() {
        assertThatThrownBy(() -> policy.parseCommand(
                UUID.randomUUID().toString(), "005930", "KR", null, "1"))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getData()).containsEntry("field", "side");
                    assertThat(exception.getData())
                            .containsEntry("retryPolicy", "SAME_CLIENT_ORDER_ID");
                });
    }

    @Test
    void 잘못된_clientOrderId는_재사용할_수_없다() {
        assertThatThrownBy(() -> policy.parseCommand(
                "invalid", "005930", "KR", "BUY", "1"))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getData())
                                .containsEntry("retryPolicy", "NOT_RETRYABLE"));
    }
}
