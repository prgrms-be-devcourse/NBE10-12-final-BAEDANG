package com.baedang.market.repository;

import com.baedang.market.entity.MinuteCandle;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Types;
import java.util.List;

@Repository
public class MinuteCandleBatchRepository {

    private static final String UPSERT_SQL = """
            INSERT INTO minute_candle (
                stock_id, candle_at, open_price, high_price, low_price, close_price, volume
            ) VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (stock_id, candle_at) DO UPDATE SET
                open_price = EXCLUDED.open_price,
                high_price = EXCLUDED.high_price,
                low_price = EXCLUDED.low_price,
                close_price = EXCLUDED.close_price,
                volume = EXCLUDED.volume
            """;

    private final JdbcTemplate jdbcTemplate;

    public MinuteCandleBatchRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void upsertAll(List<MinuteCandle> candles) {
        if (candles.isEmpty()) return;
        jdbcTemplate.batchUpdate(
                UPSERT_SQL,
                candles,
                candles.size(),
                (statement, candle) -> {
                    statement.setLong(1, candle.getStockId());
                    statement.setObject(2, candle.getCandleAt());
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
