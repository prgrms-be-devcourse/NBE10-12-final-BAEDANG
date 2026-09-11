package com.baedang.market.repository;

import com.baedang.market.entity.QuoteSnapshot;
import com.baedang.stock.entity.MarketCountry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;

@Repository
public class QuoteSnapshotBatchRepository {

    // 가격과 기준가 거래일을 원자적으로 저장합니다. 역순 응답은 무시하고 상하한가는 보존합니다.
    private static final String PRICE_UPSERT_SQL = """
            INSERT INTO quote_snapshot (stock_id, last_price, currency, quote_at, collected_at, prev_close_date, prev_close)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (stock_id) DO UPDATE SET
                last_price = EXCLUDED.last_price, currency = EXCLUDED.currency,
                quote_at = EXCLUDED.quote_at, collected_at = EXCLUDED.collected_at,
                prev_close_date = CASE WHEN EXCLUDED.prev_close_date IS NOT NULL THEN EXCLUDED.prev_close_date
                    WHEN (quote_snapshot.quote_at AT TIME ZONE ?)::date = (EXCLUDED.quote_at AT TIME ZONE ?)::date THEN quote_snapshot.prev_close_date END,
                prev_close = CASE WHEN EXCLUDED.prev_close_date IS NOT NULL THEN EXCLUDED.prev_close
                    WHEN (quote_snapshot.quote_at AT TIME ZONE ?)::date = (EXCLUDED.quote_at AT TIME ZONE ?)::date THEN quote_snapshot.prev_close END
            WHERE EXCLUDED.quote_at > quote_snapshot.quote_at
               OR (EXCLUDED.quote_at = quote_snapshot.quote_at
                   AND EXCLUDED.collected_at > quote_snapshot.collected_at)
            """;

    private final JdbcTemplate jdbcTemplate;

    public QuoteSnapshotBatchRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public int savePrices(List<QuoteSnapshot> snapshots, MarketCountry country) {
        if (snapshots.isEmpty()) return 0;
        int[][] results = jdbcTemplate.batchUpdate(PRICE_UPSERT_SQL, snapshots, 200, (statement, snapshot) -> {
            statement.setLong(1, snapshot.getStockId());
            statement.setBigDecimal(2, snapshot.getLastPrice());
            statement.setString(3, snapshot.getCurrency());
            statement.setObject(4, snapshot.getQuoteAt());
            statement.setObject(5, snapshot.getCollectedAt());
            statement.setObject(6, snapshot.getPrevCloseDate());
            statement.setBigDecimal(7, snapshot.getPrevClose());
            for (int parameter = 8; parameter <= 11; parameter++) {
                statement.setString(parameter, country.zoneId().getId());
            }
        });
        int updated = 0;
        for (int[] batch : results) {
            for (int count : batch) updated += count == Statement.SUCCESS_NO_INFO ? 1 : Math.max(0, count);
        }
        return updated;
    }

    /** 같은 거래일의 새 시세가 들어와도 기준가만 복구하며 가격과 수집 시각은 보존한다. */
    @Transactional
    public int repairReference(QuoteSnapshot expected, MarketCountry country, LocalDate referenceDate, BigDecimal close) {
        return jdbcTemplate.update("""
                UPDATE quote_snapshot SET prev_close_date = ?, prev_close = ?
                WHERE stock_id = ? AND (quote_at AT TIME ZONE ?)::date = ?
                  AND (prev_close_date IS DISTINCT FROM ? OR prev_close IS DISTINCT FROM ?)
                """, referenceDate, close, expected.getStockId(), country.zoneId().getId(),
                expected.getQuoteAt().atZoneSameInstant(country.zoneId()).toLocalDate(), referenceDate, close);
    }

    /** 조회했던 스냅샷이 그대로일 때만 기존 장외 시세를 검증된 종가로 교체한다. */
    @Transactional
    public int saveRecoveredClose(QuoteSnapshot candidate, QuoteSnapshot expected) {
        if (expected == null) {
            return jdbcTemplate.update("""
                    INSERT INTO quote_snapshot(stock_id,last_price,currency,quote_at,collected_at,prev_close_date,prev_close)
                    VALUES (?,?,?,?,?,?,?) ON CONFLICT (stock_id) DO NOTHING
                    """, candidate.getStockId(), candidate.getLastPrice(), candidate.getCurrency(), candidate.getQuoteAt(),
                    candidate.getCollectedAt(), candidate.getPrevCloseDate(), candidate.getPrevClose());
        }
        return jdbcTemplate.update("""
                UPDATE quote_snapshot SET last_price=?, currency=?, quote_at=?, collected_at=?, prev_close_date=?, prev_close=?
                WHERE stock_id=? AND quote_at=? AND collected_at=? AND last_price=?
                  AND prev_close_date IS NOT DISTINCT FROM ?
                """, candidate.getLastPrice(), candidate.getCurrency(), candidate.getQuoteAt(), candidate.getCollectedAt(),
                candidate.getPrevCloseDate(), candidate.getPrevClose(), candidate.getStockId(), expected.getQuoteAt(),
                expected.getCollectedAt(), expected.getLastPrice(), expected.getPrevCloseDate());
    }

    @Transactional
    public int clearMismatchedReference(QuoteSnapshot expected, LocalDate requiredDate) {
        return jdbcTemplate.update("""
                UPDATE quote_snapshot SET prev_close_date=NULL, prev_close=NULL
                WHERE stock_id=? AND quote_at=? AND prev_close_date IS NOT NULL AND prev_close_date <> ?
                """, expected.getStockId(), expected.getQuoteAt(), requiredDate);
    }

}
