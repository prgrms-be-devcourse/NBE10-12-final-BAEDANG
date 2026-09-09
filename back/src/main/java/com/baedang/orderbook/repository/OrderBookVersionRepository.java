package com.baedang.orderbook.repository;

import com.baedang.orderbook.entity.OrderBookVersion;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface OrderBookVersionRepository extends JpaRepository<OrderBookVersion, Long> {

    /** publisher의 버전 교체가 consumer와 직렬화되는 잠금 조회 (설계서 §5.2 2단계). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select v from OrderBookVersion v where v.stockId = :stockId and v.isActive = true")
    Optional<OrderBookVersion> findActiveForUpdate(@Param("stockId") Long stockId);

    /** consumer(#122)가 기대하는 활성 버전과 revision이 맞을 때만 비관적 락으로 조회한다 (설계서 §6). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select v from OrderBookVersion v
             where v.stockId = :stockId
               and v.bookVersionId = :bookVersion
               and v.revision = :revision
               and v.isActive = true
            """)
    Optional<OrderBookVersion> findExpectedActiveForUpdate(
            @Param("stockId") Long stockId,
            @Param("bookVersion") Long bookVersion,
            @Param("revision") Long revision
    );

    Optional<OrderBookVersion> findByStockIdAndIsActiveTrue(Long stockId);

    long countByStockIdAndIsActiveTrue(Long stockId);

    /** 장 마감·종목 이탈 시 종료할 활성 버전을 시장별로 열거한다 (스케줄러, 설계서 §5.3). */
    @Query(value = """
            select v.stock_id
              from order_book_version v
              join stock s on s.stock_id = v.stock_id
             where v.is_active = true
               and s.market_country = :marketCountry
             order by v.stock_id
            """, nativeQuery = true)
    List<Long> findActiveStockIdsByMarketCountry(@Param("marketCountry") String marketCountry);

    /** 종료 후 retention이 지난 비활성 버전을 소비 여부와 무관하게 정리한다. */
    @Modifying
    @Query(value = """
            delete from order_book_version v
             where v.is_active = false
               and v.closed_at < :cutoff
            """, nativeQuery = true)
    int deleteExpiredClosed(@Param("cutoff") OffsetDateTime cutoff);
}
