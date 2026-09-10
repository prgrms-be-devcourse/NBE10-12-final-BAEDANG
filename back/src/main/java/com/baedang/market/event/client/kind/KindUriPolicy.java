package com.baedang.market.event.client.kind;

import com.baedang.market.event.entity.KrMarket;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class KindUriPolicy {

    private static final Pattern ACPT_NO_PATTERN = Pattern.compile("^\\d{14}$");
    private static final Pattern VIEWER_QUERY_PATTERN = Pattern.compile("(?:^|&)method=search(?:&|$)");
    private static final Pattern VIEWER_ACPT_PATTERN = Pattern.compile("(?:^|&)acptNo=(\\d{14})(?:&|$)");
    private static final Pattern EXTERNAL_PATH_PATTERN =
            Pattern.compile("^/external/\\d{4}/\\d{2}/\\d{2}/\\d{6}/\\d{14}/\\d{5}\\.htm$");

    private final URI baseUrl;

    public KindUriPolicy(URI baseUrl) {
        this.baseUrl = validateBaseUrl(baseUrl);
    }

    public URI rss(KrMarket market) {
        Objects.requireNonNull(market, "market must not be null");
        return UriComponentsBuilder.fromUri(baseUrl)
                .replacePath("/disclosure/rsstodaydistribute.do")
                .replaceQuery("method=searchRssTodayDistribute&repIsuSrtCd=&mktTpCd="
                        + market.kindCode()
                        + "&searchCorpName=&currentPageSize=100")
                .build()
                .toUri();
    }

    public URI viewer(String acptNo) {
        if (acptNo == null || !ACPT_NO_PATTERN.matcher(acptNo).matches()) {
            throw new IllegalArgumentException("acptNo는 14자리 숫자여야 합니다: " + acptNo);
        }
        return UriComponentsBuilder.fromUri(baseUrl)
                .replacePath("/common/disclsviewer.do")
                .replaceQuery("method=search&acptNo=" + acptNo)
                .build()
                .toUri();
    }

    public URI viewer(URI uri) {
        Objects.requireNonNull(uri, "viewer uri must not be null");
        URI resolved = resolveAndValidateHost(uri);

        if (!"/common/disclsviewer.do".equals(resolved.getPath())) {
            throw new IllegalArgumentException("viewer path가 올바르지 않습니다: " + resolved.getPath());
        }
        String query = resolved.getRawQuery();
        if (query == null || !VIEWER_QUERY_PATTERN.matcher(query).find() || !VIEWER_ACPT_PATTERN.matcher(query).find()) {
            throw new IllegalArgumentException("viewer query 파라미터가 올바르지 않습니다: " + query);
        }
        if (resolved.getFragment() != null) {
            throw new IllegalArgumentException("viewer URI에는 fragment가 허용되지 않습니다");
        }
        return resolved;
    }

    public URI externalDetail(URI uri) {
        Objects.requireNonNull(uri, "external detail uri must not be null");
        URI resolved = resolveAndValidateHost(uri);

        if (resolved.getRawQuery() != null) {
            throw new IllegalArgumentException("external detail URI에는 query가 허용되지 않습니다");
        }
        if (resolved.getFragment() != null) {
            throw new IllegalArgumentException("external detail URI에는 fragment가 허용되지 않습니다");
        }
        String rawPath = resolved.getRawPath();
        if (rawPath == null || rawPath.contains("..") || rawPath.toLowerCase().contains("%2e")) {
            throw new IllegalArgumentException("경로 탐색은 허용되지 않습니다");
        }
        if (!EXTERNAL_PATH_PATTERN.matcher(resolved.getPath()).matches()) {
            throw new IllegalArgumentException("external detail path 형식이 올바르지 않습니다: " + resolved.getPath());
        }
        return resolved;
    }

    private URI resolveAndValidateHost(URI uri) {
        URI resolved = uri.isAbsolute() ? uri : baseUrl.resolve(uri);

        if (!baseUrl.getScheme().equalsIgnoreCase(resolved.getScheme())) {
            throw new IllegalArgumentException("scheme이 올바르지 않습니다: " + resolved.getScheme());
        }
        if (!baseUrl.getHost().equalsIgnoreCase(resolved.getHost())) {
            throw new IllegalArgumentException("허용되지 않은 host입니다: " + resolved.getHost());
        }
        if (resolved.getPort() != -1 && resolved.getPort() != baseUrl.getPort() && resolved.getPort() != 443) {
            throw new IllegalArgumentException("비표준 포트는 허용되지 않습니다: " + resolved.getPort());
        }
        if (resolved.getUserInfo() != null) {
            throw new IllegalArgumentException("user-info는 허용되지 않습니다");
        }
        return resolved;
    }

    private static URI validateBaseUrl(URI uri) {
        Objects.requireNonNull(uri, "baseUrl must not be null");
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("baseUrl scheme은 https여야 합니다: " + uri.getScheme());
        }
        if (uri.getHost() == null) {
            throw new IllegalArgumentException("baseUrl host는 필수입니다");
        }
        return uri;
    }
}
