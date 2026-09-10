package com.baedang.market.event.client.kind;

import com.baedang.market.event.entity.KrMarket;
import com.baedang.market.event.entity.MarketEventType;
import com.baedang.market.event.entity.SidecarDirection;
import com.baedang.market.event.model.KindRssBatch;
import com.baedang.market.event.model.MarketEventCandidate;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.net.URI;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class KindRssParser {

    private static final DateTimeFormatter RFC_1123 =
            DateTimeFormatter.RFC_1123_DATE_TIME.withLocale(Locale.ENGLISH);

    private static final Pattern CB = Pattern.compile(
            "^(유가증권시장|코스닥시장)\\s*매매거래\\s*일시중단\\s*\\(([123])단계\\s*CB\\s*발동\\)$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private static final Pattern SIDECAR_PROGRAM = Pattern.compile(
            "^(유가증권시장|코스닥시장)\\s*프로그램\\s*(매수|매도)호가\\s*일시\\s*효력정지\\s*\\(Side\\s*car\\s*발동\\)$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private static final Pattern SIDECAR_NAMED = Pattern.compile(
            "^(유가증권시장|코스닥시장)\\s*(매수|매도)\\s*사이드카\\s*\\(Side\\s*car\\)\\s*발동$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private static final Pattern ACPT_NO_EXTRACTOR = Pattern.compile("(?:^|[?&])acptNo=(\\d{14})(?:&|$)");

    private final KindUriPolicy uriPolicy;

    public KindRssParser(KindUriPolicy uriPolicy) {
        this.uriPolicy = Objects.requireNonNull(uriPolicy, "uriPolicy must not be null");
    }

    public KindRssBatch parse(KrMarket market, String xml) {
        Objects.requireNonNull(market, "market must not be null");
        if (xml == null || xml.isBlank()) {
            throw new IllegalArgumentException("xml must not be blank");
        }

        Document document = parseXmlDefensively(xml);
        Element root = document.getDocumentElement();
        if (root == null || !"rss".equalsIgnoreCase(root.getTagName())) {
            throw new IllegalArgumentException("Root element must be <rss>");
        }

        NodeList channelNodes = root.getElementsByTagName("channel");
        if (channelNodes.getLength() == 0) {
            throw new IllegalArgumentException("Missing <channel> element");
        }
        Element channel = (Element) channelNodes.item(0);

        NodeList itemNodes = channel.getElementsByTagName("item");
        if (itemNodes.getLength() > 100) {
            throw new IllegalArgumentException("KIND RSS item count exceeds 100: " + itemNodes.getLength());
        }

        List<MarketEventCandidate> candidates = new ArrayList<>();
        int parseErrorCount = 0;

        for (int i = 0; i < itemNodes.getLength(); i++) {
            Element item = (Element) itemNodes.item(i);
            String rawTitle = getChildText(item, "title");
            Classification classification = classifyTitle(rawTitle, market);

            if (classification == null) {
                // 일반 기업 공시이므로 후보가 아님 (파싱 에러 아님)
                continue;
            }

            if (classification.isConflict()) {
                parseErrorCount++;
                continue;
            }

            try {
                String rawLink = getChildText(item, "link");
                if (rawLink == null || rawLink.isBlank()) {
                    throw new IllegalArgumentException("Missing link");
                }
                URI rawUri = URI.create(rawLink.trim());
                URI viewerUri = uriPolicy.viewer(rawUri);

                Matcher acptMatcher = ACPT_NO_EXTRACTOR.matcher(viewerUri.getRawQuery() != null ? viewerUri.getRawQuery() : "");
                if (!acptMatcher.find()) {
                    throw new IllegalArgumentException("Missing or invalid acptNo in viewer URI: " + viewerUri);
                }
                String sourceEventId = acptMatcher.group(1);

                String pubDateStr = getChildText(item, "pubDate");
                if (pubDateStr == null || pubDateStr.isBlank()) {
                    throw new IllegalArgumentException("Missing pubDate");
                }
                Instant publishedAt = ZonedDateTime.parse(pubDateStr.trim(), RFC_1123).toInstant();

                MarketEventCandidate candidate = new MarketEventCandidate(
                        market,
                        sourceEventId,
                        classification.eventType(),
                        classification.stage(),
                        classification.direction(),
                        publishedAt,
                        rawTitle.trim(),
                        viewerUri
                );
                candidates.add(candidate);
            } catch (Exception e) {
                parseErrorCount++;
            }
        }

        return new KindRssBatch(candidates, parseErrorCount);
    }

    private Document parseXmlDefensively(String xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");

            DocumentBuilder builder = factory.newDocumentBuilder();
            return builder.parse(new InputSource(new StringReader(xml)));
        } catch (Exception e) {
            throw new IllegalArgumentException("XML 파싱 실패 또는 보안 제약 위반: " + e.getMessage(), e);
        }
    }

    private Classification classifyTitle(String rawTitle, KrMarket feedMarket) {
        if (rawTitle == null || rawTitle.isBlank()) {
            return null;
        }

        String normalized = rawTitle.replaceAll("\\p{Z}", " ").trim();
        normalized = normalized.replaceFirst("^\\[(?:유|코)\\]\\s*", "");
        normalized = normalized.replaceAll("\\s+", " ").trim();

        Matcher cbMatcher = CB.matcher(normalized);
        if (cbMatcher.matches()) {
            KrMarket titleMarket = "유가증권시장".equals(cbMatcher.group(1)) ? KrMarket.KOSPI : KrMarket.KOSDAQ;
            if (titleMarket != feedMarket) {
                return Classification.conflict();
            }
            int stage = Integer.parseInt(cbMatcher.group(2));
            return Classification.circuitBreaker(stage);
        }

        Matcher sidecarProgramMatcher = SIDECAR_PROGRAM.matcher(normalized);
        if (sidecarProgramMatcher.matches()) {
            KrMarket titleMarket = "유가증권시장".equals(sidecarProgramMatcher.group(1)) ? KrMarket.KOSPI : KrMarket.KOSDAQ;
            if (titleMarket != feedMarket) {
                return Classification.conflict();
            }
            SidecarDirection direction = "매수".equals(sidecarProgramMatcher.group(2))
                    ? SidecarDirection.BUY : SidecarDirection.SELL;
            return Classification.sidecar(direction);
        }

        Matcher sidecarNamedMatcher = SIDECAR_NAMED.matcher(normalized);
        if (sidecarNamedMatcher.matches()) {
            KrMarket titleMarket = "유가증권시장".equals(sidecarNamedMatcher.group(1)) ? KrMarket.KOSPI : KrMarket.KOSDAQ;
            if (titleMarket != feedMarket) {
                return Classification.conflict();
            }
            SidecarDirection direction = "매수".equals(sidecarNamedMatcher.group(2))
                    ? SidecarDirection.BUY : SidecarDirection.SELL;
            return Classification.sidecar(direction);
        }

        return null;
    }

    private String getChildText(Element parent, String tagName) {
        NodeList nodes = parent.getElementsByTagName(tagName);
        if (nodes.getLength() == 0) {
            return null;
        }
        return nodes.item(0).getTextContent();
    }

    private record Classification(
            MarketEventType eventType,
            Integer stage,
            SidecarDirection direction,
            boolean isConflict
    ) {
        static Classification circuitBreaker(int stage) {
            return new Classification(MarketEventType.CIRCUIT_BREAKER, stage, null, false);
        }

        static Classification sidecar(SidecarDirection direction) {
            return new Classification(MarketEventType.SIDECAR, null, direction, false);
        }

        static Classification conflict() {
            return new Classification(null, null, null, true);
        }
    }
}
