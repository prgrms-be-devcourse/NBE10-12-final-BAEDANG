package com.baedang.user.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 모의 투자 계좌. <b>회차(round_no)당 한 개</b>입니다.
 *
 * <p>포트폴리오 초기화는 삭제가 아니라 <b>기존 계좌를 CLOSED 로 바꾸고
 * 새 회차 계좌를 만드는 것</b>입니다. 원장이 보존되므로 "지난 회차 성적"
 * 기능으로 확장할 수 있습니다.
 *
 * <p>{@code account_id} 는 전역 시퀀스라 {@code round_no} 와 함께 +1 되지 않습니다.
 * 2회차 계좌의 id 가 2일 거라고 가정하지 마세요.
 *
 * <p><b>BaseEntity 를 상속하지 않습니다.</b> 이 테이블에는 created_at/updated_at 이
 * 없고 {@code opened_at}/{@code closed_at}(회차의 시작·종료)이 있습니다.
 */
@Entity
@Table(name = "account")
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "account_id")
    private Long accountId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 초기화 회차. 1부터 시작해 초기화할 때마다 +1. */
    @Column(name = "round_no", nullable = false)
    private Integer roundNo;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private AccountStatus status;

    /** 지급액. 수익률의 분모라 계좌마다 저장합니다 — 정책이 바뀌어도 과거 회차가 보존됩니다. */
    @Column(name = "initial_cash", nullable = false, precision = 19, scale = 4)
    private BigDecimal initialCash;

    /** 전체 예수금. 매수 트랜잭션에서 {@code FOR UPDATE} 로 잠그는 대상입니다. */
    @Column(name = "cash_balance", nullable = false, precision = 19, scale = 4)
    private BigDecimal cashBalance;

    /**
     * 미체결 주문에 묶인 금액.
     *
     * <p><b>주문가능금액은 저장하지 않습니다</b> — {@link #availableCash()} 로
     * 계산합니다. 파생값을 저장하면 한쪽만 갱신되는 버그가 조용히 지속됩니다.
     */
    @Column(name = "locked_cash", nullable = false, precision = 19, scale = 4)
    private BigDecimal lockedCash;

    @Column(name = "opened_at", nullable = false)
    private OffsetDateTime openedAt;

    @Column(name = "closed_at")
    private OffsetDateTime closedAt;

    /** JPA 낙관적 락. 비관적 락({@code FOR UPDATE})이 주 전략이지만 이중 안전장치로 둡니다. */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    protected Account() {
    }

    private Account(Long userId, Integer roundNo, BigDecimal initialCash) {
        this.userId = userId;
        this.roundNo = roundNo;
        this.initialCash = initialCash;
        this.cashBalance = initialCash;
        this.lockedCash = BigDecimal.ZERO;
        this.status = AccountStatus.ACTIVE;
        this.openedAt = OffsetDateTime.now();
    }

    /** 새 회차 계좌 개설. 예수금은 지급액에서 시작합니다. */
    public static Account open(Long userId, int roundNo, BigDecimal initialCash) {
        return new Account(userId, roundNo, initialCash);
    }

    /** 주문가능금액. 저장하지 않고 매번 계산합니다. */
    public BigDecimal availableCash() {
        return cashBalance.subtract(lockedCash);
    }

    /** 시장가 매수 금액을 즉시 차감합니다. 지정가 주문의 동결액은 침범하지 않습니다. */
    public void debitMarketBuy(BigDecimal amount) {
        requirePositive(amount);
        if (availableCash().compareTo(amount) < 0) {
            throw new IllegalStateException("주문가능금액보다 큰 금액을 차감할 수 없습니다");
        }
        this.cashBalance = this.cashBalance.subtract(amount);
    }

    /** 시장가 매도 체결 금액을 예수금에 즉시 반영합니다. */
    public void creditMarketSell(BigDecimal amount) {
        requirePositive(amount);
        this.cashBalance = this.cashBalance.add(amount);
    }

    private void requirePositive(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("금액은 0보다 커야 합니다");
        }
    }

    public void close() {
        this.status = AccountStatus.CLOSED;
        this.closedAt = OffsetDateTime.now();
    }

    public Long getAccountId() {
        return accountId;
    }

    public Long getUserId() {
        return userId;
    }

    public Integer getRoundNo() {
        return roundNo;
    }

    public AccountStatus getStatus() {
        return status;
    }

    public BigDecimal getInitialCash() {
        return initialCash;
    }

    public BigDecimal getCashBalance() {
        return cashBalance;
    }

    public BigDecimal getLockedCash() {
        return lockedCash;
    }

    public OffsetDateTime getOpenedAt() {
        return openedAt;
    }

    public OffsetDateTime getClosedAt() {
        return closedAt;
    }

    public Long getVersion() {
        return version;
    }
}
