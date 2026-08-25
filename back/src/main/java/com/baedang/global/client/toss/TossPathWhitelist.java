package com.baedang.global.client.toss;

/**
 * {@link TossSecuritiesClient} 가 호출할 수 있는 Toss Securities Open API 경로 전체.
 *
 * <p><b>여기 없는 경로는 호출 자체가 컴파일이 안 됩니다.</b> 문자열로 경로를 받는 대신
 * enum 상수로만 받기 때문에, 실수로 다른 경로(특히 주문 API)를 호출하는 코드 자체가
 * 작성될 수 없습니다 — {@code AGENTS.md} 의 "QuoteClient 는 허용된 경로만 화이트리스트로
 * 호출한다 / 주문 API 는 절대 호출하지 않는다" 규칙을 컴파일 타임에 강제합니다.
 *
 * <p><b>주문(order) 관련 경로는 절대 여기 추가하지 않습니다</b> — 실주문 위험.
 *
 * <p>경로는 실제 Toss OpenAPI 스펙({@code https://openapi.tossinvest.com/openapi-docs/latest/openapi.json})
 * 기준으로 확인한 값입니다. {@code docs/erd.md} 의 "Toss Securities API Mapping" 표와 동일합니다.
 *
 * <p>이 팀이 이번 스프린트에서 실제로 쓰는 건 {@link #EXCHANGE_RATE}, {@link #MARKET_CALENDAR_KR},
 * {@link #MARKET_CALENDAR_US} 뿐입니다. 나머지는 다른 Port(RankingPort, MarketDataPort,
 * SymbolInfoPort) 담당자가 그대로 재사용하도록 미리 등록해 둔 것입니다.
 */
public enum TossPathWhitelist {

    EXCHANGE_RATE("/api/v1/exchange-rate"),
    MARKET_CALENDAR_KR("/api/v1/market-calendar/KR"),
    MARKET_CALENDAR_US("/api/v1/market-calendar/US"),

    PRICES("/api/v1/prices"),
    CANDLES("/api/v1/candles"),

    STOCKS("/api/v1/stocks"),
    STOCKS_ALL("/api/v1/stocks/all"),
    /** {@code {symbol}} 은 {@code get(...)} 호출 시 pathVariables 로 채워야 합니다. */
    STOCK_WARNINGS("/api/v1/stocks/{symbol}/warnings"),

    RANKINGS("/api/v1/rankings");

    private final String path;

    TossPathWhitelist(String path) {
        this.path = path;
    }

    public String path() {
        return path;
    }
}
