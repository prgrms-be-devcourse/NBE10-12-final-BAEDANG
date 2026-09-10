package com.baedang.stock.service;

import com.baedang.global.error.BusinessException;
import com.baedang.global.error.ErrorCode;
import com.baedang.global.normalizer.DomainNormalizer;
import com.baedang.stock.entity.MarketCountry;
import com.baedang.stock.model.CandleQuery;
import com.baedang.stock.model.CandleQueryInterval;
import com.baedang.stock.model.CandleRange;
import org.springframework.stereotype.Component;

@Component
public class CandleQueryPolicy {

    // count는 응답으로 돌려줄 "봉 개수"다. 원본 1분봉 개수가 아니다.
    // (5m+1W는 봉 390개 = 1분봉 1,950개라 토스 200개 상한과 별개로 잡는다)
    private static final int MINUTE_CANDLE_COUNT = 200;
    private static final int FIVE_MINUTE_ONE_DAY_COUNT = 78;
    private static final int FIVE_MINUTE_ONE_WEEK_COUNT = 390;
    private static final int TEN_MINUTE_ONE_WEEK_COUNT = 195;
    private static final int ONE_MONTH_DAILY_COUNT = 22;
    private static final int SIX_MONTH_DAILY_COUNT = 130;
    private static final int ONE_YEAR_DAILY_COUNT = 250;
    private static final int SIX_MONTH_WEEKLY_COUNT = 26;
    private static final int ONE_YEAR_WEEKLY_COUNT = 52;

    public MarketCountry parseMarketCountry(String value) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "marketCountry가 비어 있음");
        }
        return MarketCountry.parse(value)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_INPUT, "marketCountry=" + value));
    }

    public CandleQuery parse(String interval, String range) {
        CandleQueryInterval parsedInterval = parseInterval(interval);
        CandleRange parsedRange = parseRange(range);

        int count = switch (parsedInterval) {
            case ONE_MINUTE -> {
                if (parsedRange != CandleRange.ONE_DAY) throw invalidCombination(interval, range);
                yield MINUTE_CANDLE_COUNT;
            }
            case FIVE_MINUTES -> switch (parsedRange) {
                case ONE_DAY -> FIVE_MINUTE_ONE_DAY_COUNT;
                case ONE_WEEK -> FIVE_MINUTE_ONE_WEEK_COUNT;
                default -> throw invalidCombination(interval, range);
            };
            case TEN_MINUTES -> {
                if (parsedRange != CandleRange.ONE_WEEK) throw invalidCombination(interval, range);
                yield TEN_MINUTE_ONE_WEEK_COUNT;
            }
            case ONE_DAY -> switch (parsedRange) {
                case ONE_MONTH -> ONE_MONTH_DAILY_COUNT;
                case SIX_MONTHS -> SIX_MONTH_DAILY_COUNT;
                case ONE_YEAR -> ONE_YEAR_DAILY_COUNT;
                default -> throw invalidCombination(interval, range);
            };
            case ONE_WEEK -> switch (parsedRange) {
                case SIX_MONTHS -> SIX_MONTH_WEEKLY_COUNT;
                case ONE_YEAR -> ONE_YEAR_WEEKLY_COUNT;
                default -> throw invalidCombination(interval, range);
            };
        };
        return new CandleQuery(parsedInterval, parsedRange, count);
    }

    private CandleQueryInterval parseInterval(String value) {
        if (value == null) throw invalidCombination(null, null);
        return switch (DomainNormalizer.lowerCode(value)) {
            case "1m" -> CandleQueryInterval.ONE_MINUTE;
            case "5m" -> CandleQueryInterval.FIVE_MINUTES;
            case "10m" -> CandleQueryInterval.TEN_MINUTES;
            case "1d" -> CandleQueryInterval.ONE_DAY;
            case "1w" -> CandleQueryInterval.ONE_WEEK;
            default -> throw invalidCombination(value, null);
        };
    }

    private CandleRange parseRange(String value) {
        if (value == null) throw invalidCombination(null, null);
        return switch (DomainNormalizer.upperCode(value)) {
            case "1D" -> CandleRange.ONE_DAY;
            case "1W" -> CandleRange.ONE_WEEK;
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
