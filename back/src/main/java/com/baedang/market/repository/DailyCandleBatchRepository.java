package com.baedang.market.repository;

import com.baedang.market.entity.DailyCandle;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.Types;
import java.util.List;

/**
 * {@code daily_candle} 테이블 대량 UPSERT 전용 저장소.
 *
 * <p>JPA {@code saveAll} 대신 {@link JdbcTemplate#batchUpdate}를 쓰는 이유:
 * JPA 는 엔티티 당 UPDATE/INSERT 를 분기하려고 SELECT 를 먼저 날립니다.
 * {@code ON CONFLICT DO UPDATE} 는 DB 가 직접 분기하므로 왕복이 절반으로 줄고,
 * 100 종목 1봉을 단일 배치로 처리합니다.
 *
 * <p>{@code MinuteCandleBatchRepository} 와 동일한 패턴입니다.
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

    /** 주어진 일봉 목록을 단일 배치로 UPSERT 합니다. */
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
