package com.baedang.market.client.toss.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * {@code GET /api/v1/market-calendar/KR}, {@code GET /api/v1/market-calendar/US} 의
 * 원본 응답 형태. 두 엔드포인트 응답 구조가 동일해서 DTO 를 공유합니다.
 *
 * <p>{@code nextBusinessDay} 는 휴장일일 때 {@code /market/status}의
 * {@code nextOpensAt}(다음 개장 시각)을 채우는 데 씁니다. {@code previousBusinessDay}는
 * 아직 쓰는 곳이 없어서 매핑하지 않았습니다 — {@code @JsonIgnoreProperties(ignoreUnknown = true)}
 * 덕분에 무시해도 역직렬화가 깨지지 않습니다. 필요해지면 필드만 추가하면 됩니다.
 *
 * <p><b>{@code today.integrated} 가 {@code null} 이면 휴장일입니다.</b> boolean
 * "오픈 여부" 필드가 따로 없고, null 여부로 판단하는 구조입니다 (Toss 응답 형태 그대로).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TossMarketCalendarResponse(Today today, Today nextBusinessDay) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Today(LocalDate date, IntegratedSession integrated) {
    }

    /** 정규장 외에 pre/after market 도 있지만, 이번 스프린트는 regularMarket 만 쓴다. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record IntegratedSession(SessionWindow preMarket, SessionWindow regularMarket, SessionWindow afterMarket) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SessionWindow(
            OffsetDateTime startTime,
            OffsetDateTime singlePriceAuctionStartTime,
            OffsetDateTime endTime
    ) {
    }
}
