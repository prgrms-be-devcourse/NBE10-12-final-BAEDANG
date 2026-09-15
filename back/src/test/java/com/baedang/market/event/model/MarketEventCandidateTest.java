package com.baedang.market.event.model;

import com.baedang.market.event.entity.KrMarket;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MarketEventCandidateTest {

    @Test
    void rejects_missing_event_type() {
        assertThatThrownBy(() -> new MarketEventCandidate(
                KrMarket.KOSPI,
                "20260713000658",
                null,
                1,
                null,
                Instant.parse("2026-07-13T04:29:00Z"),
                "유가증권시장 매매거래 일시중단(1단계 CB 발동)",
                URI.create("https://kind.krx.co.kr/common/disclsviewer.do?method=search&acptNo=20260713000658")
        )).isInstanceOf(NullPointerException.class);
    }
}
