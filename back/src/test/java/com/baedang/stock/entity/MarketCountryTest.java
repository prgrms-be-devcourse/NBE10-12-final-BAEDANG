package com.baedang.stock.entity;

import com.baedang.global.error.BusinessException;
import com.baedang.global.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MarketCountryTest {

    @ParameterizedTest
    @CsvSource({"' kr ', KR", "' uS ', US"})
    void 국가코드를_정규화해_파싱한다(String raw, MarketCountry expected) {
        assertThat(MarketCountry.parse(raw)).contains(expected);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", " ", "JP", "NASDAQ", "KOSPI", "K R"})
    void 미지원_국가코드의_오류_결정은_호출부에_맡긴다(String raw) {
        assertThat(MarketCountry.parse(raw)).isEmpty();
    }

    @Test
    void 거래소명_매핑은_국가코드_파싱과_별개다() {
        assertThat(MarketCountry.fromMarket("KOSPI")).isEqualTo(MarketCountry.KR);
        assertThat(MarketCountry.fromMarket("NASDAQ")).isEqualTo(MarketCountry.US);
        assertThatThrownBy(() -> MarketCountry.fromMarket("UNKNOWN"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.TOSS_API_ERROR));
    }

    @Test
    void 시장별_기본통화와_시간대를_제공한다() {
        assertThat(MarketCountry.KR.defaultCurrency()).isEqualTo("KRW");
        assertThat(MarketCountry.US.defaultCurrency()).isEqualTo("USD");
        assertThat(MarketCountry.KR.zoneId()).isEqualTo(ZoneId.of("Asia/Seoul"));
        assertThat(MarketCountry.US.zoneId()).isEqualTo(ZoneId.of("America/New_York"));
    }

    @ParameterizedTest
    @CsvSource({"2026-01-15T00:00:00Z,-5", "2026-07-15T00:00:00Z,-4"})
    void 미국_시간대는_서머타임을_따른다(String instant, int offsetHours) {
        assertThat(Instant.parse(instant).atZone(MarketCountry.US.zoneId()).getOffset())
                .isEqualTo(ZoneOffset.ofHours(offsetHours));
    }

    @Test
    void 한국_자정_이후에도_미국_거래일은_이전_날일_수_있다() {
        Instant now = Instant.parse("2026-09-01T16:00:00Z");
        assertThat(now.atZone(MarketCountry.KR.zoneId()).toLocalDate()).isEqualTo(LocalDate.of(2026, 9, 2));
        assertThat(now.atZone(MarketCountry.US.zoneId()).toLocalDate()).isEqualTo(LocalDate.of(2026, 9, 1));
    }
}
