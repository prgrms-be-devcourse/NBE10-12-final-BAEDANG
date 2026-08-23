package com.baedang.global.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.MappedSuperclass;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.OffsetDateTime;

/**
 * 생성·수정 시각을 자동으로 채워주는 공통 부모.
 *
 * <p><b>아무 엔티티나 상속시키지 마세요.</b><br>
 * {@code created_at} 과 {@code updated_at} 두 컬럼이 <b>실제로 있는 테이블만</b>
 * 상속해야 합니다. 없는 테이블이 상속하면 Hibernate 가 컬럼을 찾다가
 * {@code ddl-auto: validate} 단계에서 기동을 거부합니다.
 *
 * <p>현재 이 클래스를 상속하는 테이블은 <b>{@code users}, {@code stock}</b> 둘뿐입니다.
 * 나머지는 시각 컬럼의 의미가 서로 달라서 각자 직접 들고 있습니다 —
 * {@code account} 는 {@code opened_at}/{@code closed_at}(회차의 시작·종료),
 * {@code ledger_entry} 는 {@code occurred_at}(사건 발생 시각),
 * {@code holding} 은 {@code updated_at} 만 있습니다.
 * "공통이니까 다 붙이자" 가 아니라 <b>컬럼이 실제로 있는지</b>가 기준입니다.
 *
 * <p>시각 타입이 {@code OffsetDateTime} 인 이유는 DB 가 {@code TIMESTAMPTZ} 라서입니다.
 * 국내장·미국장·서머타임이 섞이므로 {@code LocalDateTime} 을 쓰면 오프셋이 날아갑니다.
 */
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class BaseEntity {

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }
}
