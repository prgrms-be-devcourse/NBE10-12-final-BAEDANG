package com.baedang.market.client.toss.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * {@code GET /api/v1/market-calendar/KR} 의 원본 응답 형태.
 *
 * <p><b>KR과 US는 응답 구조가 다릅니다</b> — 처음엔 같은 DTO로 공유하도록 설계했지만,
 * 2026-08-26 실제 호출 캡처(호영님 공유)로 확인해보니 US는 {@code integrated}로
 * 감싸지지 않고 {@code today} 바로 아래 {@code regularMarket} 등이 나열되는 다른 구조라
 * KR 전용({@code TossKrMarketCalendarResponse})과 US 전용
 * ({@code TossUsMarketCalendarResponse})으로 분리했습니다.
 *
 * <p><b>최상위가 {@code result} 로 한 번 더 감싸져 있습니다.</b> 처음 스펙 조회 때
 * 이 래핑을 놓쳤었습니다 — 민호님의 {@code TossPriceResponse}도 같은 패턴이라
 * Toss API 공통 응답 포맷으로 보입니다.
 *
 * <p>{@code nextBusinessDay} 는 휴장일일 때 {@code /market/status}의
 * {@code nextOpensAt}(다음 개장 시각)을 채우는 데 씁니다. {@code previousBusinessDay}는
 * 아직 쓰는 곳이 없어서 매핑하지 않았습니다.
 *
 * <p><b>{@code today.integrated} 가 {@code null} 이면 휴장일입니다.</b> boolean
 * "오픈 여부" 필드가 따로 없고, null 여부로 판단하는 구조입니다 (Toss 응답 형태 그대로).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TossKrMarketCalendarResponse(Result result) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Result(Today today, Today nextBusinessDay) {
    }

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
