package com.baedang.account.service;

import com.baedang.account.support.HoldingValuation;
import com.baedang.market.entity.QuoteSnapshot;
import com.baedang.trading.entity.Holding;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class HoldingValuatorTest {

    private static final OffsetDateTime ACQUIRED_AT =
            OffsetDateTime.of(2026, 8, 26, 3, 0, 0, 0, ZoneOffset.UTC);

    private static final OffsetDateTime QUOTE_AT =
            OffsetDateTime.of(2026, 8, 26, 3, 0, 0, 0, ZoneOffset.UTC);

    private final HoldingValuator valuator = new HoldingValuator();

    @Test
    void 국내주식은_수량과_현재가를_그대로_원단위로_평가한다() {
        Holding holding = Holding.firstBuy(1L, 101L,
                new BigDecimal("6"), new BigDecimal("228000"), BigDecimal.ONE, ACQUIRED_AT);
        QuoteSnapshot quote = quote(101L, "241500", "KRW");

        HoldingValuation result = valuateOne(holding, quote, null);

        assertThat(result.costWon()).isEqualByComparingTo("1368000");
        assertThat(result.evalWon()).isEqualByComparingTo("1449000");
        assertThat(result.pnlWon()).isEqualByComparingTo("81000");
        assertThat(result.currency()).isEqualTo("KRW");
    }

    @Test
    void 미국주식은_센트로_먼저_반올림한_뒤_환율을_곱해_원화로_평가한다() {
        Holding holding = Holding.firstBuy(1L, 202L,
                new BigDecimal("10"), new BigDecimal("88.34"), new BigDecimal("1383.60"), ACQUIRED_AT);
        QuoteSnapshot quote = quote(202L, "90.00", "USD");

        HoldingValuation result = valuateOne(holding, quote, new BigDecimal("1400"));

        // 매입: 10 x 88.34 = 883.40(센트) x 1383.60 = 1,222,272
        assertThat(result.costWon()).isEqualByComparingTo("1222272");
        // 평가: 10 x 90.00 = 900.00(센트) x 1400 = 1,260,000
        assertThat(result.evalWon()).isEqualByComparingTo("1260000");
        assertThat(result.pnlWon()).isEqualByComparingTo("37728");
        assertThat(result.currency()).isEqualTo("USD");
    }

    @Test
    void 미국주식인데_최신환율이_없으면_매입환율로_폴백한다() {
        Holding holding = Holding.firstBuy(1L, 202L,
                new BigDecimal("10"), new BigDecimal("88.34"), new BigDecimal("1383.60"), ACQUIRED_AT);
        QuoteSnapshot quote = quote(202L, "90.00", "USD");

        HoldingValuation result = valuateOne(holding, quote, null);

        // 평가 환율이 없어 매입 환율(1383.60)로 환산: 900.00 x 1383.60 = 1,245,240
        assertThat(result.evalWon()).isEqualByComparingTo("1245240");
        assertThat(result.costWon()).isEqualByComparingTo("1222272");
    }

    @Test
    void 미국주식은_수량곱가격을_센트로_반올림한_뒤_환율을_적용한다() {
        Holding holding = Holding.firstBuy(1L, 202L,
                new BigDecimal("3"), new BigDecimal("80.00"), new BigDecimal("1300"), ACQUIRED_AT);
        QuoteSnapshot quote = quote(202L, "90.005", "USD");

        HoldingValuation result = valuateOne(holding, quote, new BigDecimal("1400"));

        // 3주 x 90.005 = 270.015 → 센트 반올림 270.02 → x1400 = 378,028
        // (센트 반올림이 없으면 270.015 x 1400 = 378,021 로 달라짐)
        assertThat(result.evalWon()).isEqualByComparingTo("378028");
    }

    @Test
    void 시세가_없으면_평가액을_원가로_두고_손익을_0으로_만든다() {
        Holding holding = Holding.firstBuy(1L, 202L,
                new BigDecimal("10"), new BigDecimal("88.34"), new BigDecimal("1383.60"), ACQUIRED_AT);

        List<HoldingValuation> results =
                valuator.valuate(List.of(holding), Map.of(), new BigDecimal("1400"));
        HoldingValuation result = results.get(0);

        assertThat(result.evalWon()).isEqualByComparingTo(result.costWon());
        assertThat(result.pnlWon()).isEqualByComparingTo("0");
        assertThat(result.lastPrice()).isNull();
    }

    private HoldingValuation valuateOne(Holding holding, QuoteSnapshot quote, BigDecimal usdKrwRate) {
        return valuator.valuate(List.of(holding), Map.of(quote.getStockId(), quote), usdKrwRate).get(0);
    }

    private QuoteSnapshot quote(Long stockId, String lastPrice, String currency) {
        return new QuoteSnapshot(stockId, new BigDecimal(lastPrice), currency, QUOTE_AT, QUOTE_AT);
    }
}
