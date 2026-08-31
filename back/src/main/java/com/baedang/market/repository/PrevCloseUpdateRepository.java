package com.baedang.market.repository;

import com.baedang.market.model.PrevCloseUpdateResult;
import com.baedang.stock.entity.MarketCountry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;

/**
 * 시장별 상위 종목의 전일 종가를 다음 순서로 일괄 갱신합니다.
 * 1. ranked_stocks: 요청 시장의 is_ranked=true 종목을 MATERIALIZED CTE로 고정해 이후 조회와 갱신이 같은 대상 집합을 사용하게 합니다.
 * 2. expected_closes: 직전 거래일과 정확히 일치하는 유효한 일봉 종가만 선택합니다.
 * 3. updated: 스냅샷의 prev_close만 갱신합니다. 일봉이 없으면 장 마감 후 남아 있는 last_price를 사용하고, 스냅샷 자체가 없으면 새로 만들지 않습니다.
 * 4. 마지막 SELECT: 전체 대상·실제 갱신·폴백 사용 건수를 반환합니다. 서비스는 이를 이용해 스냅샷 누락으로 건너뛴 건수까지 계산하고 운영 로그에 남깁니다.
 * 실시간 수집기가 관리하는 가격과 시각을 보호하기 위해 last_price, quote_at, collected_at은 변경하지 않습니다.
 */
@Repository
public class PrevCloseUpdateRepository {

    private static final String UPDATE_SQL = """
            WITH ranked_stocks AS MATERIALIZED (
                SELECT stock_id
                FROM stock
                WHERE market_country = ?
                  AND is_ranked = TRUE
            ),
            expected_closes AS (
                SELECT candle.stock_id,
                       candle.close_price
                FROM daily_candle candle
                JOIN ranked_stocks ranked ON ranked.stock_id = candle.stock_id
                WHERE candle.trade_date = ?
                  AND candle.close_price > 0
            ),
            updated AS (
                UPDATE quote_snapshot snapshot
                   SET prev_close = COALESCE(expected.close_price, snapshot.last_price)
                  FROM ranked_stocks ranked
                  LEFT JOIN expected_closes expected ON expected.stock_id = ranked.stock_id
                 WHERE snapshot.stock_id = ranked.stock_id
                   AND COALESCE(expected.close_price, snapshot.last_price) > 0
                RETURNING expected.close_price IS NULL AS used_fallback
            )
            SELECT (SELECT COUNT(*) FROM ranked_stocks) AS target_count,
                   COUNT(*) AS updated_count,
                   COUNT(*) FILTER (WHERE used_fallback) AS fallback_count
            FROM updated
            """;

    private final JdbcTemplate jdbcTemplate;

    public PrevCloseUpdateRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public PrevCloseUpdateResult updateForTradeDate(
            MarketCountry marketCountry,
            LocalDate expectedTradeDate
    ) {
        return execute(marketCountry, expectedTradeDate);
    }

    /** 시장 캘린더를 확인할 수 없을 때 모든 대상에 기존 last_price를 사용합니다. */
    public PrevCloseUpdateResult updateFromLastPrice(MarketCountry marketCountry) {
        return execute(marketCountry, null);
    }

    private PrevCloseUpdateResult execute(
            MarketCountry marketCountry,
            LocalDate expectedTradeDate
    ) {
        return jdbcTemplate.queryForObject(
                UPDATE_SQL,
                (resultSet, rowNumber) -> new PrevCloseUpdateResult(
                        resultSet.getInt("target_count"),
                        resultSet.getInt("updated_count"),
                        resultSet.getInt("fallback_count")
                ),
                marketCountry.name(),
                expectedTradeDate
        );
    }
}
