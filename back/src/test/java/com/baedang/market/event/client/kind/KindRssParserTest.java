package com.baedang.market.event.client.kind;

import com.baedang.market.event.entity.KrMarket;
import com.baedang.market.event.entity.MarketEventType;
import com.baedang.market.event.entity.SidecarDirection;
import com.baedang.market.event.model.KindRssBatch;
import com.baedang.market.event.model.MarketEventCandidate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

class KindRssParserTest {

    private KindRssParser parser;

    @BeforeEach
    void setUp() {
        KindUriPolicy policy = new KindUriPolicy(URI.create("https://kind.krx.co.kr"));
        parser = new KindRssParser(policy);
    }

    @Test
    void extracts_only_confirmable_market_actions() {
        KindRssBatch result = parser.parse(KrMarket.KOSPI, fixture("rss-kospi.xml"));

        assertThat(result.candidates())
                .extracting(MarketEventCandidate::sourceEventId,
                        MarketEventCandidate::eventType,
                        MarketEventCandidate::circuitBreakerStage,
                        MarketEventCandidate::sidecarDirection)
                .containsExactly(
                        tuple("20260713000658", MarketEventType.CIRCUIT_BREAKER, 1, null),
                        tuple("20260715000125", MarketEventType.SIDECAR, null, SidecarDirection.BUY));
        assertThat(result.parseErrorCount()).isEqualTo(1);
    }

    @Test
    void pub_date_is_metadata_not_trigger_time() {
        MarketEventCandidate candidate = parser.parse(KrMarket.KOSPI, fixture("rss-kospi.xml"))
                .candidates().getFirst();

        assertThat(candidate.publishedAt()).isEqualTo(Instant.parse("2026-07-13T04:29:00Z"));
        assertThat(candidate).hasNoNullFieldsOrPropertiesExcept(
                "circuitBreakerStage", "sidecarDirection");
    }

    @Test
    void rejects_xxe_payload() {
        assertThatThrownBy(() -> parser.parse(KrMarket.KOSPI, fixture("rss-xxe.xml")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void extracts_kosdaq_market_actions() {
        KindRssBatch result = parser.parse(KrMarket.KOSDAQ, fixture("rss-kosdaq.xml"));

        assertThat(result.candidates())
                .extracting(MarketEventCandidate::sourceEventId,
                        MarketEventCandidate::eventType,
                        MarketEventCandidate::circuitBreakerStage,
                        MarketEventCandidate::sidecarDirection)
                .containsExactly(
                        tuple("20260805000214", MarketEventType.SIDECAR, null, SidecarDirection.BUY),
                        tuple("20260805000300", MarketEventType.CIRCUIT_BREAKER, 2, null));
        assertThat(result.parseErrorCount()).isEqualTo(0);
    }

    @ParameterizedTest
    @CsvSource({
            "'유가증권시장 매매거래 일시중단(1단계 CB 발동)', 1",
            "'[유] 유가증권시장 매매거래 일시중단(2단계 cb 발동)', 2",
            "'유가증권시장 매매거래 일시중단(3단계 Cb 발동)', 3"
    })
    void circuit_breaker_title_variations_are_parsed(String title, int expectedStage) {
        String xml = createXmlWithTitle(title);
        KindRssBatch result = parser.parse(KrMarket.KOSPI, xml);

        assertThat(result.candidates()).hasSize(1);
        MarketEventCandidate candidate = result.candidates().getFirst();
        assertThat(candidate.eventType()).isEqualTo(MarketEventType.CIRCUIT_BREAKER);
        assertThat(candidate.circuitBreakerStage()).isEqualTo(expectedStage);
        assertThat(candidate.sidecarDirection()).isNull();
        assertThat(result.parseErrorCount()).isEqualTo(0);
    }

    @ParameterizedTest
    @CsvSource({
            "'유가증권시장 프로그램 매수호가 일시 효력정지(Side car 발동)', BUY",
            "'[유]유가증권시장 프로그램 매도호가 일시 효력정지(Sidecar 발동)', SELL",
            "'유가증권시장 매수 사이드카(Side car) 발동', BUY",
            "'유가증권시장 매도 사이드카(Sidecar) 발동', SELL"
    })
    void sidecar_title_variations_are_parsed(String title, SidecarDirection expectedDirection) {
        String xml = createXmlWithTitle(title);
        KindRssBatch result = parser.parse(KrMarket.KOSPI, xml);

        assertThat(result.candidates()).hasSize(1);
        MarketEventCandidate candidate = result.candidates().getFirst();
        assertThat(candidate.eventType()).isEqualTo(MarketEventType.SIDECAR);
        assertThat(candidate.circuitBreakerStage()).isNull();
        assertThat(candidate.sidecarDirection()).isEqualTo(expectedDirection);
        assertThat(result.parseErrorCount()).isEqualTo(0);
    }

    @Test
    void conflicting_market_and_title_is_recorded_as_parse_error() {
        String xml = createXmlWithTitle("코스닥시장 매매거래 일시중단(1단계 CB 발동)");
        KindRssBatch result = parser.parse(KrMarket.KOSPI, xml);

        assertThat(result.candidates()).isEmpty();
        assertThat(result.parseErrorCount()).isEqualTo(1);
    }

    @Test
    void more_than_100_items_is_rejected() {
        StringBuilder items = new StringBuilder();
        for (int i = 0; i < 101; i++) {
            items.append("""
                    <item>
                      <title>[유]일반 공시</title>
                      <link>https://kind.krx.co.kr/common/disclsviewer.do?method=search&amp;acptNo=20260713000658</link>
                      <pubDate>Mon, 13 Jul 2026 13:29:00 +0900</pubDate>
                    </item>
                    """);
        }
        String xml = "<rss version=\"2.0\"><channel><title>test</title><link>https://kind.krx.co.kr</link>"
                + items + "</channel></rss>";

        assertThatThrownBy(() -> parser.parse(KrMarket.KOSPI, xml))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void malformed_or_missing_channel_throws_exception() {
        assertThatThrownBy(() -> parser.parse(KrMarket.KOSPI, "not-xml"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> parser.parse(KrMarket.KOSPI, "<root><child>text</child></root>"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private String createXmlWithTitle(String title) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <rss version="2.0">
                  <channel>
                    <title>KIND</title>
                    <link>https://kind.krx.co.kr</link>
                    <item>
                      <title>%s</title>
                      <link>https://kind.krx.co.kr/common/disclsviewer.do?method=search&amp;acptNo=20260713000658</link>
                      <pubDate>Mon, 13 Jul 2026 13:29:00 +0900</pubDate>
                    </item>
                  </channel>
                </rss>
                """.formatted(title);
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
