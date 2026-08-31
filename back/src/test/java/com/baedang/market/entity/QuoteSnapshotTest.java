package com.baedang.market.entity;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class QuoteSnapshotTest {

    @Test
    void 수집_시각을_외부에서_전달받아_저장하고_갱신한다() {
        OffsetDateTime initialQuoteAt = OffsetDateTime.parse("2026-08-31T09:00:00+09:00");
        OffsetDateTime initialCollectedAt = OffsetDateTime.parse("2026-08-31T00:00:01Z");
        QuoteSnapshot snapshot = new QuoteSnapshot(
                1L,
                new BigDecimal("70000"),
                "KRW",
                initialQuoteAt,
                initialCollectedAt
        );

        assertThat(snapshot.getCollectedAt()).isEqualTo(initialCollectedAt);

        OffsetDateTime updatedQuoteAt = OffsetDateTime.parse("2026-08-31T09:00:05+09:00");
        OffsetDateTime updatedCollectedAt = OffsetDateTime.parse("2026-08-31T00:00:06Z");
        snapshot.updatePrice(
                new BigDecimal("70100"),
                "KRW",
                updatedQuoteAt,
                updatedCollectedAt
        );

        assertThat(snapshot.getCollectedAt()).isEqualTo(updatedCollectedAt);
    }
}
