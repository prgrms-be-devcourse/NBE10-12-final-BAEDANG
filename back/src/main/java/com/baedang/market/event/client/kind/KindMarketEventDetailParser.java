package com.baedang.market.event.client.kind;

import com.baedang.market.event.entity.KrMarket;
import com.baedang.market.event.entity.SidecarDirection;
import com.baedang.market.event.entity.MarketEventType;
import com.baedang.market.event.model.ConfirmedMarketEvent;
import com.baedang.market.event.model.MarketEventCandidate;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.net.URI;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public class KindMarketEventDetailParser {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private static final List<DateTimeFormatter> DATE_TIME_FORMATTERS = List.of(
            DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss").withResolverStyle(ResolverStyle.STRICT),
            DateTimeFormatter.ofPattern("uuuu년MM월dd일 HH시mm분ss초").withResolverStyle(ResolverStyle.STRICT),
            DateTimeFormatter.ofPattern("uuuu-MM-dd HH시mm분ss초").withResolverStyle(ResolverStyle.STRICT)
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

        String contentRow = rowValue(document,
                label -> label.replaceFirst("^\\d+\\.\\s*", "").equals("내용"));
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
        KindRssParser.Classification classification =
                KindRssParser.classifyTitle(detailTitle, candidate.market());
        if (classification == null
                || classification.isConflict()
                || classification.eventType() != candidate.eventType()
                || !Objects.equals(classification.stage(), candidate.circuitBreakerStage())
                || classification.direction() != candidate.sidecarDirection()) {
            throw new IllegalArgumentException("상세 제목이 RSS 후보와 일치하지 않습니다: " + detailTitle);
        }
    }
    private void validateDurationContent(String content, MarketEventCandidate candidate) {
        String compact = normalize(content).replace(" ", "");
        if (candidate.eventType() == MarketEventType.CIRCUIT_BREAKER) {
            if (candidate.circuitBreakerStage() == 1 || candidate.circuitBreakerStage() == 2) {
                String market = candidate.market() == KrMarket.KOSPI ? "유가증권시장" : "코스닥시장";
                boolean haltStatement = compact.contains(market + "의매매거래가중단")
                        || compact.contains(market + "매매거래일시중단")
                        || compact.contains(market + "매매거래중단");
                if (!compact.contains("향후20분간") || !haltStatement) {
                    throw new IllegalArgumentException("CB 1·2단계 상세 내용이 올바르지 않습니다: " + content);
                }
            } else if (candidate.circuitBreakerStage() == 3) {
                // 1·2단계와 대칭으로 시장명이 끼어든 표현을 허용한다. 브랜치의 공식 1단계 공시 본문이
                // 3단계를 설명할 때 "당일 유가증권시장 매매거래 종료"로 적고 있다.
                String market = candidate.market() == KrMarket.KOSPI ? "유가증권시장" : "코스닥시장";
                boolean closeStatement = compact.contains("당일매매거래종료")
                        || compact.contains("당일" + market + "매매거래종료")
                        || compact.contains("당일" + market + "의매매거래종료");
                if (!closeStatement) {
                    throw new IllegalArgumentException("CB 3단계는 당일 매매거래 종료 명시가 필요합니다: " + content);
                }
            }
        } else {
            String direction = candidate.sidecarDirection() == SidecarDirection.BUY ? "매수" : "매도";
            boolean quoteHalt = compact.contains("프로그램" + direction + "호가")
                    && (compact.contains("호가의효력이정지")
                    || compact.contains("호가효력이정지")
                    || compact.contains("호가효력정지"));
            if (!compact.contains("향후5분간") || !quoteHalt) {
                throw new IllegalArgumentException("사이드카 상세 내용이 올바르지 않습니다: " + content);
            }
        }
    }

    private Instant parseTriggerTime(String dateTimeText) {
        String normalized = normalize(dateTimeText);
        for (DateTimeFormatter formatter : DATE_TIME_FORMATTERS) {
            try {
                return LocalDateTime.parse(normalized, formatter).atZone(KST).toInstant();
            } catch (DateTimeParseException ignored) {
                // Try the next observed KIND layout.
            }
        }
        throw new IllegalArgumentException("상세 공시 발동시각 파싱 실패: " + dateTimeText);
    }

    private static String rowValue(Document document, Predicate<String> labelMatch) {
        return document.select(".xforms table tr").stream()
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
