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
            "5m,1D,FIVE_MINUTES,ONE_DAY,78",
            "5m,1W,FIVE_MINUTES,ONE_WEEK,390",
            "10m,1W,TEN_MINUTES,ONE_WEEK,195",
            "1d,1M,ONE_DAY,ONE_MONTH,22",
            "1d,6M,ONE_DAY,SIX_MONTHS,130",
            "1d,1Y,ONE_DAY,ONE_YEAR,250",
            "1w,6M,ONE_WEEK,SIX_MONTHS,26",
            "1w,1Y,ONE_WEEK,ONE_YEAR,52"
    })
    void 허용_조합의_조회개수를_결정한다(
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

    // 앞 5개는 interval 갈래마다 하나씩(허용 조합 밖), 뒤 3개는 없는 값.
    @ParameterizedTest
    @CsvSource({
            "1m,1M", "5m,1M", "10m,1D", "1d,1D", "1w,1D",
            "3m,1D", "1d,3Y", "day,1M"
    })
    void 그_외_조합과_없는_값은_INVALID_INTERVAL_RANGE로_거절한다(String interval, String range) {
        assertRejected(interval, range);
    }

    @Test
    void marketCountry는_대소문자를_정규화한다() {
        assertThat(policy.parseMarketCountry("kr").name()).isEqualTo("KR");
    }

    private void assertRejected(String interval, String range) {
        assertThatThrownBy(() -> policy.parse(interval, range))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.INVALID_INTERVAL_RANGE));
    }
}
