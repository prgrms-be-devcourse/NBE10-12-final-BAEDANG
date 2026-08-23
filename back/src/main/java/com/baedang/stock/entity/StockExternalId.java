package com.baedang.stock.entity;

import jakarta.persistence.*;

import java.io.Serializable;
import java.util.Objects;

/**
 * 외부 소스별 심볼 매핑.
 *
 * <p>지금은 토스 하나지만, 나중에 DART·KIS 를 붙일 때 같은 종목의 식별자가
 * 소스마다 다릅니다. {@code stock} 테이블에 컬럼을 계속 늘리는 대신
 * 여기에 행으로 쌓습니다.
 */
@Entity
@Table(name = "stock_external_id")
@IdClass(StockExternalId.Pk.class)
public class StockExternalId {

    @Id
    @Column(name = "stock_id")
    private Long stockId;

    /** TOSS · DART · KIS 등. */
    @Id
    @Column(name = "source", length = 20)
    private String source;

    @Column(name = "external_id", nullable = false, length = 50)
    private String externalId;

    protected StockExternalId() {
    }

    public StockExternalId(Long stockId, String source, String externalId) {
        this.stockId = stockId;
        this.source = source;
        this.externalId = externalId;
    }

    public Long getStockId() { return stockId; }
    public String getSource() { return source; }
    public String getExternalId() { return externalId; }

    /**
     * 복합 PK 용 식별자 클래스.
     *
     * <p>{@code equals}/{@code hashCode} 가 <b>반드시</b> 있어야 합니다.
     * 없으면 Hibernate 가 같은 행을 다른 객체로 취급해서 중복 INSERT 를 시도합니다.
     * 기본 생성자도 필요합니다 — 리플렉션으로 만들기 때문입니다.
     */
    public static class Pk implements Serializable {
        private Long stockId;
        private String source;

        public Pk() {
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Pk pk)) return false;
            return Objects.equals(stockId, pk.stockId) && Objects.equals(source, pk.source);
        }

        @Override
        public int hashCode() {
            return Objects.hash(stockId, source);
        }
    }
}
