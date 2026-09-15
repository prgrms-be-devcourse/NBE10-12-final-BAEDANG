package com.baedang.stock.client.toss;

import com.baedang.global.clients.toss.TossSecuritiesClient;
import com.baedang.global.error.BusinessException;
import com.baedang.global.error.ErrorCode;
import com.baedang.stock.client.toss.dto.TossRankingResponse;
import com.baedang.stock.entity.MarketCountry;
import com.baedang.stock.port.RankingEntry;
import com.baedang.stock.port.RankingSnapshot;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class TossRankingAdapterTest {

    private static final OffsetDateTime RANKED_AT = OffsetDateTime.parse("2026-06-10T14:30:00+09:00");

    private final TossSecuritiesClient tossSecuritiesClient = mock(TossSecuritiesClient.class);
    private final TossRankingAdapter tossRankingAdapter = new TossRankingAdapter(tossSecuritiesClient);

    // ── 쿼리 파라미터 ────────────────────────────────────────────────────────

    @Test
    void KR_랭킹은_거래대금_1주_100개_유의종목_제외로_조회한다() {
        stub("KR", response(RANKED_AT, samsung()));

        tossRankingAdapter.fetchRanking(MarketCountry.KR);

        // 스텁이 정확히 이 파라미터로 걸려 있으므로, 호출이 성공한 것 자체가 검증이다.
        assertThat(queryParams("KR")).containsEntry("type", "MARKET_TRADING_AMOUNT")
                .containsEntry("duration", "1w")
                .containsEntry("count", "100")
                .containsEntry("excludeInvestmentCaution", "true");
    }

    @Test
    void US_랭킹은_marketCountry만_US로_바뀐다() {
        stub("US", response(RANKED_AT, nvidia()));

        RankingSnapshot snapshot = tossRankingAdapter.fetchRanking(MarketCountry.US);

        assertThat(snapshot.entries()).extracting(RankingEntry::symbol).containsExactly("NVDA");
    }

    // ── 정상 매핑 ───────────────────────────────────────────────────────────

    @Test
    void 랭킹_항목을_RankingEntry로_변환하고_price_중첩을_편다() {
        stub("KR", response(RANKED_AT, samsung()));

        RankingSnapshot snapshot = tossRankingAdapter.fetchRanking(MarketCountry.KR);

        assertThat(snapshot.rankedAt()).isEqualTo(RANKED_AT);
        assertThat(snapshot.entries()).hasSize(1);

        RankingEntry entry = snapshot.entries().get(0);
        assertThat(entry.rank()).isEqualTo(1);
        assertThat(entry.symbol()).isEqualTo("005930");
        assertThat(entry.currency()).isEqualTo("KRW");
        assertThat(entry.lastPrice()).isEqualByComparingTo(new BigDecimal("56500"));
        assertThat(entry.basePrice()).isEqualByComparingTo(new BigDecimal("55800"));
        assertThat(entry.changeRate()).isEqualByComparingTo(new BigDecimal("0.0125"));
        assertThat(entry.tradingVolume()).isEqualByComparingTo(new BigDecimal("18432100"));
        assertThat(entry.tradingAmount()).isEqualByComparingTo(new BigDecimal("1041436650000"));
    }

    @Test
    void 랭킹_100건을_순서_그대로_변환한다() {
        List<TossRankingResponse.Ranking> rankings = IntStream.rangeClosed(1, 100)
                .mapToObj(rank -> ranking(rank, "%06d".formatted(rank)))
                .toList();
        stub("KR", response(RANKED_AT, rankings.toArray(TossRankingResponse.Ranking[]::new)));

        RankingSnapshot snapshot = tossRankingAdapter.fetchRanking(MarketCountry.KR);

        assertThat(snapshot.entries()).hasSize(100);
        assertThat(snapshot.entries()).extracting(RankingEntry::rank)
                .containsExactlyElementsOf(IntStream.rangeClosed(1, 100).boxed().toList());
        assertThat(snapshot.entries().get(99).symbol()).isEqualTo("000100");
    }

    @Test
    void basePrice가_0이면_changeRate는_null로_담는다() {
        TossRankingResponse.Ranking ranking = new TossRankingResponse.Ranking(
                1,
                "900110",
                "KRW",
                new TossRankingResponse.Price("1200", "0", null),
                "1000",
                "1200000"
        );
        stub("KR", response(RANKED_AT, ranking));

        RankingSnapshot snapshot = tossRankingAdapter.fetchRanking(MarketCountry.KR);

        RankingEntry entry = snapshot.entries().get(0);
        assertThat(entry.basePrice()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(entry.changeRate()).isNull();
        assertThat(entry.lastPrice()).isEqualByComparingTo(new BigDecimal("1200"));
    }

    @Test
    void US_소수점_금액을_그대로_변환한다() {
        stub("US", response(RANKED_AT, nvidia()));

        RankingEntry entry = tossRankingAdapter.fetchRanking(MarketCountry.US).entries().get(0);

        assertThat(entry.lastPrice()).isEqualByComparingTo(new BigDecimal("131.38"));
        assertThat(entry.basePrice()).isEqualByComparingTo(new BigDecimal("128.45"));
        assertThat(entry.changeRate()).isEqualByComparingTo(new BigDecimal("0.0228"));
    }

    // ── 집계 없음 ───────────────────────────────────────────────────────────

    @Test
    void 집계가_없으면_에러가_아니라_빈_스냅샷을_반환한다() {
        stub("KR", new TossRankingResponse(new TossRankingResponse.Result(List.of(), null)));

        RankingSnapshot snapshot = tossRankingAdapter.fetchRanking(MarketCountry.KR);

        assertThat(snapshot.entries()).isEmpty();
        assertThat(snapshot.rankedAt()).isNull();
    }

    // ── 짝이 맞지 않는 응답 ─────────────────────────────────────────────────

    @Test
    void 항목은_있는데_rankedAt이_없으면_예외를_던진다() {
        stub("KR", response(null, samsung()));

        assertThatThrownBy(() -> tossRankingAdapter.fetchRanking(MarketCountry.KR))
                .isInstanceOf(BusinessException.class)
                .extracting(thrown -> ((BusinessException) thrown).getErrorCode())
                .isEqualTo(ErrorCode.TOSS_API_ERROR);
    }

    @Test
    void 항목이_없는데_rankedAt이_있으면_예외를_던진다() {
        stub("KR", new TossRankingResponse(new TossRankingResponse.Result(List.of(), RANKED_AT)));

        assertThatThrownBy(() -> tossRankingAdapter.fetchRanking(MarketCountry.KR))
                .isInstanceOf(BusinessException.class)
                .extracting(thrown -> ((BusinessException) thrown).getErrorCode())
                .isEqualTo(ErrorCode.TOSS_API_ERROR);
    }

    // ── 매핑 실패 ───────────────────────────────────────────────────────────

    @Test
    void 응답_자체가_null이면_예외를_던진다() {
        stub("KR", null);

        assertThatThrownBy(() -> tossRankingAdapter.fetchRanking(MarketCountry.KR))
                .isInstanceOf(BusinessException.class)
                .extracting(thrown -> ((BusinessException) thrown).getErrorCode())
                .isEqualTo(ErrorCode.TOSS_API_ERROR);
    }

    @Test
    void result가_null이면_예외를_던진다() {
        stub("KR", new TossRankingResponse(null));

        assertThatThrownBy(() -> tossRankingAdapter.fetchRanking(MarketCountry.KR))
                .isInstanceOf(BusinessException.class)
                .extracting(thrown -> ((BusinessException) thrown).getErrorCode())
                .isEqualTo(ErrorCode.TOSS_API_ERROR);
    }

    @Test
    void rankings가_null이면_예외를_던진다() {
        stub("KR", new TossRankingResponse(new TossRankingResponse.Result(null, RANKED_AT)));

        assertThatThrownBy(() -> tossRankingAdapter.fetchRanking(MarketCountry.KR))
                .isInstanceOf(BusinessException.class)
                .extracting(thrown -> ((BusinessException) thrown).getErrorCode())
                .isEqualTo(ErrorCode.TOSS_API_ERROR);
    }

    @ParameterizedTest
    @MethodSource("rankingsWithNullField")
    void 필수_필드가_null인_항목은_예외를_던진다(String field, TossRankingResponse.Ranking ranking) {
        List<TossRankingResponse.Ranking> rankings = new ArrayList<>();
        rankings.add(ranking);
        stub("KR", new TossRankingResponse(new TossRankingResponse.Result(rankings, RANKED_AT)));

        assertThatThrownBy(() -> tossRankingAdapter.fetchRanking(MarketCountry.KR))
                .isInstanceOf(BusinessException.class)
                .extracting(thrown -> ((BusinessException) thrown).getErrorCode())
                .isEqualTo(ErrorCode.TOSS_API_ERROR);
    }

    static Stream<Arguments> rankingsWithNullField() {
        return Stream.of(
                Arguments.of("ranking", null),
                Arguments.of("symbol", new TossRankingResponse.Ranking(
                        1, null, "KRW", price(), "18432100", "1041436650000")),
                Arguments.of("currency", new TossRankingResponse.Ranking(
                        1, "005930", null, price(), "18432100", "1041436650000")),
                Arguments.of("price", new TossRankingResponse.Ranking(
                        1, "005930", "KRW", null, "18432100", "1041436650000")),
                Arguments.of("tradingVolume", new TossRankingResponse.Ranking(
                        1, "005930", "KRW", price(), null, "1041436650000")),
                Arguments.of("tradingAmount", new TossRankingResponse.Ranking(
                        1, "005930", "KRW", price(), "18432100", null)),
                Arguments.of("price.lastPrice", new TossRankingResponse.Ranking(
                        1, "005930", "KRW", new TossRankingResponse.Price(null, "55800", "0.0125"),
                        "18432100", "1041436650000")),
                Arguments.of("price.basePrice", new TossRankingResponse.Ranking(
                        1, "005930", "KRW", new TossRankingResponse.Price("56500", null, "0.0125"),
                        "18432100", "1041436650000"))
        );
    }

    @Test
    void rank가_0이면_예외를_던진다() {
        stub("KR", response(RANKED_AT, ranking(0, "005930")));

        assertThatThrownBy(() -> tossRankingAdapter.fetchRanking(MarketCountry.KR))
                .isInstanceOf(BusinessException.class)
                .extracting(thrown -> ((BusinessException) thrown).getErrorCode())
                .isEqualTo(ErrorCode.TOSS_API_ERROR);
    }

    @Test
    void 금액_문자열이_숫자가_아니면_BusinessException을_던진다() {
        TossRankingResponse.Ranking ranking = new TossRankingResponse.Ranking(
                1,
                "005930",
                "KRW",
                price(),
                "18432100",
                "1,041,436,650,000"
        );
        stub("KR", response(RANKED_AT, ranking));

        assertThatThrownBy(() -> tossRankingAdapter.fetchRanking(MarketCountry.KR))
                .isInstanceOf(BusinessException.class)
                .isNotInstanceOf(NumberFormatException.class)
                .extracting(thrown -> ((BusinessException) thrown).getErrorCode())
                .isEqualTo(ErrorCode.TOSS_API_ERROR);
    }

    // ── 인자 검증 ───────────────────────────────────────────────────────────

    @Test
    void market이_null이면_토스를_호출하지_않고_예외를_던진다() {
        assertThatThrownBy(() -> tossRankingAdapter.fetchRanking(null))
                .isInstanceOf(BusinessException.class)
                .extracting(thrown -> ((BusinessException) thrown).getErrorCode())
                .isEqualTo(ErrorCode.INTERNAL_ERROR);

        verifyNoInteractions(tossSecuritiesClient);
    }

    // ── 픽스처 ──────────────────────────────────────────────────────────────

    private void stub(String marketCountry, TossRankingResponse response) {
        when(tossSecuritiesClient.get(
                eq("/api/v1/rankings"),
                eq(queryParams(marketCountry)),
                eq(TossRankingResponse.class)
        )).thenReturn(response);
    }

    private static Map<String, String> queryParams(String marketCountry) {
        return Map.of(
                "type", "MARKET_TRADING_AMOUNT",
                "marketCountry", marketCountry,
                "duration", "1w",
                "count", "100",
                "excludeInvestmentCaution", "true"
        );
    }

    private static TossRankingResponse response(
            OffsetDateTime rankedAt,
            TossRankingResponse.Ranking... rankings
    ) {
        return new TossRankingResponse(
                new TossRankingResponse.Result(List.of(rankings), rankedAt)
        );
    }

    private static TossRankingResponse.Price price() {
        return new TossRankingResponse.Price("56500", "55800", "0.0125");
    }

    private static TossRankingResponse.Ranking ranking(int rank, String symbol) {
        return new TossRankingResponse.Ranking(
                rank, symbol, "KRW", price(), "18432100", "1041436650000");
    }

    private static TossRankingResponse.Ranking samsung() {
        return ranking(1, "005930");
    }

    private static TossRankingResponse.Ranking nvidia() {
        return new TossRankingResponse.Ranking(
                1,
                "NVDA",
                "USD",
                new TossRankingResponse.Price("131.38", "128.45", "0.0228"),
                "342100",
                "44942580"
        );
    }
}
