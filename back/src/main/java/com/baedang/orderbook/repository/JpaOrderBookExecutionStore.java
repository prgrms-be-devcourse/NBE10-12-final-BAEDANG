package com.baedang.orderbook.repository;

import com.baedang.orderbook.entity.OrderBookLevel;
import com.baedang.orderbook.entity.OrderBookSide;
import com.baedang.orderbook.entity.OrderBookVersion;
import com.baedang.orderbook.model.LockedOrderBook;
import com.baedang.orderbook.model.OrderBookPriceOrderValidator;
import com.baedang.orderbook.port.OrderBookExecutionStore;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Repository
public class JpaOrderBookExecutionStore implements OrderBookExecutionStore {

    private static final BigDecimal MIN_US_ORDER_BOOK_PRICE = new BigDecimal("0.01");

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

        OrderBookVersion activeVersion = version.orElseThrow();
        List<OrderBookLevel> levels = side == OrderBookSide.ASK
                ? levelRepository.findAskLevelsForUpdate(expectedBookVersion)
                : levelRepository.findBidLevelsForUpdate(expectedBookVersion);

        boolean validDepth = levels.size() == 10
                || (side == OrderBookSide.BID && "USD".equals(activeVersion.getCurrency())
                    && !levels.isEmpty() && levels.size() < 10
                    && levels.getLast().getPrice().compareTo(MIN_US_ORDER_BOOK_PRICE) == 0);
        if (!validDepth || !hasSequentialDepths(levels)
                || !OrderBookPriceOrderValidator.isStrict(levels, OrderBookLevel::getPrice, side)) {
            throw new IllegalStateException("활성 호가 버전의 레벨 깊이 또는 가격 순서가 올바르지 않습니다");
        }

        return Optional.of(new LockedOrderBook(activeVersion, levels));
    }

    private boolean hasSequentialDepths(List<OrderBookLevel> levels) {
        for (int i = 0; i < levels.size(); i++) {
            if (levels.get(i).getLevelDepth() != i + 1) return false;
        }
        return true;
    }

}
