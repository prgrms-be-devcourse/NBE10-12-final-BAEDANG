package com.baedang.report.support;

import com.baedang.report.support.InvestmentTypeClassifier.HoldingSlice;
import com.baedang.stock.entity.Stock;
import com.baedang.trading.entity.OrderSide;
import com.baedang.trading.model.CostReplayEvent;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 투자 MBTI 축 비중을 <b>4주 창의 원가(cost) 구성 시점별 평균</b>으로 계산한다(설계문서 §6.2).
 *
 * <p>Phase 1 은 현재 시가 평가비중 <b>단일 스냅샷</b>이었다. 값이 오른 종목이 유형을 지배하는
 * 문제를 피하려고, 체결을 재생해 창 안 매일의 <b>원가 구성</b>을 복원하고 그 날의 축 비중을 뽑아
 * 평균낸다. 시세·{@code daily_candle} 불필요(원가는 마크가 없다). USD 원가는 체결에 저장된 환율로
 * 이미 원화 환산돼 있다({@code grossAmountKrw}).
 *
 * <p>원가 구성은 평균원가법으로 복원한다: 매수는 원가·수량을 더하고, 매도는 남은 원가를 비례
 * 차감한다. 창 시작 시점의 구성을 세우려면 창 이전 체결도 재생해야 하므로, 입력 이벤트는 계좌의
 * 전체 이력(체결 시각 오름차순)이어야 한다.
 */
@Component
public class FourWeekCostProfiler {

    private final InvestmentTypeClassifier classifier;

    public FourWeekCostProfiler(InvestmentTypeClassifier classifier) {
        this.classifier = classifier;
    }

    /**
     * 창 {@code [windowStart, now]} 을 하루 간격으로 표본하여 축 비중을 평균낸다. 보유가 한 번도
     * 없었으면(모든 표본에서 원가 0) {@code null}. 판정이 반올림에 흔들리지 않게 <b>정밀 비중</b>을
     * 그대로 돌려주고(표시용 반올림은 {@link InvestmentTypeClassifier#classifyFromShares} 가 담당).
     */
    public AxisShares averageShares(List<CostReplayEvent> events, Map<Long, Stock> stocks,
                                    OffsetDateTime windowStart, OffsetDateTime now) {
        List<OffsetDateTime> samples = dailySamples(windowStart, now);

        BigDecimal domSum = BigDecimal.ZERO;
        BigDecimal indSum = BigDecimal.ZERO;
        BigDecimal top1Sum = BigDecimal.ZERO;
        BigDecimal aggSum = BigDecimal.ZERO;
        int counted = 0;

        for (OffsetDateTime at : samples) {
            List<HoldingSlice> slices = costBasisAt(events, stocks, at);
            AxisShares s = classifier.shares(slices);
            if (s == null) {
                continue; // 그 시점엔 보유가 없었다(원가 0).
            }
            domSum = domSum.add(s.domesticShare());
            indSum = indSum.add(s.individualShare());
            top1Sum = top1Sum.add(s.top1Share());
            aggSum = aggSum.add(s.aggressiveShare());
            counted++;
        }
        if (counted == 0) {
            return null;
        }
        BigDecimal n = BigDecimal.valueOf(counted);
        return new AxisShares(avg(domSum, n), avg(indSum, n), avg(top1Sum, n), avg(aggSum, n));
    }

    /** 창을 하루 간격으로 표본하고 끝점(now)을 반드시 포함한다. */
    private static List<OffsetDateTime> dailySamples(OffsetDateTime windowStart, OffsetDateTime now) {
        List<OffsetDateTime> samples = new ArrayList<>();
        OffsetDateTime cursor = windowStart.isAfter(now) ? now : windowStart;
        while (cursor.isBefore(now)) {
            samples.add(cursor);
            cursor = cursor.plusDays(1);
        }
        samples.add(now);
        return samples;
    }

    /** {@code at} 시점까지 체결을 평균원가법으로 재생해 종목별 원가를 슬라이스로 만든다. */
    private static List<HoldingSlice> costBasisAt(List<CostReplayEvent> events, Map<Long, Stock> stocks,
                                                  OffsetDateTime at) {
        Map<Long, BigDecimal> qty = new HashMap<>();
        Map<Long, BigDecimal> cost = new HashMap<>();
        for (CostReplayEvent e : events) {
            if (e.executedAt().isAfter(at)) {
                break; // 시각 오름차순 정렬이라 이후는 볼 필요 없다.
            }
            long id = e.stockId();
            BigDecimal q = qty.getOrDefault(id, BigDecimal.ZERO);
            BigDecimal c = cost.getOrDefault(id, BigDecimal.ZERO);
            if (e.side() == OrderSide.BUY) {
                qty.put(id, q.add(e.quantity()));
                cost.put(id, c.add(e.grossAmountKrw()));
            } else if (q.signum() > 0) {
                // 평균원가법: 남은 원가를 매도 비율만큼 비례 차감.
                BigDecimal sold = e.quantity().min(q);
                BigDecimal remainRatio = q.subtract(sold).divide(q, 10, RoundingMode.HALF_UP);
                qty.put(id, q.subtract(sold));
                cost.put(id, c.multiply(remainRatio));
            }
        }
        List<HoldingSlice> slices = new ArrayList<>();
        for (Map.Entry<Long, BigDecimal> entry : cost.entrySet()) {
            if (entry.getValue().signum() <= 0) {
                continue;
            }
            Stock stock = stocks.get(entry.getKey());
            if (stock == null) {
                continue; // 종목 마스터가 없으면 축을 알 수 없어 제외.
            }
            slices.add(new HoldingSlice(entry.getValue(), stock.getStockCategory(),
                    stock.getMarketCountry(), stock.getLeverageFactor()));
        }
        return slices;
    }

    private static BigDecimal avg(BigDecimal sum, BigDecimal count) {
        return sum.divide(count, 10, RoundingMode.HALF_UP);
    }
}
