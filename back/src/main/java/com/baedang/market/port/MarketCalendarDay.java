package com.baedang.market.port;

import com.baedang.stock.entity.MarketCountry;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * 특정 날짜의 정규장 운영 정보. {@link MarketCalendarPort#fetchKrMarketCalendar}/
 * {@link MarketCalendarPort#fetchUsMarketCalendar} 의 공통 반환 타입입니다.
 *
 * <p>KR/US 를 굳이 별도 타입으로 나누지 않았습니다 — {@code infra/schema.sql} 의
 * {@code market_calendar} 테이블도 두 시장을 {@code market_country} 컬럼 하나로
 * 구분할 뿐 컬럼 구조는 동일합니다.
 *
 * <p><b>{@code regularOpenAt}/{@code regularCloseAt} 은 Toss 응답을 그대로 옮긴 값입니다.</b>
 * 09:00/15:30, 22:30/05:00 같은 시각을 우리 코드에서 계산하지 않습니다 — DST·임시 휴장일
 * 처리를 전부 Toss 가 대신 해주므로, {@code AGENTS.md} 의 "미국 정규장 시간을 하드코딩하지
 * 말 것" 규칙을 지키는 가장 쉬운 방법은 이 값을 그대로 신뢰하는 것입니다.
 *
 * @param isOpen         이 날짜에 정규장이 열리는지. Toss 응답의 {@code integrated} 가
 *                       {@code null} 이면(휴장일) {@code false}.
 * @param regularOpenAt  정규장 시작 시각. {@code isOpen=false} 면 {@code null}.
 * @param regularCloseAt 정규장 종료 시각. {@code isOpen=false} 면 {@code null}.
 */
public record MarketCalendarDay(
        MarketCountry marketCountry,
        LocalDate tradeDate,
        boolean isOpen,
        OffsetDateTime regularOpenAt,
        OffsetDateTime regularCloseAt
) {
}
