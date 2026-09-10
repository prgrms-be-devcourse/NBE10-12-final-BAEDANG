package com.baedang.report.service;

import com.baedang.account.service.AccountValuationService;
import com.baedang.account.support.AccountValuation;
import com.baedang.account.support.HoldingValuation;
import com.baedang.report.dto.PersonalityReportResponse;
import com.baedang.report.support.InvestmentTypeClassifier;
import com.baedang.stock.entity.MarketCountry;
import com.baedang.stock.entity.Stock;
import com.baedang.stock.entity.StockCategory;
import com.baedang.stock.repository.StockRepository;
import com.baedang.user.entity.Account;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PersonalityReportServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-09T00:00:00Z");

    @Mock AccountValuationService accountValuationService;
    @Mock StockRepository stockRepository;
    @Mock Account account;

    private PersonalityReportService service() {
        return new PersonalityReportService(
                accountValuationService,
                stockRepository,
                new InvestmentTypeClassifier(),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static HoldingValuation valuation(long stockId, long evalWon) {
        return new HoldingValuation(
                stockId, "KRW", BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE,
                BigDecimal.ONE, BigDecimal.valueOf(evalWon), BigDecimal.valueOf(evalWon));
    }

    private static Stock stock(long stockId, StockCategory category, MarketCountry market, String leverage) {
        Stock stock = org.mockito.Mockito.mock(Stock.class);
        lenient().when(stock.getStockId()).thenReturn(stockId);
        lenient().when(stock.getStockCategory()).thenReturn(category);
        lenient().when(stock.getMarketCountry()).thenReturn(market);
        lenient().when(stock.getLeverageFactor()).thenReturn(leverage == null ? null : new BigDecimal(leverage));
        return stock;
    }

    private void givenAccount(long initialCash, long cashBalance) {
        when(account.getAccountId()).thenReturn(10L);
        when(account.getRoundNo()).thenReturn(1);
        when(account.getInitialCash()).thenReturn(BigDecimal.valueOf(initialCash));
        when(account.getCashBalance()).thenReturn(BigDecimal.valueOf(cashBalance));
    }

    @Test
    void 수익률은_초기자본_대비_총손익이고_유형을_분류한다() {
        givenAccount(50_000_000, 20_000_000);
        List<HoldingValuation> valuations = List.of(valuation(1, 21_000_000), valuation(2, 12_000_000));
        when(accountValuationService.valuateActiveAccount(1L))
                .thenReturn(new AccountValuation(account, List.of(), Map.of(), valuations, null));
        Stock s1 = stock(1, StockCategory.INDIVIDUAL, MarketCountry.KR, null);
        Stock s2 = stock(2, StockCategory.ETF, MarketCountry.US, "1.0");
        when(stockRepository.findByStockIdIn(List.of(1L, 2L))).thenReturn(List.of(s1, s2));

        PersonalityReportResponse r = service().getReport(1L);

        // stockValue 33,000,000 · totalAsset 53,000,000 · pnl 3,000,000 · return 3M/50M = 0.06
        assertThat(r.stockValue()).isEqualTo("33000000");
        assertThat(r.totalAsset()).isEqualTo("53000000");
        assertThat(r.totalPnl()).isEqualTo("3000000");
        assertThat(r.returnRate()).isEqualTo("0.06");
        assertThat(r.classified()).isTrue();
        // 21M 지배(집중)·국내(21/33)·개별주(21/33)·레버리지 없음(안정)
        assertThat(r.typeCode()).isEqualTo("CKSB");
        assertThat(r.holdingCount()).isEqualTo(2);
        assertThat(r.shares().domestic()).isEqualTo("0.6364");
    }

    @Test
    void 보유가_1종목이면_미분류이고_유형코드는_없다() {
        givenAccount(50_000_000, 40_000_000);
        List<HoldingValuation> valuations = List.of(valuation(1, 11_000_000));
        when(accountValuationService.valuateActiveAccount(1L))
                .thenReturn(new AccountValuation(account, List.of(), Map.of(), valuations, null));
        Stock s1 = stock(1, StockCategory.INDIVIDUAL, MarketCountry.KR, null);
        when(stockRepository.findByStockIdIn(List.of(1L))).thenReturn(List.of(s1));

        PersonalityReportResponse r = service().getReport(1L);

        assertThat(r.classified()).isFalse();
        assertThat(r.typeCode()).isNull();
        assertThat(r.typeLabel()).isNull();
        assertThat(r.holdingCount()).isEqualTo(1);
    }

    @Test
    void 보유가_없으면_전액_현금이고_미분류() {
        givenAccount(50_000_000, 50_000_000);
        when(accountValuationService.valuateActiveAccount(1L))
                .thenReturn(new AccountValuation(account, List.of(), Map.of(), List.of(), null));

        PersonalityReportResponse r = service().getReport(1L);

        assertThat(r.stockValue()).isEqualTo("0");
        assertThat(r.totalAsset()).isEqualTo("50000000");
        assertThat(r.totalPnl()).isEqualTo("0");
        assertThat(r.classified()).isFalse();
        assertThat(r.holdingCount()).isZero();
    }
}
