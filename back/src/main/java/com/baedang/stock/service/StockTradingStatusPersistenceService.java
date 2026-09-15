package com.baedang.stock.service;

import com.baedang.stock.entity.MarketCountry;
import com.baedang.stock.port.StockInfo;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StockTradingStatusPersistenceService {
    private final JdbcTemplate jdbc;

    public StockTradingStatusPersistenceService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 랭킹·이름·경고를 덮지 않고 실제 제공된 거래 상태만 갱신합니다. 미국의 국내 전용 플래그는 보존합니다. */
    @Transactional
    public void update(Long stockId, MarketCountry country, StockInfo info) {
        StockInfo.KrMarketDetail kr = info.krMarketDetail();
        jdbc.update("""
                UPDATE stock SET listing_status = ?,
                    is_suspended = COALESCE(?, is_suspended),
                    is_liquidation = COALESCE(?, is_liquidation)
                WHERE stock_id = ?
                """, info.status(), country == MarketCountry.KR ? kr.krxTradingSuspended() : null,
                country == MarketCountry.KR ? kr.liquidationTrading() : null, stockId);
    }
}
