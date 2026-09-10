package com.baedang.trading.scheduler;

import com.baedang.stock.entity.MarketCountry;
import com.baedang.trading.entity.OrderSide;
import com.baedang.trading.model.ExecutionRateEvidence;
import com.baedang.trading.model.LimitExecutionBook;
import com.baedang.trading.model.LimitExecutionPreparation;
import com.baedang.trading.model.LimitOrderAcceptedEvent;
import com.baedang.trading.model.OrderMarketContext;
import com.baedang.trading.repository.LimitExecutionCandidateRepository.Candidate;
import com.baedang.trading.repository.LimitExecutionCandidateRepository.Group;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LimitExecutionProgressTest {
    private static final Instant NOW = Instant.parse("2026-09-09T01:00:00Z");

    @ParameterizedTest
    @EnumSource(OrderSide.class)
    void 여러접수는_가장선순위를_보존하고_실행완료커서와_비교한다(OrderSide side) {
        LimitExecutionProgress progress = new LimitExecutionProgress();
        Group group = new Group(1L, side);
        LimitExecutionPreparation market = market(side);
        LimitExecutionProgress.Position selected = progress.position(group, market);
        BigDecimal earlyPrice = new BigDecimal(side == OrderSide.BUY ? "110" : "90");
        BigDecimal latePrice = new BigDecimal(side == OrderSide.BUY ? "90" : "110");
        progress.onAccepted(new LimitOrderAcceptedEvent(1L, side, 2L, earlyPrice, NOW.atOffset(ZoneOffset.UTC)));
        progress.onAccepted(new LimitOrderAcceptedEvent(1L, side, 3L, latePrice, NOW.atOffset(ZoneOffset.UTC)));

        assertThat(progress.advance(group, selected, new Candidate(1L, new BigDecimal("100"), NOW.atOffset(ZoneOffset.UTC)))).isTrue();
        LimitExecutionProgress.Position next = progress.position(group, market);
        assertThat(next.after()).isNull();
        assertThat(next.token()).isNotEqualTo(selected.token());
        assertThat(progress.position(group, market)).isEqualTo(next);
    }

    @ParameterizedTest
    @EnumSource(OrderSide.class)
    void 같은가격이면_시간과_ID가_앞선접수를_다음선정에_반영한다(OrderSide side) {
        LimitExecutionProgress progress = new LimitExecutionProgress();
        Group group = new Group(1L, side);
        LimitExecutionPreparation market = market(side);
        LimitExecutionProgress.Position selected = progress.position(group, market);
        progress.onAccepted(new LimitOrderAcceptedEvent(1L, side, 2L, BigDecimal.TEN, NOW.atOffset(ZoneOffset.UTC)));
        progress.onAccepted(new LimitOrderAcceptedEvent(1L, side, 1L, BigDecimal.TEN, NOW.plusSeconds(1).atOffset(ZoneOffset.UTC)));
        progress.onAccepted(new LimitOrderAcceptedEvent(1L, side, 4L, BigDecimal.TEN, NOW.atOffset(ZoneOffset.UTC)));

        assertThat(progress.advance(group, selected, new Candidate(3L, BigDecimal.TEN, NOW.atOffset(ZoneOffset.UTC)))).isTrue();
        assertThat(progress.position(group, market).after()).isNull();
    }

    private LimitExecutionPreparation market(OrderSide side) {
        return LimitExecutionPreparation.available(1L, side,
                new LimitExecutionBook(1L, 0L, NOW, NOW, List.of()),
                new OrderMarketContext(MarketCountry.KR, true, NOW.plusSeconds(3600),
                        ExecutionRateEvidence.krw(NOW.atOffset(ZoneOffset.UTC)), NOW));
    }
}
