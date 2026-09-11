package com.baedang.report.service;

import com.baedang.account.service.AccountValuationService;
import com.baedang.account.support.AccountValuation;
import com.baedang.account.support.HoldingValuation;
import com.baedang.account.support.ReturnRateCalculator;
import com.baedang.global.error.BusinessException;
import com.baedang.global.error.ErrorCode;
import com.baedang.global.formatter.FinancialDecimalFormatter;
import com.baedang.report.dto.PersonalityReportResponse;
import com.baedang.report.dto.PersonalityReportResponse.LongHeldStock;
import com.baedang.report.support.AxisShares;
import com.baedang.report.support.FourWeekCostProfiler;
import com.baedang.report.support.HoldingLotTracker;
import com.baedang.report.support.InvestmentProfile;
import com.baedang.report.support.InvestmentTypeClassifier;
import com.baedang.stock.entity.Stock;
import com.baedang.stock.repository.StockRepository;
import com.baedang.trading.model.CostReplayEvent;
import com.baedang.trading.model.HoldingReplayEvent;
import com.baedang.trading.repository.TradeExecutionRepository;
import com.baedang.user.entity.Account;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 투자 성향 리포트 조회 서비스(현재 활성 계좌=라운드 기준, 온디맨드).
 *
 * <p>평가금액은 계좌 요약·보유 목록과 <b>같은 {@link AccountValuationService}</b> 로만 구한다
 * (단일 평가 지점). 계좌 수익률은 <b>초기자본 대비 총손익</b>이다 —
 * {@code (cash + stockValue − initial_cash)/initial_cash}, 실현+미실현을 모두 포함한다.
 * (계좌 요약의 미실현/원가 손익률과는 다른 지표라 재사용하지 않는다.)
 *
 * <p>N주 이상 보유 성과 섹션은 {@code trade_execution} 개별 체결을 재생해 현재 lot 의 첫
 * 매수 시각을 구하고({@link HoldingLotTracker}), 임계 기간을 넘은 종목만 담는다. 등락률은
 * <b>내 보유 수익률</b>(현재가 대비 평단가)이다.
 */
@Service
@Transactional(readOnly = true)
public class PersonalityReportService {

    private final AccountValuationService accountValuationService;
    private final StockRepository stockRepository;
    private final TradeExecutionRepository tradeExecutionRepository;
    private final InvestmentTypeClassifier classifier;
    private final FourWeekCostProfiler costProfiler;
    private final int holdingPeriodWeeks;
    private final int mbtiWindowWeeks;
    private final Clock clock;

    public PersonalityReportService(AccountValuationService accountValuationService,
                                    StockRepository stockRepository,
                                    TradeExecutionRepository tradeExecutionRepository,
                                    InvestmentTypeClassifier classifier,
                                    FourWeekCostProfiler costProfiler,
                                    @Value("${report.holding-period-weeks:4}") int holdingPeriodWeeks,
                                    @Value("${report.mbti-window-weeks:4}") int mbtiWindowWeeks,
                                    Clock clock) {
        this.accountValuationService = accountValuationService;
        this.stockRepository = stockRepository;
        this.tradeExecutionRepository = tradeExecutionRepository;
        this.classifier = classifier;
        this.costProfiler = costProfiler;
        this.holdingPeriodWeeks = holdingPeriodWeeks;
        this.mbtiWindowWeeks = mbtiWindowWeeks;
        this.clock = clock;
    }

    public PersonalityReportResponse getReport(Long userId) {
        AccountValuation valued = accountValuationService.valuateActiveAccount(userId);
        Account account = valued.account();
        OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);

        BigDecimal stockValue = valued.valuations().stream()
                .map(HoldingValuation::evalWon)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalAsset = account.getCashBalance().add(stockValue);
        BigDecimal totalPnl = totalAsset.subtract(account.getInitialCash());
        // 초기자본은 계좌별 저장 컬럼이라 항상 양수 → 라운드 내 고정 분모.
        BigDecimal returnRate = ReturnRateCalculator.calculate(totalPnl, account.getInitialCash());

        // 투자 MBTI 는 4주 창의 원가 구성 시점별 평균으로 판정한다(§6.2, 체결 재생·무가격).
        List<CostReplayEvent> costEvents =
                tradeExecutionRepository.findCostReplayEvents(account.getAccountId());
        Map<Long, Stock> stocks = stocksForReport(valued, costEvents);
        OffsetDateTime windowStart = laterOf(account.getOpenedAt(), now.minusWeeks(mbtiWindowWeeks));
        AxisShares avgShares = costProfiler.averageShares(costEvents, stocks, windowStart, now);
        InvestmentProfile profile = classifier.classifyFromShares(avgShares, valued.valuations().size());

        List<LongHeldStock> longHeld = longHeldStocks(account.getAccountId(), valued, stocks, now);

        return PersonalityReportResponse.of(
                account, stockValue, totalAsset, totalPnl, returnRate,
                profile, holdingPeriodWeeks, longHeld, now);
    }

    /**
     * 리포트에 필요한 종목 마스터 — 현재 보유 + 4주 창 원가 재생에 등장한 종목(창 안에서 전량
     * 매도돼 지금은 없는 종목 포함)의 합집합. 한 번에 조회한다.
     */
    private Map<Long, Stock> stocksForReport(AccountValuation valued, List<CostReplayEvent> costEvents) {
        Set<Long> stockIds = new HashSet<>();
        valued.valuations().forEach(v -> stockIds.add(v.stockId()));
        costEvents.forEach(e -> stockIds.add(e.stockId()));
        if (stockIds.isEmpty()) {
            return Map.of();
        }
        return stockRepository.findByStockIdIn(stockIds).stream()
                .collect(Collectors.toMap(Stock::getStockId, Function.identity()));
    }

    private static OffsetDateTime laterOf(OffsetDateTime a, OffsetDateTime b) {
        return a.isAfter(b) ? a : b;
    }

    /** N주 이상 보유한 종목의 성과. 체결 이력 재생으로 현재 lot 시작 시각을 구해 임계로 거른다. */
    private List<LongHeldStock> longHeldStocks(
            Long accountId, AccountValuation valued, Map<Long, Stock> stocks, OffsetDateTime now) {
        if (valued.valuations().isEmpty()) {
            return List.of();
        }
        List<Long> stockIds = valued.valuations().stream().map(HoldingValuation::stockId).toList();
        Map<Long, List<HoldingReplayEvent>> executionsByStock =
                tradeExecutionRepository.findHoldingReplayEvents(accountId, stockIds).stream()
                        .collect(Collectors.groupingBy(HoldingReplayEvent::stockId));

        OffsetDateTime threshold = now.minusWeeks(holdingPeriodWeeks);
        List<LongHeldStock> items = new ArrayList<>();
        for (HoldingValuation v : valued.valuations()) {
            OffsetDateTime heldSince = HoldingLotTracker.currentLotStart(
                    executionsByStock.getOrDefault(v.stockId(), List.of()));
            if (heldSince == null || heldSince.isAfter(threshold)) {
                continue; // 보유 이력이 없거나 N주 미만 보유
            }
            Stock stock = requireStock(stocks, v.stockId());
            items.add(new LongHeldStock(
                    stock.getSymbol(),
                    stock.getName(),
                    v.currency(),
                    FinancialDecimalFormatter.averagePrice(v.avgBuyPrice()),
                    FinancialDecimalFormatter.plain(v.lastPrice()),
                    FinancialDecimalFormatter.plain(nativeReturnRate(v)),
                    heldSince));
        }
        // 오래 보유한 순.
        items.sort(Comparator.comparing(LongHeldStock::heldSince));
        return items;
    }

    /** 내 보유 수익률(종목 통화 기준): (현재가 − 평단가)/평단가. 시세가 없으면 null. */
    private BigDecimal nativeReturnRate(HoldingValuation v) {
        if (v.lastPrice() == null) {
            return null;
        }
        return ReturnRateCalculator.calculate(v.lastPrice().subtract(v.avgBuyPrice()), v.avgBuyPrice());
    }

    private Stock requireStock(Map<Long, Stock> stocks, Long stockId) {
        Stock stock = stocks.get(stockId);
        if (stock == null) {
            throw new BusinessException(
                    ErrorCode.STOCK_NOT_FOUND, "보유 종목 stockId=" + stockId + " 의 종목 마스터가 없습니다");
        }
        return stock;
    }
}
