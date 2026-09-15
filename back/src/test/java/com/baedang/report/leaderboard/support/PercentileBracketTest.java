package com.baedang.report.leaderboard.support;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PercentileBracketTest {

    @Test
    void 참가자_100명_경계에서_브래킷을_고른다() {
        assertThat(PercentileBracket.of(1, 100)).isEqualTo(1);   // 1% → top1
        assertThat(PercentileBracket.of(5, 100)).isEqualTo(5);   // 5% → top5
        assertThat(PercentileBracket.of(6, 100)).isEqualTo(10);  // 6% → top10
        assertThat(PercentileBracket.of(10, 100)).isEqualTo(10);
        assertThat(PercentileBracket.of(11, 100)).isEqualTo(25);
        assertThat(PercentileBracket.of(25, 100)).isEqualTo(25);
        assertThat(PercentileBracket.of(50, 100)).isEqualTo(50);
        assertThat(PercentileBracket.of(75, 100)).isEqualTo(75);
    }

    @Test
    void 하위권은_null() {
        assertThat(PercentileBracket.of(76, 100)).isNull();
        assertThat(PercentileBracket.of(100, 100)).isNull();
    }

    @Test
    void 잘못된_입력은_null() {
        assertThat(PercentileBracket.of(1, 0)).isNull();
        assertThat(PercentileBracket.of(0, 100)).isNull();
    }

    @Test
    void 참가자가_적으면_한명이라도_상위_브래킷에_들_수_있다() {
        // 참가자 3명, 1등 → 33% → 50% 브래킷.
        assertThat(PercentileBracket.of(1, 3)).isEqualTo(50);
        // 3등 → 100% → 하위권 null.
        assertThat(PercentileBracket.of(3, 3)).isNull();
    }
}
