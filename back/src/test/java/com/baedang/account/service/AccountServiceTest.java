package com.baedang.account.service;

import com.baedang.account.dto.AccountSummaryResponse;
import com.baedang.account.dto.HoldingsResponse;
import com.baedang.global.error.BusinessException;
import com.baedang.global.error.ErrorCode;
import com.baedang.market.entity.ExchangeRate;
import com.baedang.market.entity.QuoteSnapshot;
import com.baedang.market.repository.ExchangeRateRepository;
import com.baedang.market.repository.QuoteSnapshotRepository;
import com.baedang.stock.entity.MarketCountry;
import com.baedang.stock.entity.Stock;
import com.baedang.stock.repository.StockRepository;
import com.baedang.trading.entity.Holding;
import com.baedang.trading.repository.HoldingRepository;
import com.baedang.user.entity.Account;
import com.baedang.user.entity.AccountStatus;
import com.baedang.user.repository.AccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccountServiceTest {

    @Mock AccountRepository accountRepository;
    @Mock HoldingRepository holdingRepository;
    @Mock QuoteSnapshotRepository quoteSnapshotRepository;
    @Mock ExchangeRateRepository exchangeRateRepository;
    @Mock StockRepository stockRepository;
    @Mock Account account;

    private static final Instant NOW = Instant.parse("2026-08-26T03:00:00Z");

    private AccountService service;

    @BeforeEach
    void setUp() {
        service = new AccountService(
                accountRepository,
                holdingRepository,
                quoteSnapshotRepository,
                exchangeRateRepository,
                stockRepository,
                new HoldingValuator(),
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void ACTIVE_계좌가_없으면_ACCOUNT_NOT_FOUND_를_던진다() {
        when(accountRepository.findByUserIdAndStatus(1L, AccountStatus.ACTIVE))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getSummary(1L))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.ACCOUNT_NOT_FOUND));

        verifyNoInteractions(holdingRepository, quoteSnapshotRepository, exchangeRateRepository);
    }

    @Test
    void 보유종목이_없으면_평가액과_손익은_0_이고_총자산은_예수금과_같다() {
        givenAccount(1L, "50000000", "50000000", 1);
        when(holdingRepository.findByAccountIdAndQuantityGreaterThan(1L, BigDecimal.ZERO))
                .thenReturn(List.of());
        when(exchangeRateRepository.findTopByBaseCurrencyAndQuoteCurrencyOrderByRateAtDesc("USD", "KRW"))
                .thenReturn(Optional.empty());

        AccountSummaryResponse response = service.getSummary(1L);

        assertThat(response.accountId()).isEqualTo(1L);
        assertThat(response.roundNo()).isEqualTo(1);
        assertThat(response.stockValue()).isEqualTo("0");
        assertThat(response.totalAsset()).isEqualTo("50000000");
        assertThat(response.unrealizedPnl()).isEqualTo("0");
        assertThat(response.unrealizedPnlRate()).isNull();
        assertThat(response.exchangeRate()).isNull();
        assertThat(response.asOf()).isEqualTo(OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));
    }

    @Test
    void 국내와_미국_보유를_각각_원화로_평가해_합산한다() {
        givenAccount(1L, "50000000", "48240000", 1);
        Holding krHolding = Holding.firstBuy(1L, 101L,
                new BigDecimal("6"), new BigDecimal("228000"), BigDecimal.ONE,
                OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));
        Holding usHolding = Holding.firstBuy(1L, 202L,
                new BigDecimal("10"), new BigDecimal("88.34"), new BigDecimal("1383.60"),
                OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));
        when(holdingRepository.findByAccountIdAndQuantityGreaterThan(1L, BigDecimal.ZERO))
                .thenReturn(List.of(krHolding, usHolding));
        when(quoteSnapshotRepository.findByStockIdIn(any()))
                .thenReturn(List.of(quote(101L, "241500", "KRW"), quote(202L, "90.00", "USD")));
        when(exchangeRateRepository.findTopByBaseCurrencyAndQuoteCurrencyOrderByRateAtDesc("USD", "KRW"))
                .thenReturn(Optional.of(rate("1401", "1400")));

        AccountSummaryResponse response = service.getSummary(1L);

        // stockValue = 1,449,000(KR) + 1,260,000(US) = 2,709,000
        assertThat(response.stockValue()).isEqualTo("2709000");
        // totalAsset = 48,240,000 + 2,709,000 = 50,949,000
        assertThat(response.totalAsset()).isEqualTo("50949000");
        // pnl = 2,709,000 - (1,368,000 + 1,222,272) = 118,728
        assertThat(response.unrealizedPnl()).isEqualTo("118728");
        // pnlRate = 118,728 / 2,590,272 = 0.0458
        assertThat(response.unrealizedPnlRate()).isEqualTo("0.0458");
        assertThat(response.exchangeRate()).isEqualTo("1400");
    }

    @Test
    void 미국_보유가_있는데_환율이_없으면_매입환율로_환산하고_엔드포인트를_유지한다() {
        givenAccount(1L, "50000000", "10000000", 1);
        Holding usHolding = Holding.firstBuy(1L, 202L,
                new BigDecimal("10"), new BigDecimal("88.34"), new BigDecimal("1383.60"),
                OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));
        when(holdingRepository.findByAccountIdAndQuantityGreaterThan(1L, BigDecimal.ZERO))
                .thenReturn(List.of(usHolding));
        when(quoteSnapshotRepository.findByStockIdIn(any()))
                .thenReturn(List.of(quote(202L, "90.00", "USD")));
        when(exchangeRateRepository.findTopByBaseCurrencyAndQuoteCurrencyOrderByRateAtDesc("USD", "KRW"))
                .thenReturn(Optional.empty());

        AccountSummaryResponse response = service.getSummary(1L);

        // 매입환율 폴백: 900.00 x 1383.60 = 1,245,240
        assertThat(response.stockValue()).isEqualTo("1245240");
        assertThat(response.totalAsset()).isEqualTo("11245240");
        assertThat(response.exchangeRate()).isNull();
    }

    @Test
    void 보유목록을_평가금액_내림차순으로_정렬하고_종목별_손익률을_계산한다() {
        givenActiveAccount(1L);
        Holding krHolding = Holding.firstBuy(1L, 101L,
                new BigDecimal("6"), new BigDecimal("228000"), BigDecimal.ONE,
                OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));
        Holding usHolding = Holding.firstBuy(1L, 202L,
                new BigDecimal("10"), new BigDecimal("88.34"), new BigDecimal("1383.60"),
                OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));
        // 평가금액이 큰 국내(1,449,000)가 미국(1,260,000)보다 먼저 오도록, 일부러 미국을 앞에 넣는다.
        when(holdingRepository.findByAccountIdAndQuantityGreaterThan(1L, BigDecimal.ZERO))
                .thenReturn(List.of(usHolding, krHolding));
        when(quoteSnapshotRepository.findByStockIdIn(any()))
                .thenReturn(List.of(
                        quote(101L, "241500", "KRW", fresh()),
                        quote(202L, "90.00", "USD", fresh())));
        when(stockRepository.findByStockIdIn(any()))
                .thenReturn(List.of(
                        stock(101L, "005930", "삼성전자", MarketCountry.KR, "KRW"),
                        stock(202L, "AAPL", "애플", MarketCountry.US, "USD")));
        when(exchangeRateRepository.findTopByBaseCurrencyAndQuoteCurrencyOrderByRateAtDesc("USD", "KRW"))
                .thenReturn(Optional.of(rate("1401", "1400")));

        HoldingsResponse response = service.getHoldings(1L);

        assertThat(response.items()).hasSize(2);
        assertThat(response.asOf()).isEqualTo(OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));

        HoldingsResponse.Item kr = response.items().get(0);
        assertThat(kr.symbol()).isEqualTo("005930");
        assertThat(kr.name()).isEqualTo("삼성전자");
        assertThat(kr.currency()).isEqualTo("KRW");
        assertThat(kr.quantity()).isEqualTo("6");
        assertThat(kr.avgBuyPrice()).isEqualTo("228000");
        assertThat(kr.avgExchangeRate()).isEqualTo("1");
        assertThat(kr.lastPrice()).isEqualTo("241500");
        assertThat(kr.evaluationAmount()).isEqualTo("1449000");
        assertThat(kr.unrealizedPnl()).isEqualTo("81000");
        assertThat(kr.unrealizedPnlRate()).isEqualTo("0.0592");
        assertThat(kr.realtime()).isTrue();

        HoldingsResponse.Item us = response.items().get(1);
        assertThat(us.symbol()).isEqualTo("AAPL");
        assertThat(us.currency()).isEqualTo("USD");
        assertThat(us.avgExchangeRate()).isEqualTo("1383.6");
        assertThat(us.lastPrice()).isEqualTo("90");
        // eval = 10 x 90 x 1400 = 1,260,000 · cost = 10 x 88.34 x 1383.60 = 1,222,272
        assertThat(us.evaluationAmount()).isEqualTo("1260000");
        assertThat(us.unrealizedPnl()).isEqualTo("37728");
        // 37,728 / 1,222,272 = 0.0309 (소수 4자리 HALF_UP)
        assertThat(us.unrealizedPnlRate()).isEqualTo("0.0309");
    }

    @Test
    void 시세가_신선도_임계값보다_오래되면_realtime_은_false_다() {
        givenActiveAccount(1L);
        Holding krHolding = Holding.firstBuy(1L, 101L,
                new BigDecimal("6"), new BigDecimal("228000"), BigDecimal.ONE,
                OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));
        when(holdingRepository.findByAccountIdAndQuantityGreaterThan(1L, BigDecimal.ZERO))
                .thenReturn(List.of(krHolding));
        when(quoteSnapshotRepository.findByStockIdIn(any()))
                .thenReturn(List.of(quote(101L, "241500", "KRW",
                        OffsetDateTime.ofInstant(NOW.minusSeconds(3600), ZoneOffset.UTC))));
        when(stockRepository.findByStockIdIn(any()))
                .thenReturn(List.of(stock(101L, "005930", "삼성전자", MarketCountry.KR, "KRW")));
        when(exchangeRateRepository.findTopByBaseCurrencyAndQuoteCurrencyOrderByRateAtDesc("USD", "KRW"))
                .thenReturn(Optional.empty());

        HoldingsResponse response = service.getHoldings(1L);

        assertThat(response.items().get(0).realtime()).isFalse();
    }

    @Test
    void 보유목록_조회시_ACTIVE_계좌가_없으면_ACCOUNT_NOT_FOUND_를_던진다() {
        when(accountRepository.findByUserIdAndStatus(1L, AccountStatus.ACTIVE))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getHoldings(1L))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.ACCOUNT_NOT_FOUND));

        verifyNoInteractions(holdingRepository, quoteSnapshotRepository, exchangeRateRepository, stockRepository);
    }

    @Test
    void 보유_종목의_종목마스터가_없으면_STOCK_NOT_FOUND_를_던진다() {
        givenActiveAccount(1L);
        Holding krHolding = Holding.firstBuy(1L, 101L,
                new BigDecimal("6"), new BigDecimal("228000"), BigDecimal.ONE, fresh());
        when(holdingRepository.findByAccountIdAndQuantityGreaterThan(1L, BigDecimal.ZERO))
                .thenReturn(List.of(krHolding));
        when(quoteSnapshotRepository.findByStockIdIn(any()))
                .thenReturn(List.of(quote(101L, "241500", "KRW")));
        when(exchangeRateRepository.findTopByBaseCurrencyAndQuoteCurrencyOrderByRateAtDesc("USD", "KRW"))
                .thenReturn(Optional.empty());
        // 데이터 정합성 오류 재현: 보유는 있는데 종목 마스터가 조회되지 않는다.
        when(stockRepository.findByStockIdIn(any()))
                .thenReturn(List.of());

        assertThatThrownBy(() -> service.getHoldings(1L))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.STOCK_NOT_FOUND));
    }

    /** 보유 목록 조회는 계좌 요약과 달리 예수금·회차를 읽지 않으므로 최소 스텁만 둔다. */
    private void givenActiveAccount(Long accountId) {
        when(accountRepository.findByUserIdAndStatus(1L, AccountStatus.ACTIVE))
                .thenReturn(Optional.of(account));
        when(account.getAccountId()).thenReturn(accountId);
    }

    private void givenAccount(Long accountId, String initialCash, String cashBalance, int roundNo) {
        when(accountRepository.findByUserIdAndStatus(1L, AccountStatus.ACTIVE))
                .thenReturn(Optional.of(account));
        when(account.getAccountId()).thenReturn(accountId);
        when(account.getInitialCash()).thenReturn(new BigDecimal(initialCash));
        when(account.getCashBalance()).thenReturn(new BigDecimal(cashBalance));
        when(account.getRoundNo()).thenReturn(roundNo);
    }

    private QuoteSnapshot quote(Long stockId, String lastPrice, String currency) {
        return quote(stockId, lastPrice, currency, fresh());
    }

    private QuoteSnapshot quote(Long stockId, String lastPrice, String currency, OffsetDateTime quoteAt) {
        return new QuoteSnapshot(stockId, new BigDecimal(lastPrice), currency, quoteAt, quoteAt);
    }

    /** 고정 시각(NOW) 기준 방금 수집된 시세 — realtime 판정이 true 가 되는 quote_at. */
    private OffsetDateTime fresh() {
        return OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC);
    }

    private Stock stock(Long stockId, String symbol, String name, MarketCountry country, String currency) {
        String market = country == MarketCountry.KR ? "KOSPI" : "NASDAQ";
        Stock stock = Stock.create(symbol, country, market, name, null, currency, "STOCK", true);
        ReflectionTestUtils.setField(stock, "stockId", stockId);
        return stock;
    }

    private ExchangeRate rate(String rate, String midRate) {
        OffsetDateTime rateAt = fresh();
        return new ExchangeRate(
                "USD",
                "KRW",
                new BigDecimal(rate),
                new BigDecimal(midRate),
                rateAt,
                rateAt);
    }
}
