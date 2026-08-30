package com.baedang.market.repository;

import com.baedang.market.entity.DailyCandle;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.Types;
import java.util.List;

/**
 * daily_candle 테이블 배치 UPSERT 저장소.
 * JdbcTemplate batchUpdate 기반 ON CONFLICT DO UPDATE를 수행합니다.
 */
@Repository
public class DailyCandleBatchRepository {

    private static final String UPSERT_SQL = """
            INSERT INTO daily_candle (
                stock_id, trade_date, open_price, high_price, low_price, close_price, volume
            ) VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (stock_id, trade_date) DO UPDATE SET
                open_price  = EXCLUDED.open_price,
                high_price  = EXCLUDED.high_price,
                low_price   = EXCLUDED.low_price,
                close_price = EXCLUDED.close_price,
                volume      = EXCLUDED.volume
            """;

    private final JdbcTemplate jdbcTemplate;

    public DailyCandleBatchRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 일봉 목록을 단일 배치로 UPSERT 합니다. */
    public void upsertAll(List<DailyCandle> candles) {
        if (candles.isEmpty()) return;
        jdbcTemplate.batchUpdate(
                UPSERT_SQL,
                candles,
                candles.size(),
                (statement, candle) -> {
                    statement.setLong(1, candle.getStockId());
                    statement.setDate(2, Date.valueOf(candle.getTradeDate()));
                    statement.setBigDecimal(3, candle.getOpenPrice());
                    statement.setBigDecimal(4, candle.getHighPrice());
                    statement.setBigDecimal(5, candle.getLowPrice());
                    statement.setBigDecimal(6, candle.getClosePrice());
                    if (candle.getVolume() == null) {
                        statement.setNull(7, Types.NUMERIC);
                    } else {
                        statement.setBigDecimal(7, candle.getVolume());
                    }
                });
    }
}
