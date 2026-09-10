package com.baedang.report.support;

import com.baedang.trading.entity.OrderSide;
import com.baedang.trading.model.HoldingReplayEvent;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 체결 이력을 재생해 현재 보유 lot 의 시작 시점(첫 매수)을 구하는 순수 계산기.
 *
 * <p>보유 종목은 원장 파생 집계라 취득일이 없다. "N주 이상 보유" 판정에는 지금 들고 있는
 * 물량이 <b>언제 열렸는지</b>가 필요한데, 이는 <b>개별 체결</b>을 체결 시각순으로 재생해
 * <b>수량이 0에서 양수로 마지막으로 바뀐 체결 시각</b>으로 구한다. 전량 매도로 0이 되면
 * lot 이 닫히고, 이후 재매수는 새 lot(취득 시점 리셋)이다.
 *
 * <p><b>주문 요약이 아니라 체결 단위로 재생한다.</b> 주문의 누적 {@code filledQuantity} 를
 * {@code orderedAt} 순으로 반영하면, 지정가 접수 뒤 기존 물량을 전량 매도하고 그 지정가가
 * 나중에 체결되는 경우 순서가 뒤바뀌어 이미 닫힌 lot 을 살려 낸다. 시각도 접수 시각이 아닌
 * 실제 체결 시각({@link HoldingReplayEvent#executedAt()})을 쓴다.
 */
public final class HoldingLotTracker {

    private HoldingLotTracker() {
    }

    /**
     * 체결 시각 오름차순으로 정렬된 <b>한 종목</b>의 체결에서 현재 lot 의 첫 매수 시각을 반환한다.
     * 현재 보유가 없거나(전부 매도됨) 체결 이력이 없으면 {@code null}.
     */
    public static OffsetDateTime currentLotStart(List<HoldingReplayEvent> chronologicalExecutions) {
        BigDecimal running = BigDecimal.ZERO;
        OffsetDateTime lotStart = null;

        for (HoldingReplayEvent execution : chronologicalExecutions) {
            if (execution.side() == OrderSide.BUY) {
                if (running.signum() == 0) {
                    lotStart = execution.executedAt();
                }
                running = running.add(execution.quantity());
            } else {
                running = running.subtract(execution.quantity());
                if (running.signum() <= 0) {
                    // 전량 매도 → lot 종료. 음수는 데이터 이상이라 0 으로 클램프.
                    running = BigDecimal.ZERO;
                    lotStart = null;
                }
            }
        }
        return lotStart;
    }
}
