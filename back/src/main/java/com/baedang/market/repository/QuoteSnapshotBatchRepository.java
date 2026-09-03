package com.baedang.market.repository;

import com.baedang.market.entity.QuoteSnapshot;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.sql.Types;
import java.util.List;

@Repository
public class QuoteSnapshotBatchRepository {

    private final JdbcTemplate jdbcTemplate;

    public QuoteSnapshotBatchRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private static final String UPSERT_SQL = """
            INSERT INTO quote_snapshot (
                stock_id, last_price, prev_close, currency, quote_at, collected_at
            ) VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT (stock_id) DO UPDATE SET
                prev_close = COALESCE(EXCLUDED.prev_close, quote_snapshot.prev_close)
            """;

    public void saveBulk(List<QuoteSnapshot> snapshots) {
        if (snapshots.isEmpty()) return;
        jdbcTemplate.batchUpdate(
                UPSERT_SQL,
                snapshots,
                snapshots.size(),
                (statement, snapshot) -> {
                    statement.setLong(1, snapshot.getStockId());
                    statement.setBigDecimal(2, snapshot.getLastPrice());
                    if (snapshot.getPrevClose() == null) {
                        statement.setNull(3, Types.NUMERIC);
                    } else {
                        statement.setBigDecimal(3, snapshot.getPrevClose());
                    }
                    statement.setString(4, snapshot.getCurrency());
                    statement.setTimestamp(5, Timestamp.from(snapshot.getQuoteAt().toInstant()));
                    statement.setTimestamp(6, Timestamp.from(snapshot.getCollectedAt().toInstant()));
                });
    }
}
