package com.baedang.trading.scheduler;

import com.baedang.trading.entity.OrderSide;
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
    private final Map<Group, Candidate> pendingAcceptances = new HashMap<>();
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
        Candidate accepted = pendingAcceptances.remove(group);
        if (accepted != null) {
            Candidate after = current.after();
            if (after != null && precedes(group, accepted, after)) after = null;
            current = new Position(++sequence, current.bookVersion(), current.rate(), after);
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

    public synchronized void reset(Group group) {
        positions.remove(group);
        pendingAcceptances.remove(group);
    }

    public synchronized void retainGroups(Set<Group> activeGroups) {
        positions.keySet().retainAll(activeGroups);
        pendingAcceptances.keySet().retainAll(activeGroups);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public synchronized void onAccepted(LimitOrderAcceptedEvent event) {
        Group group = new Group(event.stockId(), event.side());
        if (!positions.containsKey(group)) return;
        // 선정 중/실행 중인 한 건은 중단하지 않습니다. 다음 선정 경계에서만 알림을 반영합니다.
        // 커서 재평가에는 가장 앞선 접수 한 건이면 충분하며, 실제 후보는 DB에서 조회합니다.
        Candidate accepted = new Candidate(event.orderId(), event.price(), event.orderedAt());
        pendingAcceptances.merge(group, accepted,
                (previous, added) -> precedes(group, added, previous) ? added : previous);
    }

    private boolean precedes(Group group, Candidate candidate, Candidate other) {
        int priceOrder = candidate.price().compareTo(other.price());
        if (priceOrder != 0) {
            return group.side() == OrderSide.BUY ? priceOrder > 0 : priceOrder < 0;
        }
        int timeOrder = candidate.orderedAt().toInstant().compareTo(other.orderedAt().toInstant());
        return timeOrder < 0 || (timeOrder == 0 && candidate.orderId() < other.orderId());
    }
}
