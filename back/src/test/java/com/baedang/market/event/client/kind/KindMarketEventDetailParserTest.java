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
    void selects_content_row_after_sidecar_action_row() {
        MarketEventCandidate candidate = sidecarCandidate(
                KrMarket.KOSPI, "20260715000125", SidecarDirection.BUY);
        URI detailUri = URI.create(
                "https://kind.krx.co.kr/external/2026/07/15/000066/20260715000125/99404.htm");
        String html = """
                <div class="xforms">
                  <div class="xforms_title">유가증권시장 매수 사이드카(Side car) 발동</div>
                  <table>
                    <tr><td>1. 조치</td><td>프로그램 매수호가 일시 효력정지 (사이드카 발동)</td></tr>
                    <tr><td>2. 발동일시</td><td>2026년07월15일</td><td>09시06분41초</td></tr>
                    <tr><td>4. 내용</td><td>해당 선물 가격 상승으로 향후 5분간 유가증권시장의 프로그램 매수호가 효력이 정지됨</td></tr>
                  </table>
                </div>
                """;

        ConfirmedMarketEvent event = parser.parse(
                candidate,
                detailUri,
                html,
                Instant.parse("2026-07-15T00:07:10Z")
        );

        assertThat(event.triggeredAt()).isEqualTo(Instant.parse("2026-07-15T00:06:41Z"));
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

    @Test
    void parses_kosdaq_sidecar_layout() {
        MarketEventCandidate candidate = sidecarCandidate(
                KrMarket.KOSDAQ, "20260305000333", SidecarDirection.BUY);
        URI detailUri = URI.create(
                "https://kind.krx.co.kr/external/2026/03/05/000237/20260305000333/70711.htm");

        ConfirmedMarketEvent event = parser.parse(
                candidate,
                detailUri,
                fixture("detail-kosdaq-sidecar-buy.html"),
                Instant.parse("2026-03-05T00:06:00Z")
        );

        assertThat(event.market()).isEqualTo(KrMarket.KOSDAQ);
        assertThat(event.triggeredAt()).isEqualTo(Instant.parse("2026-03-05T00:05:12Z"));
    }

    @Test
    void rejects_noncanonical_detail_title() {
        MarketEventCandidate candidate = circuitBreakerCandidate(1);
        String html = detailHtml(
                "유가증권시장 CB 테스트 일시중단 1단계",
                "2026-07-13 13:28:32",
                "향후 20분간 유가증권시장 매매거래 일시중단");

        assertThatThrownBy(() -> parser.parse(candidate, detailUri(candidate), html, Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejects_circuit_breaker_stage_conflict() {
        MarketEventCandidate candidate = circuitBreakerCandidate(1);
        String html = detailHtml(
                "유가증권시장 매매거래 일시중단(11단계 CB 발동)",
                "2026-07-13 13:28:32",
                "향후 20분간 유가증권시장 매매거래 일시중단");

        assertThatThrownBy(() -> parser.parse(candidate, detailUri(candidate), html, Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejects_sidecar_direction_conflict() {
        MarketEventCandidate candidate = sidecarCandidate(
                KrMarket.KOSPI, "20260715000125", SidecarDirection.BUY);
        String html = detailHtml(
                "유가증권시장 프로그램 매수·매도호가 일시 효력정지(Sidecar 발동)",
                "2026-07-15 09:06:41",
                "향후 5분간 프로그램매수호가 효력정지");

        assertThatThrownBy(() -> parser.parse(candidate, detailUri(candidate), html, Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejects_duration_embedded_in_a_larger_number() {
        MarketEventCandidate candidate = circuitBreakerCandidate(1);
        String html = detailHtml(
                "유가증권시장 매매거래 일시중단(1단계 CB 발동)",
                "2026-07-13 13:28:32",
                "향후 120분간 유가증권시장 매매거래 일시중단");

        assertThatThrownBy(() -> parser.parse(candidate, detailUri(candidate), html, Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void stage_three_requires_same_day_market_close_statement() {
        MarketEventCandidate candidate = circuitBreakerCandidate(3);
        String html = detailHtml(
                "유가증권시장 매매거래 일시중단(3단계 CB 발동)",
                "2026-07-13 13:28:32",
                "장 종료 후 재개 예정");

        assertThatThrownBy(() -> parser.parse(candidate, detailUri(candidate), html, Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejects_rows_outside_xforms_document() {
        MarketEventCandidate candidate = circuitBreakerCandidate(1);
        String html = """
                <div class="xforms">
                  <div class="xforms_title">유가증권시장 매매거래 일시중단(1단계 CB 발동)</div>
                </div>
                <table>
                  <tr><td>1. 일자 및 시각</td><td>2026-07-13 13:28:32</td></tr>
                  <tr><td>2. 내용</td><td>향후 20분간 유가증권시장 매매거래 일시중단</td></tr>
                </table>
                """;

        assertThatThrownBy(() -> parser.parse(candidate, detailUri(candidate), html, Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejects_missing_or_impossible_trigger_time() {
        MarketEventCandidate candidate = circuitBreakerCandidate(1);
        String missing = """
                <div class="xforms">
                  <div class="xforms_title">유가증권시장 매매거래 일시중단(1단계 CB 발동)</div>
                  <table><tr><td>2. 내용</td><td>향후 20분간 유가증권시장 매매거래 일시중단</td></tr></table>
                </div>
                """;
        String impossible = detailHtml(
                "유가증권시장 매매거래 일시중단(1단계 CB 발동)",
                "2026-13-40 25:61:61",
                "향후 20분간 유가증권시장 매매거래 일시중단");

        assertThatThrownBy(() -> parser.parse(candidate, detailUri(candidate), missing, Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> parser.parse(candidate, detailUri(candidate), impossible, Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejects_unapproved_trigger_time_format() {
        MarketEventCandidate candidate = circuitBreakerCandidate(1);
        String html = detailHtml(
                "유가증권시장 매매거래 일시중단(1단계 CB 발동)",
                "2026 07 13 13 28 32",
                "향후 20분간 유가증권시장 매매거래 일시중단");

        assertThatThrownBy(() -> parser.parse(candidate, detailUri(candidate), html, Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private MarketEventCandidate circuitBreakerCandidate(int stage) {
        return new MarketEventCandidate(
                KrMarket.KOSPI,
                "20260713000658",
                MarketEventType.CIRCUIT_BREAKER,
                stage,
                null,
                Instant.parse("2026-07-13T04:29:00Z"),
                "유가증권시장 매매거래 일시중단(" + stage + "단계 CB 발동)",
                URI.create("https://kind.krx.co.kr/common/disclsviewer.do?method=search&acptNo=20260713000658")
        );
    }

    private MarketEventCandidate sidecarCandidate(
            KrMarket market,
            String sourceEventId,
            SidecarDirection direction
    ) {
        String marketName = market == KrMarket.KOSPI ? "유가증권시장" : "코스닥시장";
        String directionName = direction == SidecarDirection.BUY ? "매수" : "매도";
        return new MarketEventCandidate(
                market,
                sourceEventId,
                MarketEventType.SIDECAR,
                null,
                direction,
                Instant.parse("2026-03-05T00:06:00Z"),
                marketName + " 프로그램" + directionName + "호가 일시효력정지(Sidecar 발동)",
                URI.create("https://kind.krx.co.kr/common/disclsviewer.do?method=search&acptNo=" + sourceEventId)
        );
    }

    private URI detailUri(MarketEventCandidate candidate) {
        return URI.create("https://kind.krx.co.kr/external/2026/07/13/000273/"
                + candidate.sourceEventId() + "/99443.htm");
    }

    private String detailHtml(String title, String triggeredAt, String content) {
        return """
                <div class="xforms">
                  <div class="xforms_title">%s</div>
                  <table>
                    <tr><td>1. 일자 및 시각</td><td>%s</td></tr>
                    <tr><td>2. 내용</td><td>%s</td></tr>
                  </table>
                </div>
                """.formatted(title, triggeredAt, content);
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
