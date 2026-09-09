package com.baedang.report.support;

import com.baedang.trading.entity.OrderSide;
import com.baedang.trading.entity.TradeOrder;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 체결 이력을 재생해 현재 보유 lot 의 시작 시점(첫 매수)을 구하는 순수 계산기.
 *
 * <p>보유 종목은 원장 파생 집계라 취득일이 없다. "N주 이상 보유" 판정에는 지금 들고 있는
 * 물량이 <b>언제 열렸는지</b>가 필요한데, 이는 체결 주문을 시간순으로 재생해 <b>수량이
 * 0에서 양수로 마지막으로 바뀐 매수 시각</b>으로 구한다. 전량 매도로 0이 되면 lot 이
 * 닫히고, 이후 재매수는 새 lot(취득 시점 리셋)이다.
 *
 * <p>시각은 주문 {@code orderedAt} 을 쓴다 — 시장가는 접수=체결이라 정확하고, 지정가는
 * 접수 시각이라 체결과 미세한 차이가 있으나 "주 단위 보유기간" 표시엔 충분하다.
 */
public final class HoldingLotTracker {

    private HoldingLotTracker() {
    }

    /**
     * 시간 오름차순으로 정렬된 <b>한 종목</b>의 체결 주문에서 현재 lot 의 첫 매수 시각을 반환한다.
     * 현재 보유가 없거나(전부 매도됨) 체결 이력이 없으면 {@code null}.
     */
    public static OffsetDateTime currentLotStart(List<TradeOrder> chronologicalFills) {
        BigDecimal running = BigDecimal.ZERO;
        OffsetDateTime lotStart = null;

        for (TradeOrder order : chronologicalFills) {
            BigDecimal filled = order.getFilledQuantity();
            if (order.getSide() == OrderSide.BUY) {
                if (running.signum() == 0) {
                    lotStart = order.getOrderedAt();
                }
                running = running.add(filled);
            } else {
                running = running.subtract(filled);
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
