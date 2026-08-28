package com.baedang.stock.repository;

import com.baedang.stock.entity.MarketCountry;
import com.baedang.stock.entity.Stock;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface StockRepository extends JpaRepository<Stock, Long> {

    Optional<Stock> findBySymbolIgnoreCaseAndMarketCountry(String symbol, MarketCountry marketCountry);

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
}
