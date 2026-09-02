package com.baedang.global.normalizer;

import com.baedang.global.error.BusinessException;
import com.baedang.global.error.ErrorCode;
import com.baedang.market.repository.ExchangeRateRepository;
import com.baedang.market.service.ExchangeRateService;
import com.baedang.stock.entity.Stock;
import com.baedang.stock.repository.StockRepository;
import com.baedang.stock.service.CandleQueryPolicy;
import com.baedang.stock.service.RankingService;
import com.baedang.stock.service.StockDetailService;
import com.baedang.stock.service.StockSearchService;
import com.baedang.trading.service.MarketOrderPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 공통 정규화 도입 후에도 서비스별 검증·오류 응답 계약이 유지되는지 검증합니다. */
class DomainNormalizationContractTest {

    private final MarketOrderPolicy orderPolicy = new MarketOrderPolicy(15, 15, new BigDecimal("1000000"));
    private final CandleQueryPolicy candlePolicy = new CandleQueryPolicy();

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", " "})
    void 같은_누락값도_주문과_차트의_오류_정보가_다르다(String market) {
        assertThatThrownBy(() -> orderPolicy.parseCommand(
                1L, UUID.randomUUID().toString(), "005930", market, "BUY", "1"))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT);
                    assertThat(e.getDetail()).isNull();
                    assertThat(e.getData()).containsEntry("field", "marketCountry")
                            .containsEntry("retryPolicy", "SAME_CLIENT_ORDER_ID");
                });

        assertThatThrownBy(() -> candlePolicy.parseMarketCountry(market))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT);
                    assertThat(e.getDetail()).isEqualTo("marketCountry가 비어 있음");
                    assertThat(e.getData()).isNull();
                });
    }

    @Test
    void 미지원_시장코드는_원문과_주문_재시도_정책을_유지한다() {
        assertThatThrownBy(() -> orderPolicy.parseCommand(
                1L, UUID.randomUUID().toString(), "005930", " jp ", "BUY", "1"))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getDetail()).isEqualTo("marketCountry= jp ");
                    assertThat(e.getData()).containsEntry("retryPolicy", "SAME_CLIENT_ORDER_ID")
                            .doesNotContainKey("field");
                });
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", " ", "JP"})
    void 상세와_랭킹의_시장코드_오류문구는_각각_유지한다(String market) {
        StockDetailService detailService = new StockDetailService(null, null, null, null);
        RankingService rankingService = new RankingService(null, null, null);

        assertThatThrownBy(() -> detailService.getDetail("005930", market))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT);
                    assertThat(e.getDetail()).isEqualTo("marketCountry는 KR 또는 US여야 합니다");
                });
        assertThatThrownBy(() -> rankingService.getRankings(market, 20, null))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT);
                    assertThat(e.getDetail()).isEqualTo("market는 KR 또는 US여야 합니다");
                });
    }

    @Test
    void 차트_조회수와_수량의_정적검증은_정규화로_변하지_않는다() {
        assertThat(candlePolicy.parse(" 1D ", " 1y ").count()).isEqualTo(250);
        assertThatThrownBy(() -> orderPolicy.parseTerms(" intc ", " us ", " buy ", "1.5"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_QUANTITY));
        var terms = orderPolicy.parseTerms(" intc ", " us ", " buy ", " 1.0 ");
        assertThat(terms.symbol()).isEqualTo("INTC");
        assertThat(terms.quantity()).isEqualByComparingTo(BigDecimal.ONE);
    }

    @Test
    void 검색은_내부공백을_제거하고_종목의_null_필드는_빈값으로_취급한다() {
        StockRepository repository = mock(StockRepository.class);
        Stock stock = mock(Stock.class);
        when(stock.getName()).thenReturn("삼성전자");
        when(stock.getSymbol()).thenReturn("005930");
        when(repository.searchByKeyword("삼성")).thenReturn(List.of(stock, stock));
        StockSearchService service = new StockSearchService(repository);

        assertThat(service.search(" 삼\t성 ").items()).hasSize(2);
        verify(repository).searchByKeyword("삼성");
        assertThatThrownBy(() -> service.search(null))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_QUERY));
    }

    @Test
    void 환율은_정규화된_통화로_조회하되_미지원_통화의_기존_오류를_유지한다() {
        ExchangeRateRepository repository = mock(ExchangeRateRepository.class);
        when(repository.findTopByBaseCurrencyAndQuoteCurrencyOrderByRateAtDesc("XXX", "KRW"))
                .thenReturn(Optional.empty());
        ExchangeRateService service = new ExchangeRateService(repository, Clock.systemUTC());

        assertThatThrownBy(() -> service.getLatest(" xxx ", " krw "))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.EXCHANGE_RATE_NOT_FOUND));
        verify(repository).findTopByBaseCurrencyAndQuoteCurrencyOrderByRateAtDesc("XXX", "KRW");
    }
}
