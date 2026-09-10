package com.baedang.stock.repository;

import com.baedang.stock.entity.StockIndustry;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StockIndustryRepository extends JpaRepository<StockIndustry, Long> {
}
