package com.baedang.market.event.client.kind;

import com.baedang.market.event.entity.KrMarket;
import com.baedang.market.event.model.ConfirmedMarketEvent;
import com.baedang.market.event.model.KindRssBatch;
import com.baedang.market.event.model.MarketEventCandidate;
import com.baedang.market.event.port.MarketEventSourcePort;

import java.net.URI;
import java.time.Clock;
import java.util.Objects;
import java.util.Optional;

public class KindMarketEventAdapter implements MarketEventSourcePort {

    private static final int MAX_BYTES = 1048576; // 1 MiB

    private final KindHttpClient httpClient;
    private final KindUriPolicy uriPolicy;
    private final KindRssParser rssParser;
    private final KindViewerParser viewerParser;
    private final KindMarketEventDetailParser detailParser;
    private final Clock clock;

    public KindMarketEventAdapter(
            KindHttpClient httpClient,
            KindUriPolicy uriPolicy,
            KindRssParser rssParser,
            KindViewerParser viewerParser,
            KindMarketEventDetailParser detailParser,
            Clock clock
    ) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient must not be null");
        this.uriPolicy = Objects.requireNonNull(uriPolicy, "uriPolicy must not be null");
        this.rssParser = Objects.requireNonNull(rssParser, "rssParser must not be null");
        this.viewerParser = Objects.requireNonNull(viewerParser, "viewerParser must not be null");
        this.detailParser = Objects.requireNonNull(detailParser, "detailParser must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Override
    public KindRssBatch fetchCandidates(KrMarket market) {
        Objects.requireNonNull(market, "market must not be null");
        URI rssUri = uriPolicy.rss(market);
        String xml = httpClient.getText(rssUri, MAX_BYTES);
        return rssParser.parse(market, xml);
    }

    @Override
    public Optional<ConfirmedMarketEvent> fetchConfirmed(MarketEventCandidate candidate) {
        Objects.requireNonNull(candidate, "candidate must not be null");
        try {
            URI viewerUri = uriPolicy.viewer(candidate.viewerUrl());
            String viewerHtml = httpClient.getText(viewerUri, MAX_BYTES);
            URI detailUri = viewerParser.externalDetailUri(viewerUri, viewerHtml);
            String detailHtml = httpClient.getText(detailUri, MAX_BYTES);
            ConfirmedMarketEvent event = detailParser.parse(candidate, detailUri, detailHtml, clock.instant());
            return Optional.of(event);
        } catch (IllegalArgumentException e) {
            // HTML 파싱 실패 또는 검증 불일치 -> 후보 확인 실패로 간주
            return Optional.empty();
        }
        // 전송/네트워크 실패(IllegalStateException 등)는 그대로 전파되어 수집기가 외부 장애로 분류
    }
}
