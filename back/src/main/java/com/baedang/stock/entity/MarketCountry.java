package com.baedang.stock.entity;

import com.baedang.global.error.BusinessException;
import com.baedang.global.error.ErrorCode;
import com.baedang.global.normalizer.DomainNormalizer;

import java.time.ZoneId;
import java.util.Map;
import java.util.Optional;

/** 종목이 속한 시장의 국가. 거래 가능 시간과 세금 계산식이 여기서 갈립니다. */
public enum MarketCountry {
    KR(ZoneId.of("Asia/Seoul"), "KRW"),
    US(ZoneId.of("America/New_York"), "USD");

    private final ZoneId zoneId;
    private final String defaultCurrency;

    MarketCountry(ZoneId zoneId, String defaultCurrency) {
        this.zoneId = zoneId;
        this.defaultCurrency = defaultCurrency;
    }

    /** 거래소 현지 날짜 계산용입니다. 일봉 저장·표시 등 KST 고정 정책에는 대체 적용하지 않습니다. */
    public ZoneId zoneId() {
        return zoneId;
    }

    public String defaultCurrency() {
        return defaultCurrency;
    }

    /** 국가 코드만 파싱합니다. 누락·미지원 입력의 오류 코드와 상세 정보는 호출부에서 결정합니다. */
    public static Optional<MarketCountry> parse(String raw) {
        String normalized = DomainNormalizer.upperCode(raw);
        if (normalized == null) return Optional.empty();
        return switch (normalized) {
            case "KR" -> Optional.of(KR);
            case "US" -> Optional.of(US);
            default -> Optional.empty();
        };
    }

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
