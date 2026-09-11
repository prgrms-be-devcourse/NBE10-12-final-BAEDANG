package com.baedang.market.repository;

import com.baedang.market.port.PriceLimits;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

@Repository
public class PriceLimitRepository {
    private final JdbcTemplate jdbc;

    public PriceLimitRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** 시세 행을 만들거나 현재가를 덮어쓰지 않고, 오래된 응답의 역순 저장을 차단합니다. */
    @Transactional
    public boolean save(Long stockId, LocalDate date, PriceLimits limits) {
        return jdbc.update("""
                UPDATE quote_snapshot q
                SET upper_limit = ?, lower_limit = ?, price_limit_date = ?
                WHERE q.stock_id = ? AND q.currency = 'KRW'
                  AND EXISTS (SELECT 1 FROM stock s WHERE s.stock_id = q.stock_id AND s.market_country = 'KR')
                  AND (q.price_limit_date IS NULL OR q.price_limit_date < ?)
                """, limits.upperLimit(), limits.lowerLimit(), date, stockId, date) > 0;
    }
}
