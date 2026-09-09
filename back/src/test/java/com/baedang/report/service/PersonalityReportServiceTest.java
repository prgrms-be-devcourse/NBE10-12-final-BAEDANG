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
import com.baedang.trading.entity.OrderSide;
import com.baedang.trading.entity.TradeOrder;
import com.baedang.trading.repository.TradeOrderRepository;
import com.baedang.user.entity.Account;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
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
    @Mock TradeOrderRepository tradeOrderRepository;
    @Mock Account account;

    private PersonalityReportService service() {
        return new PersonalityReportService(
                accountValuationService,
                stockRepository,
                tradeOrderRepository,
                new InvestmentTypeClassifier(),
                4,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static HoldingValuation valuation(long stockId, long evalWon) {
        return valuation(stockId, evalWon, 1, 1);
    }

    private static HoldingValuation valuation(long stockId, long evalWon, long avgBuyPrice, long lastPrice) {
        return new HoldingValuation(
                stockId, "KRW", BigDecimal.ONE, BigDecimal.valueOf(avgBuyPrice), BigDecimal.ONE,
                BigDecimal.valueOf(lastPrice), BigDecimal.valueOf(evalWon), BigDecimal.valueOf(evalWon));
    }

    private static Stock stock(long stockId, StockCategory category, MarketCountry market, String leverage) {
        Stock stock = org.mockito.Mockito.mock(Stock.class);
        lenient().when(stock.getStockId()).thenReturn(stockId);
        lenient().when(stock.getSymbol()).thenReturn("SYM" + stockId);
        lenient().when(stock.getName()).thenReturn("종목" + stockId);
        lenient().when(stock.getStockCategory()).thenReturn(category);
        lenient().when(stock.getMarketCountry()).thenReturn(market);
        lenient().when(stock.getLeverageFactor()).thenReturn(leverage == null ? null : new BigDecimal(leverage));
        return stock;
    }

    private static TradeOrder fill(long stockId, OrderSide side, long qty, String orderedAt) {
        TradeOrder o = org.mockito.Mockito.mock(TradeOrder.class);
        lenient().when(o.getStockId()).thenReturn(stockId);
        lenient().when(o.getSide()).thenReturn(side);
        lenient().when(o.getFilledQuantity()).thenReturn(BigDecimal.valueOf(qty));
        lenient().when(o.getOrderedAt()).thenReturn(OffsetDateTime.parse(orderedAt));
        return o;
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

        assertThat(r.stockValue()).isEqualTo("33000000");
        assertThat(r.totalAsset()).isEqualTo("53000000");
        assertThat(r.totalPnl()).isEqualTo("3000000");
        assertThat(r.returnRate()).isEqualTo("0.06");
        assertThat(r.classified()).isTrue();
        assertThat(r.typeCode()).isEqualTo("CKSB");
        assertThat(r.holdingCount()).isEqualTo(2);
        assertThat(r.shares().domestic()).isEqualTo("0.6364");
        assertThat(r.holdingPeriodWeeks()).isEqualTo(4);
        assertThat(r.longHeldStocks()).isEmpty(); // 체결 이력 없음(기본 빈 목록)
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
        assertThat(r.classified()).isFalse();
        assertThat(r.longHeldStocks()).isEmpty();
    }

    @Test
    void 성과_섹션은_4주_이상_보유한_종목만_담고_보유수익률을_계산한다() {
        givenAccount(50_000_000, 20_000_000);
        // stock1: 4주 이전 매수(2026-08-01) · 평단가 10,000 → 현재가 12,000 = +0.2
        // stock2: 최근 매수(2026-09-01) → 임계(2026-08-12) 이후라 제외
        List<HoldingValuation> valuations = List.of(
                valuation(1, 21_000_000, 10_000, 12_000),
                valuation(2, 12_000_000, 5_000, 5_000));
        when(accountValuationService.valuateActiveAccount(1L))
                .thenReturn(new AccountValuation(account, List.of(), Map.of(), valuations, null));
        Stock s1 = stock(1, StockCategory.INDIVIDUAL, MarketCountry.KR, null);
        Stock s2 = stock(2, StockCategory.ETF, MarketCountry.US, "1.0");
        when(stockRepository.findByStockIdIn(List.of(1L, 2L))).thenReturn(List.of(s1, s2));
        TradeOrder o1 = fill(1, OrderSide.BUY, 10, "2026-08-01T00:00:00Z");
        TradeOrder o2 = fill(2, OrderSide.BUY, 10, "2026-09-01T00:00:00Z");
        when(tradeOrderRepository.findFilledByAccountAndStocks(10L, List.of(1L, 2L)))
                .thenReturn(List.of(o1, o2));

        PersonalityReportResponse r = service().getReport(1L);

        assertThat(r.longHeldStocks()).hasSize(1);
        PersonalityReportResponse.LongHeldStock item = r.longHeldStocks().getFirst();
        assertThat(item.symbol()).isEqualTo("SYM1");
        assertThat(item.returnRate()).isEqualTo("0.2");
        assertThat(item.heldSince()).isEqualTo(OffsetDateTime.parse("2026-08-01T00:00:00Z"));
    }
}
