package com.baedang.market.event.client.kind;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class KindViewerParser {

    private static final Pattern VIEWER_ACPT_PATTERN = Pattern.compile("(?:^|[?&])acptNo=(\\d{14})(?:&|$)");
    private static final Pattern DETAIL_ACPT_PATTERN = Pattern.compile("/external/\\d{4}/\\d{2}/\\d{2}/\\d{6}/(\\d{14})/\\d{5}\\.htm");

    private final KindUriPolicy uriPolicy;

    public KindViewerParser(KindUriPolicy uriPolicy) {
        this.uriPolicy = Objects.requireNonNull(uriPolicy, "uriPolicy must not be null");
    }

    public URI externalDetailUri(URI viewerUri, String html) {
        Objects.requireNonNull(viewerUri, "viewerUri must not be null");
        if (html == null || html.isBlank()) {
            throw new IllegalArgumentException("html must not be blank");
        }

        String expectedAcptNo = extractViewerAcptNo(viewerUri);

        Document document = Jsoup.parse(html, viewerUri.toString());
        Set<String> externalLinks = new LinkedHashSet<>();

        for (Element element : document.select("iframe[src], frame[src], a[href]")) {
            String link = element.hasAttr("src") ? element.attr("src") : element.attr("href");
            if (link != null && link.contains("/external/")) {
                externalLinks.add(link.trim());
            }
        }

        if (externalLinks.size() != 1) {
            throw new IllegalArgumentException("정확히 1개의 external 상세 본문 링크가 필요합니다. 발견: " + externalLinks.size());
        }

        String rawLink = externalLinks.iterator().next();
        URI validatedUri = uriPolicy.externalDetail(URI.create(rawLink));

        String detailAcptNo = extractDetailAcptNo(validatedUri.getPath());
        if (!expectedAcptNo.equals(detailAcptNo)) {
            throw new IllegalArgumentException("viewer의 acptNo(" + expectedAcptNo + ")와 본문의 acptNo(" + detailAcptNo + ")가 일치하지 않습니다");
        }

        return validatedUri;
    }

    private String extractViewerAcptNo(URI viewerUri) {
        String query = viewerUri.getRawQuery();
        if (query != null) {
            Matcher matcher = VIEWER_ACPT_PATTERN.matcher(query);
            if (matcher.find()) {
                return matcher.group(1);
            }
        }
        throw new IllegalArgumentException("viewer URI에서 acptNo를 찾을 수 없습니다: " + viewerUri);
    }

    private String extractDetailAcptNo(String path) {
        if (path != null) {
            Matcher matcher = DETAIL_ACPT_PATTERN.matcher(path);
            if (matcher.find()) {
                return matcher.group(1);
            }
        }
        throw new IllegalArgumentException("external detail 경로에서 acptNo를 추출할 수 없습니다: " + path);
    }
}
