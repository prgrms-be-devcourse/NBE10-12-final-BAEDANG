package com.baedang.orderbook.service;

import com.baedang.global.error.BusinessException;
import com.baedang.global.error.ErrorCode;
import com.baedang.orderbook.config.OrderBookProperties;
import com.baedang.orderbook.entity.OrderBookLevel;
import com.baedang.orderbook.entity.OrderBookVersion;
import com.baedang.orderbook.model.GeneratedOrderBook;
import com.baedang.orderbook.repository.OrderBookLevelRepository;
import com.baedang.orderbook.repository.OrderBookVersionRepository;
import com.baedang.stock.entity.Stock;
import com.baedang.stock.repository.StockRepository;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

/**
 * 종목별 가상 호가 버전 게시·종료 (설계서 §5.2).
 *
 * <p>한 종목 = 한 {@code REQUIRED} 트랜잭션이며, 락 순서는 {@code stock →
 * order_book_version}로 고정한다(consumer의 {@code account → trade_order →
 * version → level → holding}과 순환 대기가 없다 — publisher는 account·주문·
 * 보유를 절대 잠그지 않는다). 기존 활성 종료와 새 버전·최대 20개 레벨 INSERT가
 * 한 커밋에 함께 살아야 조회 중 "활성 버전 있는데 레벨 없음"이 생기지 않는다.
 *
 * <p>생성(pure generator)은 이 트랜잭션 <b>밖</b>에서 끝난 상태로 받아야 하며,
 * 이 서비스는 Toss를 호출하지 않는다.
 */
@Service
public class OrderBookPublicationService {

    private final StockRepository stockRepository;
    private final OrderBookVersionRepository versionRepository;
    private final OrderBookLevelRepository levelRepository;
    private final OrderBookProperties properties;
    private final Clock clock;
    private final EntityManager entityManager;

    public OrderBookPublicationService(
            StockRepository stockRepository,
            OrderBookVersionRepository versionRepository,
            OrderBookLevelRepository levelRepository,
            OrderBookProperties properties,
            Clock clock,
            EntityManager entityManager
    ) {
        this.stockRepository = stockRepository;
        this.versionRepository = versionRepository;
        this.levelRepository = levelRepository;
        this.properties = properties;
        this.clock = clock;
        this.entityManager = entityManager;
    }

    /**
     * 생성 결과를 새 활성 버전으로 게시한다. 실패(장 종료·stale·거래불가)하면
     * 기존 활성만 종료하고 {@code empty}를 반환한다 — 호출한 스케줄러가 로그로
     * 계속 넘기면 되고 예외가 아니다.
     */
    @Transactional
    public Optional<Long> publish(GeneratedOrderBook generated, Instant sessionValidUntil) {
        applyLockTimeout();
        Stock stock = stockRepository.findByIdForUpdate(generated.stockId())
                .orElseThrow(() -> new BusinessException(ErrorCode.STOCK_NOT_FOUND));
        OrderBookVersion active = versionRepository.findActiveForUpdate(stock.getStockId()).orElse(null);
        // 락 대기 중에 세션이 끝났을 수 있어 now를 락 이후에 다시 읽는다.
        Instant lockedAt = clock.instant();

        if (!stock.isTradable()
                || !lockedAt.isBefore(sessionValidUntil)
                || !isValidQuoteTime(generated.quoteAt(), lockedAt)) {
            if (active != null) active.close(lockedAt);
            return Optional.empty();
        }

        if (active != null) {
            active.close(lockedAt);
            // 부분 유니크 인덱스(stock_id WHERE is_active)가 새 INSERT와 충돌하지
            // 않도록 종료 상태를 이 트랜잭션 안에 반영한다.
            versionRepository.flush();
        }

        OrderBookVersion next = versionRepository.saveAndFlush(OrderBookVersion.open(generated));
        levelRepository.saveAll(OrderBookLevel.from(next, generated.levels()));
        return Optional.of(next.getBookVersionId());
    }

    /** 새 버전 없이 활성 버전만 종료한다(장 마감 열거, 생성 실패 종목용). */
    @Transactional
    public void closeActive(Long stockId) {
        applyLockTimeout();
        Stock stock = stockRepository.findByIdForUpdate(stockId)
                .orElseThrow(() -> new BusinessException(ErrorCode.STOCK_NOT_FOUND));
        versionRepository.findActiveForUpdate(stock.getStockId())
                .ifPresent(version -> version.close(clock.instant()));
    }

    /** 스케줄러 한 건이 공용 DB 락을 무기한 기다리지 않게 트랜잭션 안에서만 적용한다. */
    private void applyLockTimeout() {
        entityManager.createNativeQuery("SET LOCAL lock_timeout = '2s'").executeUpdate();
    }

    /** 설계서 §5.1 — 미래 시세와 maxQuoteAge 초과만拒绝. abs() 비교 금지. */
    private boolean isValidQuoteTime(Instant quoteAt, Instant now) {
        return !quoteAt.isAfter(now)
                && !quoteAt.isBefore(now.minus(properties.maxQuoteAge()));
    }
}
