package com.baedang.market.repository;

import com.baedang.stock.model.CandleQueryInterval;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 연속 집계 뷰(candle_5m · candle_10m · candle_1w) 조회.
 *
 * <p>뷰에는 INSERT/UPDATE 가 되지 않으므로 엔티티로 만들지 않고 조회 결과만 record 로 받는다.
 * 봉 시작 시각과 OHLCV 를 뷰가 이미 계산해 주므로 자바에서 다시 묶지 않는다.
 */
@Repository
public class CandleAggregateRepository {

    public record AggregateCandle(
            OffsetDateTime bucket,
            BigDecimal openPrice,
            BigDecimal highPrice,
            BigDecimal lowPrice,
            BigDecimal closePrice,
            BigDecimal volume
    ) {
    }

    private static final String FIVE_MINUTES = sql("candle_5m", "bucket");
    private static final String TEN_MINUTES = sql("candle_10m", "bucket");
    // candle_1w 의 bucket 은 daily_candle.trade_date(DATE)에서 나온다.
    // 일봉 응답과 같은 시각을 주도록 KST 자정으로 맞춘다. 서버 타임존에 기대지 않는다.
    private static final String ONE_WEEK =
            sql("candle_1w", "(bucket::timestamp AT TIME ZONE 'Asia/Seoul')");

    private final JdbcClient jdbcClient;

    public CandleAggregateRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    /** 최신 봉부터 count 개. 데이터가 모자라면 있는 만큼만 돌려준다. */
    public List<AggregateCandle> findLatest(CandleQueryInterval interval, Long stockId, int count) {
        String sql = switch (interval) {
            case FIVE_MINUTES -> FIVE_MINUTES;
            case TEN_MINUTES -> TEN_MINUTES;
            case ONE_WEEK -> ONE_WEEK;
            case ONE_MINUTE, ONE_DAY -> throw new IllegalArgumentException(
                    "집계 뷰가 없는 주기: " + interval.value());
        };
        return jdbcClient.sql(sql)
                .param("stockId", stockId)
                .param("count", count)
                .query(AggregateCandle.class)
                .list();
    }

    /**
     * 주봉 집계 뷰를 즉시 갱신한다. 온디맨드 일봉 백필 직후 호출된다.
     *
     * <p>구간을 좁히지 않는 이유: 새로고침은 <b>창 안에 완전히 들어온 버킷만</b> 계산한다.
     * 백필한 거래일 범위로 창을 잡으면 진행 중인 주(그리고 시작이 잘린 주)의 봉이
     * 만들어지지 않는다 — 정작 화면 맨 앞에 오는 최신 봉이다.
     *
     * <p>비용은 창 넓이가 아니라 그 안의 무효화된 양에 비례하므로, 바뀐 게 없으면
     * 전 구간을 지정해도 "already up-to-date" 로 끝난다.
     *
     * <p>!! {@code refresh_continuous_aggregate} 는 트랜잭션 블록 안에서 실행할 수 없다
     * (새로고침이 트랜잭션 두 개에 걸쳐 돌기 때문). {@code Propagation.NEVER} 로 강제한다 —
     * 호출부에 트랜잭션이 있으면 DB 까지 가지 않고 여기서 막힌다.
     */
    @Transactional(propagation = Propagation.NEVER)
    public void refreshWeekly() {
        jdbcClient.sql("CALL refresh_continuous_aggregate('candle_1w', NULL, NULL)")
                .update();
    }

    private static String sql(String view, String bucketExpression) {
        return """
                SELECT %s AS bucket,
                       open_price, high_price, low_price, close_price, volume
                  FROM %s
                 WHERE stock_id = :stockId
                 ORDER BY bucket DESC
                 LIMIT :count
                """.formatted(bucketExpression, view);
    }
}
