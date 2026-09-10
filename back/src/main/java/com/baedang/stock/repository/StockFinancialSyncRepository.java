package com.baedang.stock.repository;

import com.baedang.stock.entity.StockFinancialSync;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StockFinancialSyncRepository extends JpaRepository<StockFinancialSync, Long> {
}
