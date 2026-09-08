package com.baedang.orderbook.entity;

import com.baedang.orderbook.model.GeneratedOrderBookLevel;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

@Entity
@Table(
        name = "order_book_level",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_order_book_level",
                columnNames = {"book_version_id", "side", "level_depth"}
        )
)
public class OrderBookLevel {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "level_id")
    private Long levelId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "book_version_id", nullable = false)
    private OrderBookVersion bookVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "side", nullable = false, length = 4)
    private OrderBookSide side;

    @Column(name = "level_depth", nullable = false)
    private Integer levelDepth;

    @Column(name = "price", nullable = false, precision = 19, scale = 4)
    private BigDecimal price;

    @Column(name = "initial_quantity", nullable = false, precision = 19, scale = 6)
    private BigDecimal initialQuantity;

    @Column(name = "remaining_quantity", nullable = false, precision = 19, scale = 6)
    private BigDecimal remainingQuantity;

    protected OrderBookLevel() {
    }

    private OrderBookLevel(
            OrderBookVersion bookVersion,
            OrderBookSide side,
            int levelDepth,
            BigDecimal price,
            BigDecimal quantity
    ) {
        this.bookVersion = bookVersion;
        this.side = Objects.requireNonNull(side, "side는 필수입니다");
        if (levelDepth < 1 || levelDepth > 10) {
            throw new IllegalArgumentException("levelDepth는 1 이상 10 이하이어야 합니다: " + levelDepth);
        }
        this.levelDepth = levelDepth;
        this.price = Objects.requireNonNull(price, "price는 필수입니다");
        if (price.signum() <= 0) {
            throw new IllegalArgumentException("price는 양수여야 합니다");
        }
        Objects.requireNonNull(quantity, "quantity는 필수입니다");
        if (quantity.signum() <= 0) {
            throw new IllegalArgumentException("quantity는 양수여야 합니다");
        }
        this.initialQuantity = quantity;
        this.remainingQuantity = quantity;
    }

    public static OrderBookLevel create(
            OrderBookVersion bookVersion,
            OrderBookSide side,
            int levelDepth,
            BigDecimal price,
            BigDecimal quantity
    ) {
        return new OrderBookLevel(bookVersion, side, levelDepth, price, quantity);
    }

    /** publisher가 생성 결과(ASK 10개, 국내 BID 10개, 미국 BID 1~10개)를 새 버전 산하 엔티티로 변환한다. */
    public static List<OrderBookLevel> from(OrderBookVersion bookVersion, List<GeneratedOrderBookLevel> generated) {
        return generated.stream()
                .map(level -> create(
                        bookVersion,
                        level.side(),
                        level.levelDepth(),
                        level.price(),
                        level.quantity()))
                .toList();
    }

    public void consume(BigDecimal quantity) {
        if (quantity == null || quantity.signum() <= 0) {
            throw new IllegalArgumentException("소비 수량은 양수여야 합니다: " + quantity);
        }
        if (quantity.compareTo(this.remainingQuantity) > 0) {
            throw new IllegalArgumentException("잔여 수량을 초과하여 소비할 수 없습니다: 잔여="
                    + this.remainingQuantity + ", 요청=" + quantity);
        }
        this.remainingQuantity = this.remainingQuantity.subtract(quantity);
    }

    public Long getLevelId() {
        return levelId;
    }

    public OrderBookVersion getBookVersion() {
        return bookVersion;
    }

    public OrderBookSide getSide() {
        return side;
    }

    public Integer getLevelDepth() {
        return levelDepth;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public BigDecimal getInitialQuantity() {
        return initialQuantity;
    }

    public BigDecimal getRemainingQuantity() {
        return remainingQuantity;
    }
}
