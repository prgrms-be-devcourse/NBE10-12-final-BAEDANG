package com.baedang.stock.repository;

import com.baedang.stock.entity.MarketCountry;
import com.baedang.stock.entity.Stock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface StockRepository extends JpaRepository<Stock, Long> {

    Optional<Stock> findBySymbolIgnoreCaseAndMarketCountry(String symbol, MarketCountry marketCountry);

    /** 보유 종목들의 심볼·이름·통화를 한 번에 조회합니다 (마이페이지 보유 목록). */
    List<Stock> findByStockIdIn(Collection<Long> stockIds);


    @Query("""
            select s
            from Stock s
            where lower(replace(s.name, ' ', '')) like concat('%', :keyword, '%')
               or lower(replace(coalesce(s.englishName, ''), ' ', '')) like concat('%', :keyword, '%')
               or lower(s.symbol) like concat('%', :keyword, '%')
            """)
    List<Stock> searchByKeyword(@Param("keyword") String keyword);
}
