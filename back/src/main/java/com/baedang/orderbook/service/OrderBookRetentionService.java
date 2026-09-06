package com.baedang.orderbook.service;

import com.baedang.orderbook.config.OrderBookProperties;
import com.baedang.orderbook.repository.OrderBookVersionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.ZoneOffset;

/**
 * 미소비 종료 호가 버전 정리 (설계서 §3.3).
 *
 * <p>{@code revision = 0}이고 종료 후 {@code unconsumedRetention}이 지난 버전만
 * 삭제 대상이다. 활성 버전과 소비된({@code revision > 0}) 종료 버전은 감사 근거라
 * 건드리지 않고, 체결이 참조하는 레벨은 FK {@code ON DELETE RESTRICT}가 원천
 * 차단한다. 3초 갱신이 만드는 미사용 이력을 유한하게 유지하는 게 목적.
 */
@Service
public class OrderBookRetentionService {

    private final OrderBookVersionRepository versionRepository;
    private final OrderBookProperties properties;
    private final Clock clock;

    public OrderBookRetentionService(
            OrderBookVersionRepository versionRepository,
            OrderBookProperties properties,
            Clock clock
    ) {
        this.versionRepository = versionRepository;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public int deleteExpiredUnconsumed() {
        var cutoff = clock.instant()
                .minus(properties.unconsumedRetention())
                .atOffset(ZoneOffset.UTC);
        return versionRepository.deleteExpiredUnconsumed(cutoff);
    }
}
