package com.baedang.report.service;

import com.baedang.report.support.AxisShares;
import com.baedang.report.support.FourWeekCostProfiler;
import com.baedang.report.support.InvestmentProfile;
import com.baedang.report.support.InvestmentTypeClassifier;
import com.baedang.stock.entity.Stock;
import com.baedang.stock.repository.StockRepository;
import com.baedang.trading.entity.Holding;
import com.baedang.trading.model.CostReplayEvent;
import com.baedang.trading.repository.TradeExecutionRepository;
import com.baedang.user.entity.Account;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 계좌의 투자 성향(MBTI) 유형을 <b>원가 4주 평균 비중</b>으로 판정하는 단일 지점(설계문서 §6.2).
 *
 * <p>개인 리포트({@code GET /api/reports/me})와 리더보드 배치가 모두 이 하나를 거친다 — 둘이
 * 각자 분류하면 같은 계좌의 유형이 리포트와 순위표에서 어긋난다(단일 평가 서비스와 같은 이유).
 * 체결을 재생해 4주 창의 원가 구성을 시점별 복원·평균하고, 컷오프로 판정한다.
 */
@Service
@Transactional(readOnly = true)
public class InvestmentTypeService {

    private final TradeExecutionRepository tradeExecutionRepository;
    private final StockRepository stockRepository;
    private final FourWeekCostProfiler costProfiler;
    private final InvestmentTypeClassifier classifier;
    private final int mbtiWindowWeeks;

    public InvestmentTypeService(TradeExecutionRepository tradeExecutionRepository,
                                 StockRepository stockRepository,
                                 FourWeekCostProfiler costProfiler,
                                 InvestmentTypeClassifier classifier,
                                 @Value("${report.mbti-window-weeks:4}") int mbtiWindowWeeks) {
        this.tradeExecutionRepository = tradeExecutionRepository;
        this.stockRepository = stockRepository;
        this.costProfiler = costProfiler;
        this.classifier = classifier;
        this.mbtiWindowWeeks = mbtiWindowWeeks;
    }

    /**
     * 주어진 계좌의 유형을 판정한다. 종목 수 게이트(최소 2종목)는 <b>현재 보유</b> 기준이라,
     * 호출자가 이미 조회한 보유를 넘겨 재조회를 피한다. 원가 재생에 등장한(창 안에서 전량 매도된)
     * 종목까지 종목 마스터를 한 번에 모은다.
     */
    public InvestmentProfile classify(Account account, List<Holding> currentHoldings, OffsetDateTime now) {
        List<CostReplayEvent> events = tradeExecutionRepository.findCostReplayEvents(account.getAccountId());

        Set<Long> stockIds = new HashSet<>();
        currentHoldings.forEach(h -> stockIds.add(h.getStockId()));
        events.forEach(e -> stockIds.add(e.stockId()));
        Map<Long, Stock> stocks = stockIds.isEmpty() ? Map.of()
                : stockRepository.findByStockIdIn(stockIds).stream()
                        .collect(Collectors.toMap(Stock::getStockId, Function.identity()));

        OffsetDateTime windowStart = laterOf(account.getOpenedAt(), now.minusWeeks(mbtiWindowWeeks));
        AxisShares avgShares = costProfiler.averageShares(events, stocks, windowStart, now);
        return classifier.classifyFromShares(avgShares, currentHoldings.size());
    }

    private static OffsetDateTime laterOf(OffsetDateTime a, OffsetDateTime b) {
        return a.isAfter(b) ? a : b;
    }
}
