package com.baedang.trading.service;

import com.baedang.global.error.BusinessException;
import com.baedang.global.error.ErrorCode;
import com.baedang.stock.entity.MarketCountry;
import com.baedang.trading.entity.OrderSide;
import com.baedang.trading.model.CumulativeSettlementState;
import com.baedang.trading.model.LimitOrderSettlementResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.*;

class LimitOrderSettlementCalculatorTest {
    private final LimitOrderSettlementCalculator calculator = calculator("0.0001");

    private LimitOrderSettlementCalculator calculator(String fee) {
        return new LimitOrderSettlementCalculator(d(fee), d("0.002"), d("0.0000206"), d("0.01"), d("1000000"));
    }

    @ParameterizedTest
    @CsvSource({"KR,BUY,12345,1", "KR,SELL,12345,1", "US,BUY,88.33,1383.601234", "US,SELL,88.33,1383.601234"})
    void 동일_가격_환율은_분할_횟수와_무관하게_누적_정산액이_같다(MarketCountry country, OrderSide side,
                                                       BigDecimal price, BigDecimal rate) {
        var whole = calculator.calculate(country, side, price, d("100"), rate, CumulativeSettlementState.empty());
        var state = CumulativeSettlementState.empty();
        BigDecimal net = BigDecimal.ZERO;
        for (int i = 0; i < 100; i++) {
            var part = calculator.calculate(country, side, price, BigDecimal.ONE, rate, state);
            assertThat(part.isExecutable()).isTrue();
            state = part.nextState();
            net = net.add(part.netAmountKrw());
        }
        assertThat(state).usingRecursiveComparison().withComparatorForType(BigDecimal::compareTo, BigDecimal.class)
                .isEqualTo(whole.nextState());
        assertThat(net).isEqualByComparingTo(whole.netAmountKrw());
    }

    @Test
    void 이슈_기준_미국_매도_환율이_바뀌어도_최소_SEC는_다시_부과하지_않는다() {
        var first = calculate(OrderSide.SELL, "100", "1", "1300", CumulativeSettlementState.empty());
        var second = calculate(OrderSide.SELL, "100", "1", "1400", first.nextState());
        assertAmounts(first, "130000", "13", "13", "129974", "0.01");
        assertAmounts(second, "140000", "14", "0", "139986", "0");
        assertThat(second.nextState().unroundedTaxKrw()).isEqualByComparingTo("13");
    }

    @Test
    void SEC_증가분만_이번_환율로_변환하고_원화세금_누적_반올림차액을_부과한다() {
        var first = calculate(OrderSide.SELL, "100", "1", "1350", CumulativeSettlementState.empty());
        var second = calculate(OrderSide.SELL, "900", "1", "1450", first.nextState());
        assertThat(first.amounts().taxKrw()).isEqualByComparingTo("14");
        assertThat(second.amounts().secFeeUsd()).isEqualByComparingTo("0.01");
        assertThat(second.nextState().unroundedTaxKrw()).isEqualByComparingTo("28");
        assertThat(second.amounts().taxKrw()).isEqualByComparingTo("14");
    }

    @Test
    void 원화_반올림과_수수료를_체결마다_독립_계산하지_않는다() {
        var first = calculate(OrderSide.BUY, "1", "1", "4999.5", CumulativeSettlementState.empty());
        var second = calculate(OrderSide.BUY, "1", "1", "4999.5", first.nextState());
        assertAmounts(first, "5000", "1", "0", "5001", "0");
        assertAmounts(second, "4999", "0", "0", "4999", "0");
        assertThat(second.nextState().grossAmountKrw()).isEqualByComparingTo("9999");
    }

    @Test
    void 국내는_환율없이_계산하고_수수료와_매도세를_누적차액으로_계산한다() {
        var first = calculator.calculate(MarketCountry.KR, OrderSide.SELL, d("250"), d("1"), null, CumulativeSettlementState.empty());
        var next = calculator.calculate(MarketCountry.KR, OrderSide.SELL, d("250"), d("1"), d("9999"), first.nextState());
        assertThat(first.amounts().taxKrw()).isEqualByComparingTo("1");
        assertThat(next.amounts().taxKrw()).isZero();
        assertThat(next.amounts().grossAmountUsd()).isZero();
        assertThat(next.amounts().unroundedGrossAmountKrw()).isEqualByComparingTo("250");
    }

    @ParameterizedTest
    @CsvSource({"0.0001,0", "0.1,-1"})
    void 정산액이_0이하면_체결금액_없이_보류하고_누적상태를_유지한다(String fee, BigDecimal expectedNet) {
        var empty = CumulativeSettlementState.empty();
        var result = calculator(fee).calculate(MarketCountry.US, OrderSide.SELL, d("0.01"), d("1"), d("1300"), empty);
        assertThat(result.isExecutable()).isFalse();
        assertThat(result.netAmountKrw()).isEqualByComparingTo(expectedNet);
        assertThat(result.amounts()).isNull();
        assertThat(result.nextState()).isEqualTo(empty);
        assertThatThrownBy(result::requireExecutionAmounts).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_SETTLEMENT_AMOUNT));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"0", "-1", "0.1", "1000001"})
    void 정수가_아니거나_상한을_초과하는_수량은_거절한다(String quantity) {
        assertThatThrownBy(() -> calculator.calculate(MarketCountry.US, OrderSide.BUY, d("1"),
                quantity == null ? null : d(quantity), d("1300"), CumulativeSettlementState.empty()))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_QUANTITY));
    }

    @Test
    void 누적수량도_상한을_검증한다() {
        var first = calculate(OrderSide.BUY, "1", "1000000", "1300", CumulativeSettlementState.empty());
        assertThatThrownBy(() -> calculate(OrderSide.BUY, "1", "1", "1300", first.nextState()))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void 미국_단가를_몰래_반올림하지_않으며_후행영은_허용한다() {
        assertThatThrownBy(() -> calculate(OrderSide.BUY, "88.335", "1", "1300", CumulativeSettlementState.empty()))
                .isInstanceOf(BusinessException.class);
        var result = calculate(OrderSide.BUY, "88.3300", "1", "1383.601234", CumulativeSettlementState.empty());
        assertThat(result.amounts().unroundedGrossAmountKrw()).isEqualByComparingTo(d("88.33").multiply(d("1383.601234")));
    }

    @Test
    void 저장가능_금액_경계와_잘못된_누적근거를_검증한다() {
        var valid = calculator.calculate(MarketCountry.KR, OrderSide.BUY, d("99999999999999"), d("1"), null,
                CumulativeSettlementState.empty());
        assertThat(valid.isExecutable()).isTrue();
        assertThatThrownBy(() -> calculator.calculate(MarketCountry.KR, OrderSide.BUY, d("999999999999999"), d("1"), null,
                CumulativeSettlementState.empty())).isInstanceOf(BusinessException.class);
        var corrupt = new CumulativeSettlementState(d("1"), d("100"), d("130000"), d("0"), d("0"), d("130000"), d("0"), d("0"));
        assertThatThrownBy(() -> calculate(OrderSide.BUY, "100", "1", "1300", corrupt))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INTERNAL_ERROR));
    }

    @Test
    void 최초동결은_지정가_환산액과_수수료만_포함한다() {
        assertThat(calculator.initialReservedCash(MarketCountry.US, d("100"), d("2"), d("1300")))
                .isEqualByComparingTo("260026");
        assertThat(calculator.initialReservedCash(MarketCountry.KR, d("10000"), d("2"), null))
                .isEqualByComparingTo("20002");
    }

    @ParameterizedTest
    @CsvSource({"200,2,1,90,true,110,0", "200,1,1,90,true,0,110", "200,1,1,200,true,0,0",
            "200,2,1,200,false,200,0", "200,2,1,201,false,200,0", "200,2,1,0,false,200,0",
            "200,2,1,-1,false,200,0", "0,1,1,1,false,0,0"})
    void 실제결제액_차감_전량잔차해제_부족보류를_구분한다(BigDecimal reserved, BigDecimal remaining,
            BigDecimal quantity, BigDecimal net, boolean executable, BigDecimal after, BigDecimal released) {
        var result = calculator.reserveAfterBuy(reserved, remaining, quantity, net);
        assertThat(result.executable()).isEqualTo(executable);
        assertThat(result.reservedCashAfter()).isEqualByComparingTo(after);
        assertThat(result.releasedCash()).isEqualByComparingTo(released);
    }

    @Test
    void 환율상승시_전체후보는_불가능해도_작은_정수후보는_동결안에서_가능하다() {
        var reserved = calculator.initialReservedCash(MarketCountry.US, d("100"), d("2"), d("1300"));
        var all = calculate(OrderSide.BUY, "100", "2", "1400", CumulativeSettlementState.empty());
        var one = calculate(OrderSide.BUY, "100", "1", "1400", CumulativeSettlementState.empty());
        assertThat(calculator.reserveAfterBuy(reserved, d("2"), d("2"), all.netAmountKrw()).executable()).isFalse();
        assertThat(calculator.reserveAfterBuy(reserved, d("2"), d("1"), one.netAmountKrw()).reservedCashAfter())
                .isEqualByComparingTo("120012");
    }

    @ParameterizedTest
    @CsvSource({"KR,0,200,200,0,200", "US,200,276720.246800,276720,28,276748"})
    void 원가용_반올림전_거래대금과_수수료포함_결제액을_구분한다(MarketCountry country,
            BigDecimal grossUsd, BigDecimal rawKrw, BigDecimal grossKrw, BigDecimal fee, BigDecimal net) {
        var result = calculator.calculate(country, OrderSide.BUY, d("100"), d("2"), d("1383.601234"), CumulativeSettlementState.empty());
        var a = result.requireExecutionAmounts();
        assertThat(a.grossAmountUsd()).isEqualByComparingTo(grossUsd);
        assertThat(a.unroundedGrossAmountKrw()).isEqualByComparingTo(rawKrw);
        assertThat(a.grossAmountKrw()).isEqualByComparingTo(grossKrw);
        assertThat(a.feeKrw()).isEqualByComparingTo(fee);
        assertThat(a.netAmountKrw()).isEqualByComparingTo(net);
    }

    private LimitOrderSettlementResult calculate(OrderSide side, String price, String quantity, String rate, CumulativeSettlementState state) {
        return calculator.calculate(MarketCountry.US, side, d(price), d(quantity), d(rate), state);
    }

    private void assertAmounts(LimitOrderSettlementResult result, String gross, String fee, String tax, String net, String sec) {
        assertThat(result.amounts().grossAmountKrw()).isEqualByComparingTo(gross);
        assertThat(result.amounts().feeKrw()).isEqualByComparingTo(fee);
        assertThat(result.amounts().taxKrw()).isEqualByComparingTo(tax);
        assertThat(result.netAmountKrw()).isEqualByComparingTo(net);
        assertThat(result.amounts().secFeeUsd()).isEqualByComparingTo(sec);
    }

    @Test
    void 계산기_생성자의_요율과_상한_양수_불변식을_검증한다() {
        assertThatThrownBy(() -> new LimitOrderSettlementCalculator(null, d("0.002"), d("0.0000206"), d("0.01"), d("1000000")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LimitOrderSettlementCalculator(d("-0.0001"), d("0.002"), d("0.0000206"), d("0.01"), d("1000000")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LimitOrderSettlementCalculator(d("0.0001"), null, d("0.0000206"), d("0.01"), d("1000000")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LimitOrderSettlementCalculator(d("0.0001"), d("0.002"), d("-0.0000206"), d("0.01"), d("1000000")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LimitOrderSettlementCalculator(d("0.0001"), d("0.002"), d("0.0000206"), d("-0.01"), d("1000000")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LimitOrderSettlementCalculator(d("0.0001"), d("0.002"), d("0.0000206"), d("0.01"), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LimitOrderSettlementCalculator(d("0.0001"), d("0.002"), d("0.0000206"), d("0.01"), d("-1")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 동결계산_결과_모델_불변식을_검증한다() {
        assertThatThrownBy(() -> new com.baedang.trading.model.BuyReservationResult(true, null, BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new com.baedang.trading.model.BuyReservationResult(true, BigDecimal.ZERO, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new com.baedang.trading.model.BuyReservationResult(true, BigDecimal.ONE.negate(), BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new com.baedang.trading.model.BuyReservationResult(true, BigDecimal.ZERO, BigDecimal.ONE.negate()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new com.baedang.trading.model.BuyReservationResult(false, BigDecimal.TEN, BigDecimal.ONE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 누적정산상태_모델_불변식을_검증한다() {
        assertThatThrownBy(() -> new CumulativeSettlementState(null, d("0"), d("0"), d("0"), d("0"), d("0"), d("0"), d("0")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CumulativeSettlementState(d("-1"), d("0"), d("0"), d("0"), d("0"), d("0"), d("0"), d("0")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CumulativeSettlementState(d("0"), d("-1"), d("0"), d("0"), d("0"), d("0"), d("0"), d("0")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 지정가정산결과_모델_불변식을_검증한다() {
        var empty = CumulativeSettlementState.empty();
        var amounts = new com.baedang.trading.model.ExecutionAmounts(d("0"), d("100"), d("0"), d("100"), d("0"), d("0"), d("100"));
        assertThatThrownBy(() -> new LimitOrderSettlementResult(null, amounts, empty))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LimitOrderSettlementResult(d("100"), amounts, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LimitOrderSettlementResult(d("100"), null, empty))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LimitOrderSettlementResult(d("0"), amounts, empty))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LimitOrderSettlementResult(d("99"), amounts, empty))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static BigDecimal d(String value) { return new BigDecimal(value); }
}
