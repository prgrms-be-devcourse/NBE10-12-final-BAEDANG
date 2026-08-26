package com.baedang.market.client.toss.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * {@code GET /api/v1/market-calendar/US} 의 원본 응답 형태.
 *
 * <p><b>KR과 응답 구조가 다릅니다.</b> 2026-08-26 실제 호출 캡처(호영님 공유) 기준:
 * <pre>
 * { "result": { "today": {
 *     "date": "2026-08-26",
 *     "dayMarket":   { "startTime": ..., "endTime": ... },
 *     "preMarket":   { "startTime": ..., "endTime": ... },
 *     "regularMarket": { "startTime": ..., "endTime": ... },
 *     "afterMarket": { "startTime": ..., "endTime": ... }
 * } } }
 * </pre>
 * KR처럼 {@code integrated}로 한 번 더 감싸지 않고, {@code today} 바로 아래
 * {@code regularMarket}이 있습니다. 세션 구간도 {@code singlePriceAuctionStartTime}
 * 같은 필드 없이 {@code startTime}/{@code endTime}만 있습니다. {@code dayMarket}은
 * 우리 도메인(정규장 오픈 여부)에 필요 없어 매핑하지 않았습니다 — 무엇을 의미하는
 * 필드인지 아직 확인되지 않았습니다.
 *
 * <p>⚠️ <b>휴장일 응답 형태는 아직 확인되지 않았습니다.</b> 캡처된 예시는 정규장이
 * 열리는 평일뿐이라, 휴장일에 {@code regularMarket} 이 {@code null}로 오는지 다른
 * 형태인지 모릅니다. 지금은 KR과 같은 방식(필드가 없으면 휴장)으로 방어적으로
 * 처리해뒀습니다 — 실제 휴장일 응답을 확인하면 검증이 필요합니다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TossUsMarketCalendarResponse(Result result) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Result(Today today, Today nextBusinessDay) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Today(LocalDate date, SessionWindow regularMarket) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SessionWindow(OffsetDateTime startTime, OffsetDateTime endTime) {
    }
}
