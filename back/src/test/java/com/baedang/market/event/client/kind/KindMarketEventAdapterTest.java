package com.baedang.market.event.client.kind;

import com.baedang.market.event.entity.KrMarket;
import com.baedang.market.event.entity.MarketEventType;
import com.baedang.market.event.model.ConfirmedMarketEvent;
import com.baedang.market.event.model.KindRssBatch;
import com.baedang.market.event.model.MarketEventCandidate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KindMarketEventAdapterTest {

    private static final Instant NOW = Instant.parse("2026-07-13T04:29:10Z");

    private KindHttpClient httpClient;
    private KindUriPolicy uriPolicy;
    private KindRssParser rssParser;
    private KindViewerParser viewerParser;
    private KindMarketEventDetailParser detailParser;
    private KindMarketEventAdapter adapter;

    @BeforeEach
    void setUp() {
        httpClient = mock(KindHttpClient.class);
        uriPolicy = mock(KindUriPolicy.class);
        rssParser = mock(KindRssParser.class);
        viewerParser = mock(KindViewerParser.class);
        detailParser = mock(KindMarketEventDetailParser.class);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

        adapter = new KindMarketEventAdapter(
                httpClient,
                uriPolicy,
                rssParser,
                viewerParser,
                detailParser,
                clock
        );
    }

    @Test
    void fetch_candidates_delegates_to_http_client_and_rss_parser() {
        URI rssUri = URI.create("https://kind.krx.co.kr/rss");
        when(uriPolicy.rss(KrMarket.KOSPI)).thenReturn(rssUri);
        when(httpClient.getText(rssUri, 1048576)).thenReturn("<rss>xml</rss>");
        KindRssBatch expectedBatch = new KindRssBatch(List.of(), 0);
        when(rssParser.parse(KrMarket.KOSPI, "<rss>xml</rss>")).thenReturn(expectedBatch);

        KindRssBatch result = adapter.fetchCandidates(KrMarket.KOSPI);

        assertThat(result).isSameAs(expectedBatch);
    }

    @Test
    void fetch_confirmed_flows_through_viewer_and_detail_parsers() {
        URI viewerUrl = URI.create("https://kind.krx.co.kr/common/disclsviewer.do?method=search&acptNo=20260713000658");
        MarketEventCandidate candidate = new MarketEventCandidate(
                KrMarket.KOSPI,
                "20260713000658",
                MarketEventType.CIRCUIT_BREAKER,
                1,
                null,
                Instant.parse("2026-07-13T04:29:00Z"),
                "CB 1단계",
                viewerUrl
        );
        URI detailUri = URI.create("https://kind.krx.co.kr/external/detail.htm");
        when(httpClient.getText(viewerUrl, 1048576)).thenReturn("<html>viewer</html>");
        when(viewerParser.externalDetailUri(viewerUrl, "<html>viewer</html>")).thenReturn(detailUri);
        when(httpClient.getText(detailUri, 1048576)).thenReturn("<html>detail</html>");

        ConfirmedMarketEvent expectedEvent = new ConfirmedMarketEvent(
                "20260713000658",
                KrMarket.KOSPI,
                MarketEventType.CIRCUIT_BREAKER,
                1,
                null,
                Instant.parse("2026-07-13T04:28:32Z"),
                candidate.publishedAt(),
                NOW,
                candidate.title(),
                detailUri
        );
        when(detailParser.parse(candidate, detailUri, "<html>detail</html>", NOW)).thenReturn(expectedEvent);

        Optional<ConfirmedMarketEvent> confirmed = adapter.fetchConfirmed(candidate);

        assertThat(confirmed).contains(expectedEvent);
    }

    @Test
    void fetch_confirmed_returns_empty_when_html_parsing_fails() {
        URI viewerUrl = URI.create("https://kind.krx.co.kr/common/disclsviewer.do?method=search&acptNo=20260713000658");
        MarketEventCandidate candidate = new MarketEventCandidate(
                KrMarket.KOSPI,
                "20260713000658",
                MarketEventType.CIRCUIT_BREAKER,
                1,
                null,
                Instant.parse("2026-07-13T04:29:00Z"),
                "CB 1단계",
                viewerUrl
        );
        when(httpClient.getText(viewerUrl, 1048576)).thenReturn("<html>bad viewer</html>");
        when(viewerParser.externalDetailUri(viewerUrl, "<html>bad viewer</html>"))
                .thenThrow(new IllegalArgumentException("invalid viewer HTML"));

        Optional<ConfirmedMarketEvent> confirmed = adapter.fetchConfirmed(candidate);

        assertThat(confirmed).isEmpty();
    }

    @Test
    void fetch_confirmed_propagates_transport_error() {
        URI viewerUrl = URI.create("https://kind.krx.co.kr/common/disclsviewer.do?method=search&acptNo=20260713000658");
        MarketEventCandidate candidate = new MarketEventCandidate(
                KrMarket.KOSPI,
                "20260713000658",
                MarketEventType.CIRCUIT_BREAKER,
                1,
                null,
                Instant.parse("2026-07-13T04:29:00Z"),
                "CB 1단계",
                viewerUrl
        );
        when(httpClient.getText(viewerUrl, 1048576))
                .thenThrow(new IllegalStateException("network timeout"));

        assertThatThrownBy(() -> adapter.fetchConfirmed(candidate))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("network timeout");
    }
}
