package com.baedang.trading.service;

import com.baedang.global.error.BusinessException;
import com.baedang.global.error.ErrorCode;
import com.baedang.market.entity.QuoteSnapshot;
import com.baedang.stock.entity.MarketCountry;
import com.baedang.stock.entity.Stock;
import com.baedang.trading.model.OrderMarketContext;
import com.baedang.trading.model.ExecutionRateEvidence;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderPolicyTest {

    @Test
    void accountId가_없으면_같은_요청을_재전송할_수_없다() {
        assertThatThrownBy(() -> policy.parseInput(
                null, UUID.randomUUID().toString(), "005930", "KR", "BUY", "1"))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT);
                    assertThat(exception.getData())
                            .containsEntry("field", "accountId")
                            .containsEntry("retryPolicy", "NOT_RETRYABLE");
                });
    }

    private static final Instant CHECKED_AT = Instant.parse("2026-08-26T06:29:59Z");

    private static final OffsetDateTime QUOTE_AT =
            OffsetDateTime.ofInstant(CHECKED_AT, ZoneOffset.UTC);

    private final OrderPolicy policy =
            new OrderPolicy(15, 15, new BigDecimal("1000000"));

    @ParameterizedTest
    @CsvSource({"0,-1,2,1,true", "0,-1,2,2,false", "-59,-100,3600,0,true",
            "-59,-100,3600,1,true", "1,-100,3600,0,false", "0,1,3600,0,false"})
    void 컨텍스트가_신선해도_원본환율의_유효구간과_미래수신을_검증한다(
            long fetchedOffset, long fromOffset, long untilOffset, long nowOffset, boolean valid) {
        var evidence = new ExecutionRateEvidence(new BigDecimal("1383.601234"),
                QUOTE_AT.plusSeconds(fetchedOffset), QUOTE_AT.plusSeconds(fromOffset), QUOTE_AT.plusSeconds(untilOffset));
        var context = new OrderMarketContext(MarketCountry.US, true, Instant.MAX, evidence, CHECKED_AT);
        if (valid) {
            policy.validateExecutionContextFresh(context, CHECKED_AT.plusSeconds(nowOffset));
        } else {
            assertThatThrownBy(() -> policy.validateExecutionContextFresh(context, CHECKED_AT.plusSeconds(nowOffset)))
                    .isInstanceOfSatisfying(BusinessException.class, exception -> {
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.EXCHANGE_RATE_NOT_FOUND);
                        assertThat(exception.getData()).containsEntry("retryPolicy", "SAME_CLIENT_ORDER_ID");
                    });
        }
    }

    @Test
    void 시장가도_환율근거가_누락되면_체결하지_않는다() {
        var context = new OrderMarketContext(MarketCountry.US, true, Instant.MAX,
                null, CHECKED_AT);
        assertThatThrownBy(() -> policy.validateExecutionContextFresh(context, CHECKED_AT))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.EXCHANGE_RATE_NOT_FOUND));
    }

    @ParameterizedTest
    @CsvSource({"KR, KRW", "US, USD"})
    void 시장에_맞는_종목과_시세_통화면_유효하다(MarketCountry marketCountry, String currency) {
        Stock stock = Stock.create("TEST", marketCountry, "TEST", "테스트", null, currency, "STOCK", true);
        QuoteSnapshot quote = new QuoteSnapshot(1L, BigDecimal.ONE, currency, QUOTE_AT, QUOTE_AT);

        assertThat(policy.hasValidCurrencyForMarket(stock, quote)).isTrue();
    }

    @ParameterizedTest
    @CsvSource({"KR, USD", "US, KRW"})
    void 종목과_시세_통화가_같아도_시장과_다르면_유효하지_않다(
            MarketCountry marketCountry,
            String currency
    ) {
        Stock stock = Stock.create("TEST", marketCountry, "TEST", "테스트", null, currency, "STOCK", true);
        QuoteSnapshot quote = new QuoteSnapshot(1L, BigDecimal.ONE, currency, QUOTE_AT, QUOTE_AT);

        assertThat(policy.hasValidCurrencyForMarket(stock, quote)).isFalse();
    }

    @Test
    void 세션_종료_시각부터는_조회당시_운영중이어도_체결할_수_없다() {
        OrderMarketContext context = new OrderMarketContext(
                MarketCountry.KR,
                true,
                Instant.parse("2026-08-26T06:30:00Z"),
                ExecutionRateEvidence.krw(CHECKED_AT.atOffset(ZoneOffset.UTC)),
                CHECKED_AT);

        assertThat(context.isMarketOpenAt(Instant.parse("2026-08-26T06:29:59.999Z"))).isTrue();
        assertThat(context.isMarketOpenAt(Instant.parse("2026-08-26T06:30:00Z"))).isFalse();
    }

    @Test
    void 시장정보_만료는_오류코드와_같은_ID_재시도_정책을_제공한다() {
        OrderMarketContext context = new OrderMarketContext(
                MarketCountry.KR, true, Instant.MAX, ExecutionRateEvidence.krw(QUOTE_AT), CHECKED_AT);

        assertThatThrownBy(() -> policy.validateExecutionContextFresh(
                context, CHECKED_AT.plusSeconds(16)))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.MARKET_CONTEXT_EXPIRED);
                    assertThat(exception.getData()).containsEntry("retryPolicy", "SAME_CLIENT_ORDER_ID");
                });
    }

    @Test
    void 주문의_누락필드는_필드명과_같은_ID_재시도_정책을_제공한다() {
        assertThatThrownBy(() -> policy.parseInput(
                1L, UUID.randomUUID().toString(), "005930", "KR", null, "1"))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getData()).containsEntry("field", "side");
                    assertThat(exception.getData())
                            .containsEntry("retryPolicy", "SAME_CLIENT_ORDER_ID");
                });
    }

    @Test
    void 잘못된_clientOrderId는_재사용할_수_없다() {
        assertThatThrownBy(() -> policy.parseInput(
                1L, "invalid", "005930", "KR", "BUY", "1"))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getData())
                                .containsEntry("retryPolicy", "NOT_RETRYABLE")
                                .containsEntry("field", "clientOrderId"));
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.NullAndEmptySource
    @org.junit.jupiter.params.provider.ValueSource(strings = {" ", "0", "1000001", "1.5", "abc", "111111111111111111111111111111111"})
    void 잘못된_수량은_문제필드와_재시도정책을_제공한다(String quantity) {
        assertThatThrownBy(() -> policy.parseInput(1L, UUID.randomUUID().toString(), "005930", "KR", "BUY", quantity))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_QUANTITY);
                    assertThat(e.getData()).containsEntry("field", "quantity")
                            .containsEntry("retryPolicy", "SAME_CLIENT_ORDER_ID");
                });
        assertThatThrownBy(() -> policy.parseTerms("005930", "KR", "BUY", quantity))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getData()).containsEntry("field", "quantity"));
    }

    @ParameterizedTest
    @CsvSource({"KR,UNKNOWN,side", "INVALID,BUY,marketCountry"})
    void 잘못된_시장과_방향은_견적과_주문에서_문제필드를_제공한다(String country, String side, String field) {
        assertThatThrownBy(() -> policy.parseInput(1L, UUID.randomUUID().toString(), "005930", country, side, "1"))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT);
                    assertThat(e.getData()).containsEntry("field", field)
                            .containsEntry("retryPolicy", "SAME_CLIENT_ORDER_ID");
                });
        assertThatThrownBy(() -> policy.parseTerms("005930", country, side, "1"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getData()).containsEntry("field", field));
    }

    @Test
    void 잘못된_종목코드_방향_시장은_INVALID_INPUT_예외를_던진다() {
        assertThatThrownBy(() -> policy.parseInput(1L, UUID.randomUUID().toString(), null, "KR", "BUY", "1"))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getData()).containsEntry("field", "symbol"));
        assertThatThrownBy(() -> policy.parseInput(1L, null, "005930", "KR", "BUY", "1"))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getData()).containsEntry("field", "clientOrderId"));
        assertThatThrownBy(() -> policy.parseInput(1L, UUID.randomUUID().toString(), "005930", null, "BUY", "1"))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getData()).containsEntry("field", "marketCountry"));
        assertThatThrownBy(() -> policy.parseInput(1L, UUID.randomUUID().toString(), "005930", "INVALID", "BUY", "1"))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT));
        assertThatThrownBy(() -> policy.parseInput(1L, UUID.randomUUID().toString(), "005930", "KR", "UNKNOWN", "1"))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT));
    }

    @Test
    void 컨텍스트가_null이거나_시각이_역전되면_MARKET_CONTEXT_EXPIRED를_던진다() {
        assertThatThrownBy(() -> policy.validateExecutionContextFresh(null, CHECKED_AT))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.MARKET_CONTEXT_EXPIRED));
        var context = new OrderMarketContext(MarketCountry.KR, true, Instant.MAX, ExecutionRateEvidence.krw(QUOTE_AT), CHECKED_AT);
        assertThatThrownBy(() -> policy.validateExecutionContextFresh(context, CHECKED_AT.minusSeconds(1)))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.MARKET_CONTEXT_EXPIRED));
        assertThatThrownBy(() -> policy.validateExecutionContextFresh(context, null))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.MARKET_CONTEXT_EXPIRED));
    }
}
