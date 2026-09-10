package com.baedang.market.event.client.kind;

import com.baedang.market.event.entity.KrMarket;
import com.baedang.market.event.entity.MarketEventType;
import com.baedang.market.event.entity.SidecarDirection;
import com.baedang.market.event.model.ConfirmedMarketEvent;
import com.baedang.market.event.model.MarketEventCandidate;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.net.URI;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class KindMarketEventDetailParser {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private static final Pattern DATE_TIME_PATTERN = Pattern.compile(
            "(\\d{4})[-년\\s]+(\\d{1,2})[-월\\s]+(\\d{1,2})[일]?\\s+(\\d{1,2})[:시\\s]+(\\d{1,2})[:분\\s]+(\\d{1,2})[초]?"
    );

    private final KindUriPolicy uriPolicy;

    public KindMarketEventDetailParser(KindUriPolicy uriPolicy) {
        this.uriPolicy = Objects.requireNonNull(uriPolicy, "uriPolicy must not be null");
    }

    public ConfirmedMarketEvent parse(
            MarketEventCandidate candidate,
            URI detailUri,
            String html,
            Instant receivedAt
    ) {
        Objects.requireNonNull(candidate, "candidate must not be null");
        Objects.requireNonNull(detailUri, "detailUri must not be null");
        Objects.requireNonNull(receivedAt, "receivedAt must not be null");
        if (html == null || html.isBlank()) {
            throw new IllegalArgumentException("detail html must not be blank");
        }

        URI validatedDetailUri = uriPolicy.externalDetail(detailUri);
        Document document = Jsoup.parse(html, validatedDetailUri.toString());

        Element titleElement = document.selectFirst(".xforms_title");
        if (titleElement == null || titleElement.text().isBlank()) {
            throw new IllegalArgumentException("상세 공시 .xforms_title이 없습니다");
        }
        String detailTitle = normalize(titleElement.text());
        validateTitleMatchesCandidate(detailTitle, candidate);

        String contentRow = rowValue(document, label -> label.contains("내용") || label.contains("조치"));
        validateDurationContent(contentRow, candidate);

        String dateTimeRow = rowValue(document, label -> label.contains("일자") || label.contains("시각") || label.contains("일시"));
        Instant triggeredAt = parseTriggerTime(dateTimeRow);

        return new ConfirmedMarketEvent(
                candidate.sourceEventId(),
                candidate.market(),
                candidate.eventType(),
                candidate.circuitBreakerStage(),
                candidate.sidecarDirection(),
                triggeredAt,
                candidate.publishedAt(),
                receivedAt,
                candidate.title(),
                validatedDetailUri
        );
    }

    private void validateTitleMatchesCandidate(String detailTitle, MarketEventCandidate candidate) {
        String expectedMarketName = candidate.market() == KrMarket.KOSPI ? "유가증권시장" : "코스닥시장";
        if (!detailTitle.contains(expectedMarketName)) {
            throw new IllegalArgumentException("상세 제목 시장 불일치: 기대=" + expectedMarketName + ", 본문=" + detailTitle);
        }

        if (candidate.eventType() == MarketEventType.CIRCUIT_BREAKER) {
            if (!detailTitle.contains("일시중단") || !detailTitle.toUpperCase().contains("CB")) {
                throw new IllegalArgumentException("상세 제목 서킷브레이커 유형 불일치: " + detailTitle);
            }
            String expectedStageStr = candidate.circuitBreakerStage() + "단계";
            if (!detailTitle.contains(expectedStageStr)) {
                throw new IllegalArgumentException("상세 제목 CB 단계 불일치: 기대=" + expectedStageStr + ", 본문=" + detailTitle);
            }
        } else if (candidate.eventType() == MarketEventType.SIDECAR) {
            String expectedDirection = candidate.sidecarDirection() == SidecarDirection.BUY ? "매수" : "매도";
            if (!detailTitle.contains(expectedDirection)) {
                throw new IllegalArgumentException("상세 제목 사이드카 방향 불일치: 기대=" + expectedDirection + ", 본문=" + detailTitle);
            }
        }
    }

    private void validateDurationContent(String content, MarketEventCandidate candidate) {
        if (candidate.eventType() == MarketEventType.CIRCUIT_BREAKER) {
            if (candidate.circuitBreakerStage() == 1 || candidate.circuitBreakerStage() == 2) {
                if (!content.contains("20분간")) {
                    throw new IllegalArgumentException("CB 1·2단계는 20분간 중단 명시가 필요합니다: " + content);
                }
            } else if (candidate.circuitBreakerStage() == 3) {
                if (!content.contains("종료")) {
                    throw new IllegalArgumentException("CB 3단계는 당일 매매거래 종료 명시가 필요합니다: " + content);
                }
            }
        } else if (candidate.eventType() == MarketEventType.SIDECAR) {
            if (!content.contains("5분간")) {
                throw new IllegalArgumentException("사이드카는 5분간 효력정지 명시가 필요합니다: " + content);
            }
        }
    }

    private Instant parseTriggerTime(String dateTimeText) {
        Matcher matcher = DATE_TIME_PATTERN.matcher(dateTimeText);
        if (!matcher.find()) {
            throw new IllegalArgumentException("상세 공시 발동시각 파싱 실패: " + dateTimeText);
        }

        int year = Integer.parseInt(matcher.group(1));
        int month = Integer.parseInt(matcher.group(2));
        int day = Integer.parseInt(matcher.group(3));
        int hour = Integer.parseInt(matcher.group(4));
        int minute = Integer.parseInt(matcher.group(5));
        int second = Integer.parseInt(matcher.group(6));

        LocalDateTime localDateTime = LocalDateTime.of(year, month, day, hour, minute, second);
        return localDateTime.atZone(KST).toInstant();
    }

    private static String rowValue(Document document, Predicate<String> labelMatch) {
        return document.select(".xforms table tr, table tr").stream()
                .filter(row -> !row.select("td").isEmpty())
                .filter(row -> labelMatch.test(normalize(row.select("td").first().text())))
                .findFirst()
                .map(row -> row.select("td").stream().skip(1)
                        .map(Element::text).collect(Collectors.joining(" ")))
                .orElseThrow(() -> new IllegalArgumentException("required KIND row missing"));
    }

    private static String normalize(String text) {
        if (text == null) return "";
        return text.replaceAll("\\p{Z}", " ").replaceAll("\\s+", " ").trim();
    }
}
