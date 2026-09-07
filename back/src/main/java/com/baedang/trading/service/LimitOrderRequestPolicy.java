package com.baedang.trading.service;

import com.baedang.global.error.BusinessException;
import com.baedang.global.error.ErrorCode;
import com.baedang.global.normalizer.DomainNormalizer;
import com.baedang.stock.entity.MarketCountry;
import com.baedang.trading.model.ClientOrderRetryPolicy;
import java.math.BigDecimal;
import java.util.regex.Pattern;
import static com.baedang.trading.support.DecimalScaleValidator.isRepresentableAtScale;
import static com.baedang.trading.support.NumericBounds.MONEY_LIMIT;

/** 원본 지정가 및 통화 검증. 사용자 입력은 반올림하지 않습니다. */
public final class LimitOrderRequestPolicy {
    private static final Pattern PRICE = Pattern.compile("\\d+(\\.\\d+)?");
    private LimitOrderRequestPolicy() {}

    public static String currency(String value, MarketCountry country) {
        String currency = DomainNormalizer.currency(value);
        if (!("KRW".equals(currency) || (country == MarketCountry.US && "USD".equals(currency)))) throw invalid();
        return currency;
    }

    public static BigDecimal price(String value, String currency) {
        if (value == null || value.length() > 32 || !PRICE.matcher(value.trim()).matches()) throw invalid();
        BigDecimal price = new BigDecimal(value.trim());
        if (price.signum() <= 0 || price.compareTo(MONEY_LIMIT) >= 0
                || !isRepresentableAtScale(price, "KRW".equals(currency) ? 0 : 2)) throw invalid();
        return price;
    }

    private static BusinessException invalid() {
        return new BusinessException(ErrorCode.INVALID_INPUT, ClientOrderRetryPolicy.SAME_CLIENT_ORDER_ID.asData());
    }
}
