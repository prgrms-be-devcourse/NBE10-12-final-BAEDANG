package com.baedang.stock.entity;

import com.baedang.global.error.BusinessException;
import com.baedang.global.error.ErrorCode;

import java.util.Map;

/** 종목이 속한 시장의 국가. 거래 가능 시간과 세금 계산식이 여기서 갈립니다. */
public enum MarketCountry {
    KR, US;

    private static final Map<String, MarketCountry> marketsNameMap = Map.of(
            "KOSPI", KR,
            "KOSDAQ", KR,
            "KR_ETC", KR,

            "NYSE", US,
            "NASDAQ", US,
            "AMEX", US,
            "US_ETC", US
    );

    /**
     * 마켓 이름에서 국가를 유도합니다. 적재 1단계는 호출 인자에서, 2단계는 응답 값에서
     * 같은 규칙으로 유도해야 {@code KR_ETC → KOSPI} 교정 때 국가가 어긋나지 않습니다.
     *
     * <p>모르는 마켓이면 바로 끊습니다. {@code null} 을 돌려주면 {@code market_country} 가
     * NOT NULL 이라 flush 시점에야 터지고, 그때는 원인에서 한참 멀어져 있습니다.
     */
    public static MarketCountry fromMarket(String market) {
        MarketCountry marketCountry = marketsNameMap.get(market);
        if (marketCountry == null) {
            throw new BusinessException(ErrorCode.TOSS_API_ERROR, "알 수 없는 마켓: " + market);
        }
        return marketCountry;
    }

    public static Map<String, MarketCountry> marketsNameMap() {
        return marketsNameMap;
    }
}
