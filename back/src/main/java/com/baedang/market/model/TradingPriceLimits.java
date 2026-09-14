package com.baedang.market.model;

import com.baedang.market.entity.QuoteSnapshot;
import com.baedang.stock.entity.MarketCountry;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** 거래용 당일 가격 범위. 미국의 제한 없음과 국내 데이터 미확보를 구분합니다. */
public record TradingPriceLimits(LocalDate date, BigDecimal lower, BigDecimal upper) {
    private static final BigDecimal MAX_PRICE_EXCLUSIVE = new BigDecimal("1000000000000000");

    public static TradingPriceLimits from(QuoteSnapshot quote) {
        return quote == null ? new TradingPriceLimits(null, null, null)
                : new TradingPriceLimits(quote.getPriceLimitDate(), quote.getLowerLimit(), quote.getUpperLimit());
    }

    /** 정규장 여부는 호출자가 세션 근거로 별도 검사합니다. 이 메서드는 외부 조회를 하지 않습니다. */
    public boolean usable(MarketCountry country, Instant now) {
        if (country == null || now == null) return false;
        return country == MarketCountry.US || (date != null
                && date.equals(now.atZone(country.zoneId()).toLocalDate())
                && validPrice(lower) && validPrice(upper) && lower.compareTo(upper) <= 0);
    }

    public boolean contains(MarketCountry country, BigDecimal price) {
        if (country == null) return false;
        return price != null && price.signum() > 0 && (country == MarketCountry.US
                || (lower != null && upper != null && price.compareTo(lower) >= 0 && price.compareTo(upper) <= 0));
    }

    private static boolean validPrice(BigDecimal price) {
        return price != null && price.signum() > 0 && price.stripTrailingZeros().scale() <= 4
                && price.compareTo(MAX_PRICE_EXCLUSIVE) < 0;
    }
}
