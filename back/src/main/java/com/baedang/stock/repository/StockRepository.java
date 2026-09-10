package com.baedang.stock.repository;

import com.baedang.stock.entity.MarketCountry;
import com.baedang.stock.entity.Stock;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface StockRepository extends JpaRepository<Stock, Long> {
    @Query("""
            select count(s) > 0 from Stock s
            where s.stockId = :stockId
              and s.listingStatus = com.baedang.stock.entity.ListingStatus.ACTIVE
              and (s.isRanked = true or exists (
                select o.orderId from TradeOrder o where o.stockId = s.stockId
                  and o.orderType = com.baedang.trading.entity.OrderType.LIMIT
                  and o.status in (com.baedang.trading.entity.OrderStatus.PENDING,
                                   com.baedang.trading.entity.OrderStatus.PARTIALLY_FILLED)
                  and o.expiresAt > :now and o.quantity > o.filledQuantity))
            """)
    boolean isQuoteTarget(@Param("stockId") Long stockId, @Param("now") OffsetDateTime now);

    /** 랭킹 또는 모든 사용자 중 활성 지정가 주문이 있는 종목만 매 페이지 재확인합니다. */
    @Query("""
            select s from Stock s
            where s.marketCountry = :country and s.stockId > :after
              and s.listingStatus = com.baedang.stock.entity.ListingStatus.ACTIVE
              and (s.isRanked = true or exists (
                select o.orderId from TradeOrder o
                where o.stockId = s.stockId
                  and o.orderType = com.baedang.trading.entity.OrderType.LIMIT
                  and o.status in (com.baedang.trading.entity.OrderStatus.PENDING,
                                   com.baedang.trading.entity.OrderStatus.PARTIALLY_FILLED)
                  and o.expiresAt > :now and o.quantity > o.filledQuantity))
            order by s.stockId
            """)
    List<Stock> findQuoteTargets(@Param("country") MarketCountry country,
            @Param("after") Long after,
            @Param("now") OffsetDateTime now, Pageable page);

    Optional<Stock> findBySymbolIgnoreCaseAndMarketCountry(String symbol, MarketCountry marketCountry);

    /**
     * 가상 호가 publisher가 종목별로 버전 교체를 직렬화할 때 씁니다 (설계서 §5.2 1단계).
     * 조회 전용이 아니라 {@code FOR UPDATE}이므로 반드시 트랜잭션 안에서 호출하세요.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Stock s where s.stockId = :stockId")
    Optional<Stock> findByIdForUpdate(@Param("stockId") Long stockId);

    /**
     * 보유 종목들의 심볼·이름·통화를 한 번에 조회합니다 (마이페이지 보유 목록).
     */
    List<Stock> findByStockIdIn(Collection<Long> stockIds);


    @Query("""
            select s
            from Stock s
            where lower(replace(s.name, ' ', '')) like concat('%', :keyword, '%')
               or lower(replace(coalesce(s.englishName, ''), ' ', '')) like concat('%', :keyword, '%')
               or lower(s.symbol) like concat('%', :keyword, '%')
            """)
    List<Stock> searchByKeyword(@Param("keyword") String keyword);

    // 첫 페이지
    @Query("""
            select s
            from Stock s
            where s.marketCountry = :marketCountry
              and s.isRanked = true
              and s.tradingAmount is not null
            order by s.tradingAmount desc, s.stockId desc
            """)
    List<Stock> findRankedByMarketCountry(
            @Param("marketCountry") MarketCountry marketCountry,
            Pageable pageable
    );

    // 다음 페이지
    @Query("""
            select s
            from Stock s
            where s.marketCountry = :marketCountry
              and s.isRanked = true
              and s.tradingAmount is not null
              and (
                    s.tradingAmount < :tradingAmount
                    or (
                        s.tradingAmount = :tradingAmount
                        and s.stockId < :stockId
                    )
              )
            order by s.tradingAmount desc, s.stockId desc
            """)
    List<Stock> findRankedAfterCursor(
            @Param("marketCountry") MarketCountry marketCountry,
            @Param("tradingAmount") BigDecimal tradingAmount,
            @Param("stockId") Long stockId,
            Pageable pageable
    );

    List<Stock> findByMarketCountryAndIsRankedTrue(MarketCountry marketCountry);

    List<Stock> findByMarketCountryAndSymbolIn(MarketCountry marketCountry, Collection<String> symbols);

    Page<Stock> findAllByOrderByStockIdAsc(Pageable pageable);
}
