package com.baedang.market.port;

import java.time.LocalDate;

/**
 * 환율, 국내/해외 장 운영 정보를 가져오는 포트.
 *
 * <p>이 인터페이스는 <b>우리 서비스가 무엇을 필요로 하는지</b>만 선언합니다 — Toss 의
 * 엔드포인트 이름, 파라미터, 응답 구조는 전혀 몰라야 합니다. 실제 호출은
 * {@code TossMarketCalendarAdapter}({@code toss.enabled=true}일 때만 등록)가 담당합니다.
 *
 * <p>Service 계층은 이 포트만 주입받으면 되고, Toss 호출 방식을 전혀 알 필요가
 * 없습니다. 실제 사용처에는 동일 시장·날짜 요청을 공유하는 캐싱 데코레이터가
 * 주입되고, 이 데코레이터가 Toss 원본 어댑터에 위임합니다.
 */
public interface MarketCalendarPort {

    /**
     * USD/KRW 환율을 조회한다. MVP 는 이 통화쌍만 다룬다 (docs/erd.md 참고).
     */
    ExchangeRateQuote fetchExchangeRate();

    /**
     * 국내(KR) 장 운영 정보를 조회한다.
     *
     * @param date 조회할 날짜 (보통 오늘)
     */
    MarketCalendarDay fetchKrMarketCalendar(LocalDate date);

    /**
     * 해외(US) 장 운영 정보를 조회한다. DST 여부에 따라 정규장 시각이 달라지는데,
     * 이 계산은 Toss 가 해주므로 여기서 별도 분기가 필요 없다.
     *
     * @param date 조회할 날짜 (보통 오늘, KST 기준)
     */
    MarketCalendarDay fetchUsMarketCalendar(LocalDate date);
}
