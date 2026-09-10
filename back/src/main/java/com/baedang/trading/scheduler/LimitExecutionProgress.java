package com.baedang.trading.scheduler;

import com.baedang.trading.model.LimitExecutionPreparation;
import com.baedang.trading.model.LimitOrderAcceptedEvent;
import com.baedang.trading.repository.LimitExecutionCandidateRepository.Candidate;
import com.baedang.trading.repository.LimitExecutionCandidateRepository.Group;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** 단일 인스턴스용 진행 위치. 주문별 캐시가 아니며, 전체 그룹 순회 후 비활성 그룹을 제거합니다. */
@Component
public class LimitExecutionProgress {
    public record Position(long token, Long bookVersion, BigDecimal rate, Candidate after) {}
    private final Map<Group, Position> positions = new HashMap<>();
    private long sequence;

    public synchronized Position position(Group group, LimitExecutionPreparation market) {
        if (!market.available() || !group.stockId().equals(market.stockId()) || group.side() != market.side()) {
            throw new IllegalArgumentException("그룹과 후보 선정 근거가 일치해야 합니다");
        }
        Position current = positions.get(group);
        if (current == null || !current.bookVersion().equals(market.book().version())
                || current.rate().compareTo(market.context().executionRate()) != 0) {
            current = new Position(++sequence, market.book().version(), market.context().executionRate(), null);
            positions.put(group, current);
        }
        return current;
    }

    public synchronized boolean isCurrent(Group group, Position selected) {
        Position current = positions.get(group);
        return current != null && current.token() == selected.token();
    }

    public synchronized boolean advance(Group group, Position selected, Candidate after) {
        if (!isCurrent(group, selected)) return false;
        positions.put(group, new Position(selected.token(), selected.bookVersion(), selected.rate(), after));
        return true;
    }

    public synchronized void reset(Group group) { positions.remove(group); }

    public synchronized void retainGroups(Set<Group> activeGroups) { positions.keySet().retainAll(activeGroups); }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public synchronized void onAccepted(LimitOrderAcceptedEvent event) {
        Group group = new Group(event.stockId(), event.side());
        Position current = positions.get(group);
        if (current == null) return;
        Candidate after = current.after();
        // 이미 지난 위치보다 선순위일 때만 처음으로 돌아갑니다. 후순위 접수는 읽어 둔 페이지만 무효화합니다.
        if (after != null) {
            int priceOrder = event.price().compareTo(after.price());
            boolean precedes = event.side() == com.baedang.trading.entity.OrderSide.BUY ? priceOrder > 0 : priceOrder < 0;
            if (priceOrder == 0) {
                int timeOrder = event.orderedAt().toInstant().compareTo(after.orderedAt().toInstant());
                precedes = timeOrder < 0 || (timeOrder == 0 && event.orderId() < after.orderId());
            }
            if (precedes) after = null;
        }
        positions.put(group, new Position(++sequence, current.bookVersion(), current.rate(), after));
    }
}
