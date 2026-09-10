package com.baedang.market.event.client.kind;

import com.baedang.market.event.entity.KrMarket;
import com.baedang.market.event.entity.MarketEventType;
import com.baedang.market.event.entity.SidecarDirection;
import com.baedang.market.event.model.ConfirmedMarketEvent;
import com.baedang.market.event.model.MarketEventCandidate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KindMarketEventDetailParserTest {

    private KindMarketEventDetailParser parser;

    @BeforeEach
    void setUp() {
        KindUriPolicy policy = new KindUriPolicy(URI.create("https://kind.krx.co.kr"));
        parser = new KindMarketEventDetailParser(policy);
    }

    @Test
    void parses_kospi_cb_trigger_time_from_detail_not_pub_date() {
        MarketEventCandidate candidate = new MarketEventCandidate(
                KrMarket.KOSPI,
                "20260713000658",
                MarketEventType.CIRCUIT_BREAKER,
                1,
                null,
                Instant.parse("2026-07-13T04:29:00Z"),
                "유가증권시장 매매거래 일시중단(1단계 CB 발동)",
                URI.create("https://kind.krx.co.kr/common/disclsviewer.do?method=search&acptNo=20260713000658")
        );
        URI detailUri = URI.create("https://kind.krx.co.kr/external/2026/07/13/000273/20260713000658/99443.htm");

        ConfirmedMarketEvent event = parser.parse(
                candidate,
                detailUri,
                fixture("detail-kospi-cb-stage1.html"),
                Instant.parse("2026-07-13T04:29:07Z")
        );

        assertThat(event.market()).isEqualTo(KrMarket.KOSPI);
        assertThat(event.sourceEventId()).isEqualTo("20260713000658");
        assertThat(event.eventType()).isEqualTo(MarketEventType.CIRCUIT_BREAKER);
        assertThat(event.circuitBreakerStage()).isEqualTo(1);
        assertThat(event.sidecarDirection()).isNull();
        assertThat(event.triggeredAt()).isEqualTo(Instant.parse("2026-07-13T04:28:32Z"));
        assertThat(event.publishedAt()).isEqualTo(Instant.parse("2026-07-13T04:29:00Z"));
        assertThat(event.receivedAt()).isEqualTo(Instant.parse("2026-07-13T04:29:07Z"));
        assertThat(event.sourceUrl()).isEqualTo(detailUri);
    }

    @Test
    void parses_korean_date_format_in_sidecar_buy() {
        MarketEventCandidate candidate = new MarketEventCandidate(
                KrMarket.KOSPI,
                "20260715000125",
                MarketEventType.SIDECAR,
                null,
                SidecarDirection.BUY,
                Instant.parse("2026-07-15T00:07:00Z"),
                "유가증권시장 프로그램 매수호가 일시 효력정지(Side car 발동)",
                URI.create("https://kind.krx.co.kr/common/disclsviewer.do?method=search&acptNo=20260715000125")
        );
        URI detailUri = URI.create("https://kind.krx.co.kr/external/2026/07/15/000066/20260715000125/99404.htm");

        ConfirmedMarketEvent event = parser.parse(
                candidate,
                detailUri,
                fixture("detail-kospi-sidecar-buy.html"),
                Instant.parse("2026-07-15T00:07:10Z")
        );

        assertThat(event.triggeredAt()).isEqualTo(Instant.parse("2026-07-15T00:06:41Z"));
        assertThat(event.sidecarDirection()).isEqualTo(SidecarDirection.BUY);
    }

    @Test
    void rejects_market_conflict_between_candidate_and_detail_title() {
        MarketEventCandidate candidate = new MarketEventCandidate(
                KrMarket.KOSPI,
                "20260713000658",
                MarketEventType.CIRCUIT_BREAKER,
                1,
                null,
                Instant.parse("2026-07-13T04:29:00Z"),
                "유가증권시장 매매거래 일시중단(1단계 CB 발동)",
                URI.create("https://kind.krx.co.kr/common/disclsviewer.do?method=search&acptNo=20260713000658")
        );
        URI detailUri = URI.create("https://kind.krx.co.kr/external/2026/07/13/000273/20260713000658/99443.htm");
        String conflictingHtml = fixture("detail-kosdaq-sidecar-buy.html");

        assertThatThrownBy(() -> parser.parse(candidate, detailUri, conflictingHtml, Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejects_missing_xforms_title() {
        MarketEventCandidate candidate = new MarketEventCandidate(
                KrMarket.KOSPI,
                "20260713000658",
                MarketEventType.CIRCUIT_BREAKER,
                1,
                null,
                Instant.parse("2026-07-13T04:29:00Z"),
                "유가증권시장 매매거래 일시중단(1단계 CB 발동)",
                URI.create("https://kind.krx.co.kr/common/disclsviewer.do?method=search&acptNo=20260713000658")
        );
        URI detailUri = URI.create("https://kind.krx.co.kr/external/2026/07/13/000273/20260713000658/99443.htm");
        String noTitleHtml = "<html><body><table><tr><td>1. 일자 및 시각</td><td>2026-07-13</td><td>13:28:32</td></tr></table></body></html>";

        assertThatThrownBy(() -> parser.parse(candidate, detailUri, noTitleHtml, Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejects_wrong_duration_text() {
        MarketEventCandidate candidate = new MarketEventCandidate(
                KrMarket.KOSPI,
                "20260713000658",
                MarketEventType.CIRCUIT_BREAKER,
                1,
                null,
                Instant.parse("2026-07-13T04:29:00Z"),
                "유가증권시장 매매거래 일시중단(1단계 CB 발동)",
                URI.create("https://kind.krx.co.kr/common/disclsviewer.do?method=search&acptNo=20260713000658")
        );
        URI detailUri = URI.create("https://kind.krx.co.kr/external/2026/07/13/000273/20260713000658/99443.htm");
        String wrongDurationHtml = """
                <div class="xforms">
                  <div class="xforms_title">유가증권시장 매매거래 일시중단(1단계 CB 발동)</div>
                  <table>
                    <tr><td>1. 일자 및 시각</td><td>2026-07-13</td><td>13:28:32</td></tr>
                    <tr><td>2. 내용</td><td>향후 10분간 유가증권시장 매매거래 일시중단</td></tr>
                  </table>
                </div>
                """;

        assertThatThrownBy(() -> parser.parse(candidate, detailUri, wrongDurationHtml, Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private String fixture(String name) {
        try (var in = getClass().getResourceAsStream("/fixtures/kind/" + name)) {
            if (in == null) {
                throw new IllegalArgumentException("Fixture not found: " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
