package com.baedang.stock.repository;

import com.baedang.stock.entity.FinancialPeriodType;
import com.baedang.stock.entity.StockFinancialPeriod;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface StockFinancialPeriodRepository
        extends JpaRepository<StockFinancialPeriod, StockFinancialPeriod.Pk> {

    List<StockFinancialPeriod> findByStockIdAndPeriodTypeOrderByStatementYearMonthDesc(
            Long stockId, FinancialPeriodType periodType);
}
