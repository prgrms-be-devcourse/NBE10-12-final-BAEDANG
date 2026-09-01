package com.baedang.stock.service;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class DailyCandleBackfillTrackerTest {

    private final DailyCandleBackfillTracker tracker = new DailyCandleBackfillTracker();

    @Test
    void 성공한_백필을_종목별로_완료_처리한다() {
        assertThat(tracker.isCompleted(1L)).isFalse();

        tracker.markCompleted(1L);

        assertThat(tracker.isCompleted(1L)).isTrue();
        assertThat(tracker.isCompleted(2L)).isFalse();
    }

    @Test
    void 같은_종목을_여러번_완료_처리해도_기록은_하나만_유지한다() {
        tracker.markCompleted(1L);
        tracker.markCompleted(1L);

        assertThat(tracker.entryCount()).isOne();
    }

    @Test
    void 성공한_최신화_요청은_해당_거래일과_그_이전까지_충족한다() {
        LocalDate tradeDate = LocalDate.of(2026, 8, 31);

        tracker.markRefreshedThrough(1L, tradeDate);

        assertThat(tracker.wasRefreshedThrough(1L, tradeDate)).isTrue();
        assertThat(tracker.wasRefreshedThrough(1L, tradeDate.minusDays(1))).isTrue();
        assertThat(tracker.wasRefreshedThrough(1L, tradeDate.plusDays(1))).isFalse();
        assertThat(tracker.wasRefreshedThrough(2L, tradeDate)).isFalse();
    }

    @Test
    void 더_오래된_거래일로_완료_기록이_후퇴하지_않는다() {
        LocalDate newerDate = LocalDate.of(2026, 8, 31);
        tracker.markRefreshedThrough(1L, newerDate);

        tracker.markRefreshedThrough(1L, newerDate.minusDays(1));

        assertThat(tracker.wasRefreshedThrough(1L, newerDate)).isTrue();
    }
}
