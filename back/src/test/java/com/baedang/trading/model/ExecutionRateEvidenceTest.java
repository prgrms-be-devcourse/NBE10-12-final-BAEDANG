package com.baedang.trading.model;

import com.baedang.market.port.ExecutionExchangeRateSnapshot;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class ExecutionRateEvidenceTest {
    private static final OffsetDateTime AT = OffsetDateTime.parse("2026-09-04T01:00:00Z");

    @Test
    void 환율근거는_미래수신시각과_원본기한_TTL을_검증한다() {
        var evidence = ExecutionRateEvidence.from(new ExecutionExchangeRateSnapshot(
                new BigDecimal("1383.601234"), AT, AT, AT.plusHours(1)));
        assertThat(evidence.isValidAt(AT.minusSeconds(1))).isFalse();
        assertThat(evidence.isValidAt(AT.plusSeconds(59))).isTrue();
        assertThat(evidence.isValidAt(AT.plusSeconds(60))).isFalse();
        var shortEvidence = ExecutionRateEvidence.from(new ExecutionExchangeRateSnapshot(
                new BigDecimal("1383.601234"), AT, AT, AT.plusSeconds(1)));
        assertThat(shortEvidence.isValidAt(AT.plusSeconds(1))).isFalse();
    }

    @Test
    void 국내_환율근거는_1이며_준비시각부터_60초간_유효하다() {
        var evidence = ExecutionRateEvidence.krw(AT);
        assertThat(evidence.rate()).isEqualByComparingTo("1");
        assertThat(evidence.isValidAt(AT)).isTrue();
        assertThat(evidence.isValidAt(AT.plusSeconds(60))).isFalse();
        assertThat(evidence.isValidAt(null)).isFalse();
    }

    @Test
    void 환율_양수_불변식과_팩토리_입력을_검증한다() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new ExecutionRateEvidence(null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new ExecutionRateEvidence(BigDecimal.ZERO, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new ExecutionRateEvidence(BigDecimal.ONE.negate(), null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> ExecutionRateEvidence.from(null))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> ExecutionRateEvidence.krw(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 불완전하거나_역전된_유효기간은_거절한다() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new ExecutionRateEvidence(BigDecimal.ONE, AT, null, AT.plusHours(1)))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new ExecutionRateEvidence(BigDecimal.ONE, AT, AT.plusHours(1), AT))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new ExecutionRateEvidence(BigDecimal.ONE, AT, AT, AT))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
