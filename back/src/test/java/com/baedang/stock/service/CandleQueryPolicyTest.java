package com.baedang.stock.service;

import com.baedang.global.error.BusinessException;
import com.baedang.global.error.ErrorCode;
import com.baedang.stock.model.CandleQueryInterval;
import com.baedang.stock.model.CandleRange;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CandleQueryPolicyTest {

    private final CandleQueryPolicy policy = new CandleQueryPolicy();

    @ParameterizedTest
    @CsvSource({
            "1m,1D,ONE_MINUTE,ONE_DAY,200",
            "1d,1M,ONE_DAY,ONE_MONTH,22",
            "1d,6M,ONE_DAY,SIX_MONTHS,130",
            "1d,1Y,ONE_DAY,ONE_YEAR,250"
    })
    void MVP_조합별_조회개수를_결정한다(
            String interval,
            String range,
            CandleQueryInterval expectedInterval,
            CandleRange expectedRange,
            int expectedCount
    ) {
        var query = policy.parse(interval, range);

        assertThat(query.interval()).isEqualTo(expectedInterval);
        assertThat(query.range()).isEqualTo(expectedRange);
        assertThat(query.count()).isEqualTo(expectedCount);
    }

    @ParameterizedTest
    @CsvSource({"1m,1M", "1d,1D", "5m,1D", "1w,3Y"})
    void MVP_범위밖_조합은_거절한다(String interval, String range) {
        assertThatThrownBy(() -> policy.parse(interval, range))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.INVALID_INTERVAL_RANGE));
    }

    @Test
    void marketCountry는_대소문자를_정규화한다() {
        assertThat(policy.parseMarketCountry("kr").name()).isEqualTo("KR");
    }
}
