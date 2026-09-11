package com.baedang.trading.model;

import com.baedang.market.port.ExecutionExchangeRateSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class ExecutionRateEvidenceTest {
    private static final OffsetDateTime AT = OffsetDateTime.parse("2026-09-04T01:00:00Z");

    @Test
    void 환율근거는_60초가_지나도_원본유효기간_안이면_사용하고_미래수신은_거절한다() {
        var evidence = ExecutionRateEvidence.from(new ExecutionExchangeRateSnapshot(
                new BigDecimal("1383.601234"), AT, AT, AT.plusHours(1)));
        assertThat(evidence.isValidAt(AT.minusSeconds(1))).isFalse();
        assertThat(evidence.isValidAt(AT.plusSeconds(59))).isTrue();
        assertThat(evidence.isValidAt(AT.plusSeconds(60))).isTrue();
        assertThat(evidence.isValidAt(AT.plusHours(1))).isFalse();
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

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6})
    void 시각이_하나라도_누락되면_생성하지_못한다(int present) {
        OffsetDateTime fetchedAt = (present & 1) != 0 ? AT : null;
        OffsetDateTime validFrom = (present & 2) != 0 ? AT : null;
        OffsetDateTime validUntil = (present & 4) != 0 ? AT.plusSeconds(60) : null;
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> new ExecutionRateEvidence(BigDecimal.ONE, fetchedAt, validFrom, validUntil))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("환율 유효 근거는 모두 제공해야 합니다");
    }
}
