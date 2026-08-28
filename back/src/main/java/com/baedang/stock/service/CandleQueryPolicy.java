package com.baedang.stock.service;

import com.baedang.global.error.BusinessException;
import com.baedang.global.error.ErrorCode;
import com.baedang.stock.entity.MarketCountry;
import com.baedang.stock.model.CandleQuery;
import com.baedang.stock.model.CandleQueryInterval;
import com.baedang.stock.model.CandleRange;
import org.springframework.stereotype.Component;

import java.util.Locale;

@Component
public class CandleQueryPolicy {

    private static final int MINUTE_CANDLE_COUNT = 200;
    private static final int ONE_MONTH_DAILY_COUNT = 22;
    private static final int SIX_MONTH_DAILY_COUNT = 130;
    private static final int ONE_YEAR_DAILY_COUNT = 250;

    public MarketCountry parseMarketCountry(String value) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "marketCountry가 비어 있음");
        }
        try {
            return MarketCountry.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "marketCountry=" + value);
        }
    }

    public CandleQuery parse(String interval, String range) {
        CandleQueryInterval parsedInterval = parseInterval(interval);
        CandleRange parsedRange = parseRange(range);

        int count = switch (parsedInterval) {
            case ONE_MINUTE -> {
                if (parsedRange != CandleRange.ONE_DAY) throw invalidCombination(interval, range);
                yield MINUTE_CANDLE_COUNT;
            }
            case ONE_DAY -> switch (parsedRange) {
                case ONE_MONTH -> ONE_MONTH_DAILY_COUNT;
                case SIX_MONTHS -> SIX_MONTH_DAILY_COUNT;
                case ONE_YEAR -> ONE_YEAR_DAILY_COUNT;
                default -> throw invalidCombination(interval, range);
            };
        };
        return new CandleQuery(parsedInterval, parsedRange, count);
    }

    private CandleQueryInterval parseInterval(String value) {
        if (value == null) throw invalidCombination(null, null);
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "1m" -> CandleQueryInterval.ONE_MINUTE;
            case "1d" -> CandleQueryInterval.ONE_DAY;
            default -> throw invalidCombination(value, null);
        };
    }

    private CandleRange parseRange(String value) {
        if (value == null) throw invalidCombination(null, null);
        return switch (value.trim().toUpperCase(Locale.ROOT)) {
            case "1D" -> CandleRange.ONE_DAY;
            case "1M" -> CandleRange.ONE_MONTH;
            case "6M" -> CandleRange.SIX_MONTHS;
            case "1Y" -> CandleRange.ONE_YEAR;
            default -> throw invalidCombination(null, value);
        };
    }

    private BusinessException invalidCombination(String interval, String range) {
        return new BusinessException(
                ErrorCode.INVALID_INTERVAL_RANGE,
                "interval=" + interval + ", range=" + range);
    }
}
