package com.baedang.stock.repository;

import com.baedang.stock.entity.StockLike;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface StockLikeRepository extends JpaRepository<StockLike, Long> {

    @Modifying
    @Query(value = """
            INSERT INTO stock_like (user_id, stock_id) VALUES (:userId, :stockId)
            ON CONFLICT (user_id, stock_id) DO NOTHING
            """, nativeQuery = true)
    void insertIfAbsent(@Param("userId") Long userId, @Param("stockId") Long stockId);

    Optional<StockLike> findByUserIdAndStockId(Long userId, Long stockId);

    @Modifying
    @Query("DELETE FROM StockLike l WHERE l.userId = :userId AND l.stockLikeId = :stockLikeId")
    void deleteByUserIdAndStockLikeId(@Param("userId") Long userId, @Param("stockLikeId") Long stockLikeId);

    @Query("""
            SELECT l FROM StockLike l
            WHERE l.userId = :userId
              AND (:cursorId IS NULL OR l.stockLikeId < :cursorId)
            ORDER BY l.stockLikeId DESC
            """)
    List<StockLike> findPage(@Param("userId") Long userId,
                             @Param("cursorId") Long cursorId,
                             Pageable pageable);
}
