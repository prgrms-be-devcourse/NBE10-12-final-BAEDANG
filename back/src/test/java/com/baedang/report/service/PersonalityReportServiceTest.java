package com.baedang.report.service;

import com.baedang.account.service.AccountValuationService;
import com.baedang.account.support.AccountValuation;
import com.baedang.account.support.HoldingValuation;
import com.baedang.report.dto.PersonalityReportResponse;
import com.baedang.report.support.FourWeekCostProfiler;
import com.baedang.report.support.InvestmentTypeClassifier;
import com.baedang.stock.entity.MarketCountry;
import com.baedang.stock.entity.Stock;
import com.baedang.stock.entity.StockCategory;
import com.baedang.stock.repository.StockRepository;
import com.baedang.trading.entity.OrderSide;
import com.baedang.trading.model.CostReplayEvent;
import com.baedang.trading.model.HoldingReplayEvent;
import com.baedang.trading.repository.TradeExecutionRepository;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PersonalityReportServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-09T00:00:00Z");

    @Mock AccountValuationService accountValuationService;
    @Mock StockRepository stockRepository;
    @Mock TradeExecutionRepository tradeExecutionRepository;
    @Mock Account account;

    private PersonalityReportService service() {
        InvestmentTypeClassifier classifier = new InvestmentTypeClassifier();
        return new PersonalityReportService(
                accountValuationService,
                stockRepository,
                tradeExecutionRepository,
                classifier,
                new FourWeekCostProfiler(classifier),
                4,
                4,
                4,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    /** 원가 재생 이벤트 한 건(매수/매도, 원화 거래대금). */
    private static CostReplayEvent costEvent(long stockId, OrderSide side, long qty, long grossKrw, String at) {
        return new CostReplayEvent(stockId, side, BigDecimal.valueOf(qty),
                BigDecimal.valueOf(grossKrw), OffsetDateTime.parse(at));
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

    private static HoldingReplayEvent exec(long stockId, OrderSide side, long qty, String executedAt) {
        return new HoldingReplayEvent(
                stockId, side, BigDecimal.valueOf(qty), OffsetDateTime.parse(executedAt));
    }

    private void givenAccount(long initialCash, long cashBalance) {
        when(account.getAccountId()).thenReturn(10L);
        when(account.getRoundNo()).thenReturn(1);
        when(account.getInitialCash()).thenReturn(BigDecimal.valueOf(initialCash));
        when(account.getCashBalance()).thenReturn(BigDecimal.valueOf(cashBalance));
        // 6주 전 개설 → MBTI 4주 창 = [NOW−4주, NOW].
        when(account.getOpenedAt()).thenReturn(OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC).minusWeeks(6));
    }

    @Test
    void 수익률은_초기자본_대비_총손익이고_유형을_분류한다() {
        givenAccount(50_000_000, 20_000_000);
        List<HoldingValuation> valuations = List.of(valuation(1, 21_000_000), valuation(2, 12_000_000));
        when(accountValuationService.valuateActiveAccount(1L))
                .thenReturn(new AccountValuation(account, List.of(), Map.of(), valuations, null));
        Stock s1 = stock(1, StockCategory.INDIVIDUAL, MarketCountry.KR, null);
        Stock s2 = stock(2, StockCategory.ETF, MarketCountry.US, "1.0");
        when(stockRepository.findByStockIdIn(any())).thenReturn(List.of(s1, s2));
        // 원가 구성: 국내 개별주 2,100만 + 미국 ETF 1,200만. 창 이전 매수라 4주 내내 구성 불변
        // → 4주 평균 = 스냅샷. 국내·개별주·top1 = 21/33 ≈ 0.6364 → CKSB.
        when(tradeExecutionRepository.findCostReplayEvents(10L)).thenReturn(List.of(
                costEvent(1, OrderSide.BUY, 10, 21_000_000, "2026-08-01T00:00:00Z"),
                costEvent(2, OrderSide.BUY, 10, 12_000_000, "2026-08-01T00:00:00Z")));

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
        assertThat(r.longHeldStocks()).isEmpty(); // 체결 재생은 원가용이고 성과 섹션은 별도(기본 빈 목록)
    }

    @Test
    void 개설_4주_미만이면_리포트가_잠긴다() {
        when(account.getAccountId()).thenReturn(10L);
        when(account.getRoundNo()).thenReturn(2);
        OffsetDateTime openedAt = OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC).minusWeeks(1);
        when(account.getOpenedAt()).thenReturn(openedAt);
        when(accountValuationService.valuateActiveAccount(1L))
                .thenReturn(new AccountValuation(account, List.of(), Map.of(), List.of(), null));

        PersonalityReportResponse r = service().getReport(1L);

        assertThat(r.locked()).isTrue();
        assertThat(r.unlockAt()).isEqualTo(openedAt.plusWeeks(4));
        assertThat(r.classified()).isFalse();
        assertThat(r.typeCode()).isNull();
        assertThat(r.roundNo()).isEqualTo(2);
    }

    @Test
    void 보유가_1종목이면_미분류이고_유형코드는_없다() {
        givenAccount(50_000_000, 40_000_000);
        List<HoldingValuation> valuations = List.of(valuation(1, 11_000_000));
        when(accountValuationService.valuateActiveAccount(1L))
                .thenReturn(new AccountValuation(account, List.of(), Map.of(), valuations, null));
        Stock s1 = stock(1, StockCategory.INDIVIDUAL, MarketCountry.KR, null);
        when(stockRepository.findByStockIdIn(any())).thenReturn(List.of(s1));

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
        when(stockRepository.findByStockIdIn(any())).thenReturn(List.of(s1, s2));
        when(tradeExecutionRepository.findHoldingReplayEvents(10L, List.of(1L, 2L)))
                .thenReturn(List.of(
                        exec(1, OrderSide.BUY, 10, "2026-08-01T00:00:00Z"),
                        exec(2, OrderSide.BUY, 10, "2026-09-01T00:00:00Z")));

        PersonalityReportResponse r = service().getReport(1L);

        assertThat(r.longHeldStocks()).hasSize(1);
        PersonalityReportResponse.LongHeldStock item = r.longHeldStocks().getFirst();
        assertThat(item.symbol()).isEqualTo("SYM1");
        assertThat(item.returnRate()).isEqualTo("0.2");
        assertThat(item.heldSince()).isEqualTo(OffsetDateTime.parse("2026-08-01T00:00:00Z"));
    }

    @Test
    void 전량매도_뒤_재체결된_종목은_체결시각_기준이라_4주_목록에서_빠진다() {
        givenAccount(50_000_000, 20_000_000);
        // stock1: 8/1 매수 → 9/8 전량 매도 → 9/9 지정가 5주 체결. 현재 lot 은 9/9(임계 이후)라 제외.
        List<HoldingValuation> valuations = List.of(valuation(1, 21_000_000, 10_000, 12_000));
        when(accountValuationService.valuateActiveAccount(1L))
                .thenReturn(new AccountValuation(account, List.of(), Map.of(), valuations, null));
        Stock s1 = stock(1, StockCategory.INDIVIDUAL, MarketCountry.KR, null);
        when(stockRepository.findByStockIdIn(any())).thenReturn(List.of(s1));
        when(tradeExecutionRepository.findHoldingReplayEvents(10L, List.of(1L)))
                .thenReturn(List.of(
                        exec(1, OrderSide.BUY, 10, "2026-08-01T00:00:00Z"),
                        exec(1, OrderSide.SELL, 10, "2026-09-08T00:00:00Z"),
                        exec(1, OrderSide.BUY, 5, "2026-09-09T00:00:00Z")));

        PersonalityReportResponse r = service().getReport(1L);

        assertThat(r.longHeldStocks()).isEmpty();
    }
}
