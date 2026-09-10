package com.baedang.trading.repository;

import com.baedang.trading.entity.OrderSide;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** 신규 주문을 ID 상한으로 제외하지 않고 종목·방향과 가격/시간 keyset으로 탐색합니다. */
@Repository
public class LimitExecutionCandidateRepository {
    private static final String ACTIVE = " order_type = 'LIMIT' AND status IN ('PENDING', 'PARTIALLY_FILLED')"
            + " AND quantity > filled_quantity AND expires_at > ? ";
    private final JdbcTemplate jdbc;

    public LimitExecutionCandidateRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record Group(Long stockId, OrderSide side) {}
    public record Candidate(Long orderId, BigDecimal price, OffsetDateTime orderedAt) {}

    public Optional<Group> nextGroup(Group after, OffsetDateTime now) {
        String cursor = after == null ? "" : " AND (stock_id, side) > (?, ?) ";
        List<Object> args = new ArrayList<>(List.of(now));
        if (after != null) { args.add(after.stockId()); args.add(after.side().name()); }
        return jdbc.query("SELECT stock_id, side FROM trade_order WHERE " + ACTIVE + cursor
                + " GROUP BY stock_id, side ORDER BY stock_id, side LIMIT 1",
                (row, index) -> new Group(row.getLong("stock_id"), OrderSide.valueOf(row.getString("side"))), args.toArray())
                .stream().findFirst();
    }

    public List<Candidate> page(Group group, Candidate after, OffsetDateTime now, int size) {
        // SQL 방향은 enum에서만 선택합니다. 가격은 바인딩하며 문자열 입력을 SQL에 삽입하지 않습니다.
        boolean buy = group.side() == OrderSide.BUY;
        String cursor = after == null ? "" : " AND (limit_price " + (buy ? "<" : ">")
                + " ? OR (limit_price = ? AND (ordered_at, order_id) > (?, ?))) ";
        List<Object> args = new ArrayList<>(List.of(now, group.stockId()));
        if (after != null) {
            args.add(after.price()); args.add(after.price()); args.add(after.orderedAt()); args.add(after.orderId());
        }
        args.add(size);
        return jdbc.query("SELECT order_id, limit_price, ordered_at FROM trade_order WHERE " + ACTIVE
                + " AND stock_id = ? AND side = '" + group.side().name() + "'" + cursor
                + " ORDER BY limit_price " + (buy ? "DESC" : "ASC") + ", ordered_at, order_id LIMIT ?",
                (row, index) -> new Candidate(row.getLong("order_id"), row.getBigDecimal("limit_price"),
                        row.getObject("ordered_at", OffsetDateTime.class)), args.toArray());
    }
}
