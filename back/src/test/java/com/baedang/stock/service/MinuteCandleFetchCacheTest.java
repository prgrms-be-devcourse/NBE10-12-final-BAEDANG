package com.baedang.stock.service;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class MinuteCandleFetchCacheTest {

    private static final Instant FETCHED_AT = Instant.parse("2026-08-28T01:00:00Z");

    private final MinuteCandleFetchCache cache = new MinuteCandleFetchCache();

    @Test
    void TTL_안의_조회기록은_신선하다() {
        cache.markFetched(1L, FETCHED_AT);

        assertThat(cache.isFresh(1L, FETCHED_AT.plusSeconds(59))).isTrue();
        assertThat(cache.entryCount()).isEqualTo(1);
    }

    @Test
    void 조회된_키가_만료되면_즉시_제거한다() {
        cache.markFetched(1L, FETCHED_AT);

        assertThat(cache.isFresh(1L, FETCHED_AT.plusSeconds(60))).isFalse();
        assertThat(cache.entryCount()).isZero();
    }

    @Test
    void 다시_조회되지_않은_만료키도_cleanup으로_제거한다() {
        cache.markFetched(1L, FETCHED_AT);
        cache.markFetched(2L, FETCHED_AT.plusSeconds(120));

        cache.evictExpired(FETCHED_AT.plusSeconds(120));

        assertThat(cache.entryCount()).isEqualTo(1);
        assertThat(cache.isFresh(2L, FETCHED_AT.plusSeconds(120))).isTrue();
    }

    @Test
    void 미래시각의_조회기록도_잘못된_캐시로_보고_제거한다() {
        cache.markFetched(1L, FETCHED_AT.plusSeconds(1));

        assertThat(cache.isFresh(1L, FETCHED_AT)).isFalse();
        assertThat(cache.entryCount()).isZero();
    }
}
