package com.baedang.orderbook.repository;

import com.baedang.orderbook.entity.OrderBookLevel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface OrderBookLevelRepository extends JpaRepository<OrderBookLevel, Long> {

    long countByBookVersion_BookVersionId(Long bookVersionId);

    @Query(value = """
            select v.book_version_id as bookVersion,
                   v.revision as revision,
                   v.base_price as basePrice,
                   v.currency as currency,
                   v.quote_at as quoteAt,
                   v.generated_at as generatedAt,
                   v.policy_version as policyVersion,
                   v.seed as seed,
                   l.side as side,
                   l.level_depth as levelDepth,
                   l.price as price,
                   l.remaining_quantity as remainingQuantity
              from order_book_version v
              join order_book_level l on l.book_version_id = v.book_version_id
             where v.stock_id = :stockId
               and v.is_active = true
             order by case when l.side = 'ASK' then 0 else 1 end,
                      case when l.side = 'ASK' then l.price end asc,
                      case when l.side = 'BID' then l.price end desc,
                      l.level_depth asc
            """, nativeQuery = true)
    List<OrderBookRowProjection> findActiveSnapshotRows(@Param("stockId") Long stockId);
}
