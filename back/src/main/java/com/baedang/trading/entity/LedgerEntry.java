package com.baedang.trading.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 거래 원장. <b>append-only 입니다 — UPDATE 와 DELETE 를 하지 않습니다.</b>
 *
 * <p>잘못 기록했으면 수정하지 말고 반대 부호 항목을 넣어 상쇄하세요.
 * 그래서 이 엔티티에는 상태를 바꾸는 메서드가 하나도 없습니다.
 *
 * <p><b>항목은 세 가지뿐입니다</b> — INITIAL_DEPOSIT / BUY / SELL.
 * 수수료와 세금은 별도 줄로 쪼개지 않고 매수·매도 금액에 포함합니다.
 * 신규 거래 원장은 개별 체결에 대응하며 주문당 여러 줄이 존재할 수 있습니다.
 *
 * <p>검증식: {@code SUM(amount) = account.cash_balance} (계좌별).
 * 테스트로 만들어두면 원장을 제대로 이해했다는 가장 확실한 증거가 됩니다.
 *
 * <p><b>정적 팩토리가 세 개인 이유</b> — 항목 종류마다 필요한 값이 다릅니다.
 * 초기 지급은 주문이 없고, 매수는 음수, 매도는 양수여야 합니다.
 * 범용 빌더 하나로 두면 {@code SELL} 인데 음수를 넣는 실수가 컴파일됩니다.
 * 팩토리로 나누면 <b>잘못된 조합을 애초에 만들 수 없습니다.</b>
 */
@Entity
@Table(name = "ledger_entry")
public class LedgerEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "entry_id")
    private Long entryId;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    /** 원인이 된 주문. 초기금 지급은 주문이 없으므로 null. */
    @Column(name = "order_id")
    private Long orderId;

    /** 개별 체결 근거. 초기 지급/기존 시장가 원장/독립 정정 기록은 null일 수 있습니다. */
    @Column(name = "execution_id")
    private Long executionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "entry_type", nullable = false, length = 20)
    private EntryType entryType;

    /** 부호 있는 예수금 증감액. 수수료·세금이 <b>포함된</b> 값입니다. */
    @Column(name = "amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    /** 반영 직후 잔액. 파생값이지만 정합성이 깨진 지점을 즉시 찾는 데 유용합니다. */
    @Column(name = "balance_after", nullable = false, precision = 19, scale = 4)
    private BigDecimal balanceAfter;

    /**
     * 체결 시점 환율. 원화 종목은 1.
     *
     * <p>{@code amount} 가 이미 원화 환산값이라 계산에는 안 쓰입니다.
     * "이 거래를 얼마짜리 환율로 했는가"를 <b>원장만 보고 알 수 있게</b> 하는
     * 감사 항목입니다. 지금 안 남기면 과거 값은 복원할 수 없습니다.
     */
    @Column(name = "exchange_rate", nullable = false, precision = 19, scale = 6)
    private BigDecimal exchangeRate;

    /** "삼성전자 10주 @ 241,500 (수수료 포함)" 처럼 사람이 읽을 설명. */
    @Column(name = "memo", length = 200)
    private String memo;

    @Column(name = "occurred_at", nullable = false)
    private OffsetDateTime occurredAt;

    protected LedgerEntry() {
    }

    private LedgerEntry(Long accountId, Long orderId, EntryType entryType, BigDecimal amount,
                        BigDecimal balanceAfter, BigDecimal exchangeRate, String memo,
                        OffsetDateTime occurredAt) {
        this.accountId = accountId;
        this.orderId = orderId;
        this.entryType = entryType;
        this.amount = amount;
        this.balanceAfter = balanceAfter;
        this.exchangeRate = exchangeRate != null ? exchangeRate : BigDecimal.ONE;
        this.memo = memo;
        this.occurredAt = occurredAt;
    }

    /** 모의투자금 지급. 회원가입과 포트폴리오 초기화 두 곳에서 씁니다. */
    public static LedgerEntry initialDeposit(
            Long accountId,
            BigDecimal amount,
            String memo,
            OffsetDateTime occurredAt
    ) {
        return new LedgerEntry(accountId, null, EntryType.INITIAL_DEPOSIT,
                amount, amount, BigDecimal.ONE, memo, occurredAt);
    }

    /**
     * 매수. {@code netAmount}(gross + fee)를 <b>음수로 뒤집어</b> 넣습니다.
     * 호출부는 양수를 넘기면 됩니다 — 부호를 헷갈릴 일이 없습니다.
     */
    public static LedgerEntry buy(Long accountId, Long orderId, BigDecimal netAmount,
                                  BigDecimal balanceAfter, BigDecimal exchangeRate, String memo,
                                  OffsetDateTime occurredAt) {
        return new LedgerEntry(accountId, orderId, EntryType.BUY,
                netAmount.negate(), balanceAfter, exchangeRate, memo, occurredAt);
    }

    /** 매도. {@code netAmount}(gross − fee − tax)가 그대로 양수로 들어갑니다. */
    public static LedgerEntry sell(Long accountId, Long orderId, BigDecimal netAmount,
                                   BigDecimal balanceAfter, BigDecimal exchangeRate, String memo,
                                   OffsetDateTime occurredAt) {
        return new LedgerEntry(accountId, orderId, EntryType.SELL,
                netAmount, balanceAfter, exchangeRate, memo, occurredAt);
    }

    public Long getEntryId() { return entryId; }
    public Long getExecutionId() { return executionId; }

    /** 신규 정상 체결 원장. 과거 기록 보정과 중복 요청 판정은 호출부의 별도 책임입니다. */
    public static LedgerEntry execution(TradeOrder order, TradeExecution execution, BigDecimal balanceAfter, String memo) {
        if (execution == null || execution.getExecutionId() == null || balanceAfter == null || balanceAfter.signum() < 0) {
            throw new IllegalArgumentException("저장된 체결과 체결 직후 잔액이 필요합니다");
        }
        execution.validateOrder(order);
        boolean buy = order.getSide() == OrderSide.BUY;
        LedgerEntry entry = new LedgerEntry(order.getAccountId(), order.getOrderId(),
                buy ? EntryType.BUY : EntryType.SELL,
                buy ? execution.getNetAmountKrw().negate() : execution.getNetAmountKrw(), balanceAfter,
                execution.getExchangeRate(), memo, execution.getExecutedAt());
        entry.executionId = execution.getExecutionId();
        return entry;
    }
    public Long getAccountId() { return accountId; }
    public Long getOrderId() { return orderId; }
    public EntryType getEntryType() { return entryType; }
    public BigDecimal getAmount() { return amount; }
    public BigDecimal getBalanceAfter() { return balanceAfter; }
    public BigDecimal getExchangeRate() { return exchangeRate; }
    public String getMemo() { return memo; }
    public OffsetDateTime getOccurredAt() { return occurredAt; }
}
