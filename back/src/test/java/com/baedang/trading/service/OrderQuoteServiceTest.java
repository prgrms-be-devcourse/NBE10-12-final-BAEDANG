package com.baedang.trading.service;

import com.baedang.global.error.BusinessException;
import com.baedang.global.error.ErrorCode;
import com.baedang.market.entity.QuoteSnapshot;
import com.baedang.market.repository.QuoteSnapshotRepository;
import com.baedang.market.port.ExecutionExchangeRateProvider;
import com.baedang.market.port.MarketSessionProvider;
import com.baedang.stock.entity.ListingStatus;
import com.baedang.stock.entity.MarketCountry;
import com.baedang.stock.entity.Stock;
import com.baedang.stock.repository.StockRepository;
import com.baedang.trading.dto.OrderQuoteResponse;
import com.baedang.trading.repository.HoldingRepository;
import com.baedang.user.entity.Account;
import com.baedang.user.entity.AccountStatus;
import com.baedang.user.repository.AccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderQuoteServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-25T03:00:00Z");

    @Mock AccountRepository accountRepository;
    @Mock StockRepository stockRepository;
    @Mock QuoteSnapshotRepository quoteSnapshotRepository;
    @Mock HoldingRepository holdingRepository;
    @Mock MarketSessionProvider marketSessionProvider;
    @Mock ExecutionExchangeRateProvider exchangeRateProvider;
    @Mock Account account;
    @Mock Stock stock;

    private OrderQuoteService service;

    @BeforeEach
    void setUp() {
        OrderAmountCalculator calculator = new OrderAmountCalculator(
                new BigDecimal("0.0001"),
                new BigDecimal("0.002"),
                new BigDecimal("0.0000206"),
                new BigDecimal("0.01")
        );
        service = new OrderQuoteService(
                accountRepository,
                stockRepository,
                quoteSnapshotRepository,
                holdingRepository,
                marketSessionProvider,
                exchangeRateProvider,
                calculator,
                new MarketOrderPolicy(15),
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void 시장가_매수_견적을_조회하고_데이터를_변경하지_않는다() {
        givenTradableKrStock(new BigDecimal("241500"), 5);

        OrderQuoteResponse result = service.getQuote(1L, "005930", "buy", "10");

        assertThat(result.symbol()).isEqualTo("005930");
        assertThat(result.side().name()).isEqualTo("BUY");
        assertThat(result.quantity()).isEqualTo("10");
        assertThat(result.grossAmount()).isEqualTo("2415000");
        assertThat(result.fee()).isEqualTo("242");
        assertThat(result.tax()).isEqualTo("0");
        assertThat(result.netAmount()).isEqualTo("2415242");
        assertThat(result.executable()).isTrue();
        assertThat(result.reason()).isNull();
    }

    @Test
    void 주문가능금액은_예수금에서_동결액을_뺀_값으로_판정한다() {
        givenTradableKrStock(new BigDecimal("241500"), 5);
        when(account.availableCash()).thenReturn(new BigDecimal("1000000"));

        OrderQuoteResponse result = service.getQuote(1L, "005930", "BUY", "10");

        assertThat(result.executable()).isFalse();
        assertThat(result.reason()).isEqualTo(ErrorCode.INSUFFICIENT_CASH.name());
        assertThat(result.availableCash()).isEqualTo("1000000");
    }

    @Test
    void 보유종목이_없으면_매도할_수_없다() {
        givenTradableKrStock(new BigDecimal("241500"), 5);
        when(account.getAccountId()).thenReturn(11L);
        when(holdingRepository.findByAccountIdAndStockId(11L, 101L)).thenReturn(Optional.empty());

        OrderQuoteResponse result = service.getQuote(1L, "005930", "SELL", "1");

        assertThat(result.executable()).isFalse();
        assertThat(result.reason()).isEqualTo(ErrorCode.INSUFFICIENT_QUANTITY.name());
    }

    @Test
    void 시세가_15초를_초과하면_오래된_시세로_판정한다() {
        givenTradableKrStock(new BigDecimal("241500"), 16);

        OrderQuoteResponse result = service.getQuote(1L, "005930", "BUY", "1");

        assertThat(result.executable()).isFalse();
        assertThat(result.reason()).isEqualTo(ErrorCode.STALE_QUOTE.name());
    }

    @Test
    void 랭킹외_종목은_다른_실행불가_사유보다_우선한다() {
        when(accountRepository.findByUserIdAndStatus(1L, AccountStatus.ACTIVE))
                .thenReturn(Optional.of(account));
        when(account.availableCash()).thenReturn(new BigDecimal("50000000"));
        when(stockRepository.findFirstBySymbolIgnoreCaseOrderByStockIdAsc("005930"))
                .thenReturn(Optional.of(stock));
        when(stock.getStockId()).thenReturn(101L);
        when(stock.getSymbol()).thenReturn("005930");
        when(stock.getMarketCountry()).thenReturn(MarketCountry.KR);
        when(stock.getIsRanked()).thenReturn(false);
        QuoteSnapshot quote = new QuoteSnapshot(
                101L,
                new BigDecimal("241500"),
                "KRW",
                NOW.minusSeconds(5).atOffset(ZoneOffset.UTC)
        );
        when(quoteSnapshotRepository.findById(101L)).thenReturn(Optional.of(quote));

        OrderQuoteResponse result = service.getQuote(1L, "005930", "BUY", "1");

        assertThat(result.reason()).isEqualTo(ErrorCode.NOT_IN_UNIVERSE.name());
        verifyNoInteractions(marketSessionProvider);
    }

    @Test
    void 소수점_수량은_조회전에_거절한다() {
        assertThatThrownBy(() -> service.getQuote(1L, "005930", "BUY", "0.5"))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_QUANTITY));
        verifyNoInteractions(accountRepository, stockRepository, quoteSnapshotRepository);
    }

    private void givenTradableKrStock(BigDecimal price, long quoteAgeSeconds) {
        when(accountRepository.findByUserIdAndStatus(1L, AccountStatus.ACTIVE))
                .thenReturn(Optional.of(account));
        when(account.availableCash()).thenReturn(new BigDecimal("50000000"));

        when(stockRepository.findFirstBySymbolIgnoreCaseOrderByStockIdAsc("005930"))
                .thenReturn(Optional.of(stock));
        when(stock.getStockId()).thenReturn(101L);
        when(stock.getSymbol()).thenReturn("005930");
        when(stock.getMarketCountry()).thenReturn(MarketCountry.KR);
        when(stock.getIsRanked()).thenReturn(true);
        when(stock.getListingStatus()).thenReturn(ListingStatus.ACTIVE);
        when(stock.getIsSuspended()).thenReturn(false);
        when(stock.getIsLiquidation()).thenReturn(false);

        OffsetDateTime quoteAt = NOW.minusSeconds(quoteAgeSeconds).atOffset(ZoneOffset.UTC);
        QuoteSnapshot quote = new QuoteSnapshot(101L, price, "KRW", quoteAt);
        when(quoteSnapshotRepository.findById(101L)).thenReturn(Optional.of(quote));
        when(marketSessionProvider.isOpen(MarketCountry.KR, NOW)).thenReturn(true);
    }
}
