package com.baedang.market.repository;

import com.baedang.market.entity.QuoteSnapshot;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Statement;
import java.sql.Types;
import java.util.List;

@Repository
public class QuoteSnapshotBatchRepository {

    // 현재가만 갱신합니다. 역순 응답은 무시하고 전일 종가/상하한가를 보존합니다.
    private static final String PRICE_UPSERT_SQL = """
            INSERT INTO quote_snapshot (stock_id, last_price, currency, quote_at, collected_at)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (stock_id) DO UPDATE SET
                last_price = EXCLUDED.last_price, currency = EXCLUDED.currency,
                quote_at = EXCLUDED.quote_at, collected_at = EXCLUDED.collected_at
            WHERE EXCLUDED.quote_at > quote_snapshot.quote_at
               OR (EXCLUDED.quote_at = quote_snapshot.quote_at
                   AND EXCLUDED.collected_at > quote_snapshot.collected_at)
            """;

    private final JdbcTemplate jdbcTemplate;

    public QuoteSnapshotBatchRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public int savePrices(List<QuoteSnapshot> snapshots) {
        if (snapshots.isEmpty()) return 0;
        int[][] results = jdbcTemplate.batchUpdate(PRICE_UPSERT_SQL, snapshots, 200, (statement, snapshot) -> {
            statement.setLong(1, snapshot.getStockId());
            statement.setBigDecimal(2, snapshot.getLastPrice());
            statement.setString(3, snapshot.getCurrency());
            statement.setObject(4, snapshot.getQuoteAt());
            statement.setObject(5, snapshot.getCollectedAt());
        });
        int updated = 0;
        for (int[] batch : results) {
            for (int count : batch) updated += count == Statement.SUCCESS_NO_INFO ? 1 : Math.max(0, count);
        }
        return updated;
    }

    public void updatePrevClose(Long stockId, BigDecimal price) {
        jdbcTemplate.update("UPDATE quote_snapshot SET prev_close = ? WHERE stock_id = ?", price, stockId);
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
                    statement.setObject(5, snapshot.getQuoteAt());
                    statement.setObject(6, snapshot.getCollectedAt());
                });
    }
}
