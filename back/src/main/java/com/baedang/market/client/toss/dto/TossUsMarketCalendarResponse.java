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
 * <p><b>휴장일 응답도 2026-08-27 실제 호출 캡처(호영님 공유, 주말 8/22·8/23 및 그
 * 앞뒤 평일 조회)로 확인했습니다.</b> 필드 자체가 사라지는 게 아니라
 * {@code "regularMarket": null}처럼 명시적으로 {@code null}이 옵니다:
 * <pre>
 * { "result": { "today": {
 *     "date": "2026-08-23",
 *     "dayMarket": null, "preMarket": null, "regularMarket": null, "afterMarket": null
 * }, "nextBusinessDay": { "date": "2026-08-24", "regularMarket": { "startTime": "2026-08-24T22:30:00+09:00", ... } } } }
 * </pre>
 * Jackson 입장에서는 "필드 없음"과 "필드가 {@code null}" 이 동일하게 처리되므로,
 * {@code today.regularMarket() == null} 로 휴장일을 판단하는 기존 로직이 이 형태에도
 * 그대로 맞습니다 — 코드 변경은 필요 없었고, 이 문서와 테스트만 실제 캡처로 갱신했습니다.
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
