package com.baedang.stock.repository;

import com.baedang.stock.entity.MarketCountry;
import com.baedang.stock.entity.Stock;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface StockRepository extends JpaRepository<Stock, Long> {

    Optional<Stock> findBySymbolIgnoreCaseAndMarketCountry(String symbol, MarketCountry marketCountry);
}
