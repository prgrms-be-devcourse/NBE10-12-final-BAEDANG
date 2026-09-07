package com.baedang.orderbook.repository;

import com.baedang.orderbook.entity.OrderBookLevel;
import com.baedang.orderbook.entity.OrderBookSide;
import com.baedang.orderbook.entity.OrderBookVersion;
import com.baedang.orderbook.model.LockedOrderBook;
import com.baedang.orderbook.port.OrderBookExecutionStore;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Repository
public class JpaOrderBookExecutionStore implements OrderBookExecutionStore {

    private final OrderBookVersionRepository versionRepository;
    private final OrderBookLevelRepository levelRepository;

    public JpaOrderBookExecutionStore(
            OrderBookVersionRepository versionRepository,
            OrderBookLevelRepository levelRepository
    ) {
        this.versionRepository = versionRepository;
        this.levelRepository = levelRepository;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<LockedOrderBook> lockForExecution(
            Long stockId,
            Long expectedBookVersion,
            Long expectedRevision,
            OrderBookSide side
    ) {
        Objects.requireNonNull(stockId, "stockId는 필수입니다");
        Objects.requireNonNull(expectedBookVersion, "expectedBookVersion은 필수입니다");
        Objects.requireNonNull(expectedRevision, "expectedRevision은 필수입니다");
        Objects.requireNonNull(side, "side는 필수입니다");

        Optional<OrderBookVersion> version = versionRepository.findExpectedActiveForUpdate(
                stockId, expectedBookVersion, expectedRevision);
        if (version.isEmpty()) {
            return Optional.empty();
        }

        List<OrderBookLevel> levels = side == OrderBookSide.ASK
                ? levelRepository.findAskLevelsForUpdate(expectedBookVersion)
                : levelRepository.findBidLevelsForUpdate(expectedBookVersion);

        if (levels.size() != 10) {
            throw new IllegalStateException("활성 호가 버전은 방향별 10개 레벨이어야 합니다");
        }

        return Optional.of(new LockedOrderBook(version.orElseThrow(), levels));
    }
}
