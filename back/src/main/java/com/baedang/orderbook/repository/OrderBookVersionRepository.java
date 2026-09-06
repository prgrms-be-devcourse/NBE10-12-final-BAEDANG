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

    /**
     * 미소비 종료 버전 정리 (설계서 §3.3): 활성 아님 + revision 0 + retention 경과만 대상이고,
     * 체결이 참조하는 레벨의 버전은 제외한다. 체결 출처는 PR #127 계약상
     * {@code trade_execution.book_level_id}라 레벨을 경유해 판정한다 — {@code book_version_id}를
     * 체결 행에 중복 저장하지 않는다.
     */
    @Modifying
    @Query(value = """
            delete from order_book_version v
             where v.is_active = false
               and v.revision = 0
               and v.closed_at < :cutoff
               and not exists (
                   select 1
                     from trade_execution e
                     join order_book_level l on l.level_id = e.book_level_id
                    where l.book_version_id = v.book_version_id
               )
            """, nativeQuery = true)
    int deleteExpiredUnconsumed(@Param("cutoff") OffsetDateTime cutoff);
}
