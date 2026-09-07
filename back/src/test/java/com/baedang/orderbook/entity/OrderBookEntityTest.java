package com.baedang.orderbook.entity;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderBookEntityTest {

    @Test
    void 종료된_버전은_다시_활성화되지_않는다() {
        Instant generatedAt = Instant.parse("2026-09-03T01:00:00Z");
        OrderBookVersion version = OrderBookVersion.open(
                1L,
                new BigDecimal("70000"),
                "KRW",
                generatedAt.minusSeconds(2),
                generatedAt,
                "V1",
                42L
        );

        assertThat(version.isActive()).isTrue();
        assertThat(version.getRevision()).isZero();
        assertThat(version.getClosedAt()).isNull();

        version.close(generatedAt.plusSeconds(3));

        assertThat(version.isActive()).isFalse();
        assertThat(version.getClosedAt()).isEqualTo(
                generatedAt.plusSeconds(3).atOffset(ZoneOffset.UTC)
        );
        assertThatThrownBy(() -> version.close(generatedAt.plusSeconds(4)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void closedAt이_null이면_active_상태를_변경하지_않고_예외를_던진다() {
        Instant generatedAt = Instant.parse("2026-09-03T01:00:00Z");
        OrderBookVersion version = OrderBookVersion.open(
                1L,
                new BigDecimal("70000"),
                "KRW",
                generatedAt.minusSeconds(2),
                generatedAt,
                "V1",
                42L
        );

        assertThatThrownBy(() -> version.close(null))
                .isInstanceOf(NullPointerException.class);
        assertThat(version.isActive()).isTrue();
        assertThat(version.getClosedAt()).isNull();
    }

    @Test
    void revision은_활성_버전에서만_증가한다() {
        Instant generatedAt = Instant.parse("2026-09-03T01:00:00Z");
        OrderBookVersion version = OrderBookVersion.open(
                1L,
                new BigDecimal("70000"),
                "KRW",
                generatedAt.minusSeconds(2),
                generatedAt,
                "V1",
                42L
        );

        version.advanceRevision();
        assertThat(version.getRevision()).isEqualTo(1L);

        version.advanceRevision();
        assertThat(version.getRevision()).isEqualTo(2L);

        version.close(generatedAt.plusSeconds(3));

        assertThatThrownBy(version::advanceRevision)
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 레벨_잔량은_최초수량_아래에서만_감소한다() {
        OrderBookLevel level = OrderBookLevel.create(
                null,
                OrderBookSide.ASK,
                1,
                new BigDecimal("70100"),
                new BigDecimal("50")
        );

        assertThat(level.getInitialQuantity()).isEqualByComparingTo("50");
        assertThat(level.getRemainingQuantity()).isEqualByComparingTo("50");

        level.consume(new BigDecimal("20"));

        assertThat(level.getRemainingQuantity()).isEqualByComparingTo("30");
        assertThatThrownBy(() -> level.consume(new BigDecimal("31")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> level.consume(BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> level.consume(new BigDecimal("-1")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
