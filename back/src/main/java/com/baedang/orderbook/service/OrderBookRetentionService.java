package com.baedang.orderbook.service;

import com.baedang.orderbook.config.OrderBookProperties;
import com.baedang.orderbook.repository.OrderBookVersionRepository;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.ZoneOffset;

/**
 * 종료 후 retention이 지난 호가 버전을 정리한다.
 *
 * <p>활성 버전은 보존하고, 비활성 버전은 소비 여부와 무관하게 삭제한다. 체결 가격·수량·
 * 정산 금액은 {@code trade_execution}이 영구 보존하며 {@code book_level_id}는 FK 없는
 * 추적 값이다. 버전 삭제 시 산하 레벨은 함께 삭제된다.
 */
@Service
public class OrderBookRetentionService {

    private final OrderBookVersionRepository versionRepository;
    private final OrderBookProperties properties;
    private final Clock clock;
    private final EntityManager entityManager;

    public OrderBookRetentionService(
            OrderBookVersionRepository versionRepository,
            OrderBookProperties properties,
            Clock clock,
            EntityManager entityManager
    ) {
        this.versionRepository = versionRepository;
        this.properties = properties;
        this.clock = clock;
        this.entityManager = entityManager;
    }

    @Transactional
    public int deleteExpiredClosed() {
        entityManager.createNativeQuery("SET LOCAL lock_timeout = '2s'").executeUpdate();
        var cutoff = clock.instant()
                .minus(properties.closedVersionRetention())
                .atOffset(ZoneOffset.UTC);
        return versionRepository.deleteExpiredClosed(cutoff);
    }
}
