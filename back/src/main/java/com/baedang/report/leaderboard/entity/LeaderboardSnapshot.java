package com.baedang.report.leaderboard.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 리더보드 배치가 적재하는 순위 스냅샷 한 행(설계문서 §6.4).
 *
 * <p>한 배치 실행이 자격 계좌마다 한 행을 남기고, 같은 실행의 행들은 {@code asOf}를 공유한다.
 * 화면은 최신 {@code asOf} 만 읽어 아침 고정 순위 + as-of 시각을 보여준다. 절대 금액은 저장하되
 * (재현·검증용 {@code equity}), 노출은 수익률%와 상위 N% 뿐이다(사행성 배제).
 */
@Entity
@Table(name = "leaderboard_snapshot")
public class LeaderboardSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "snapshot_id")
    private Long snapshotId;

    @Column(name = "as_of", nullable = false)
    private OffsetDateTime asOf;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "round_no", nullable = false)
    private Integer roundNo;

    @Column(name = "equity", nullable = false, precision = 19, scale = 4)
    private BigDecimal equity;

    @Column(name = "return_rate", nullable = false, precision = 12, scale = 6)
    private BigDecimal returnRate;

    @Column(name = "rank", nullable = false)
    private Integer rank;

    @Column(name = "participants", nullable = false)
    private Integer participants;

    protected LeaderboardSnapshot() {
    }

    private LeaderboardSnapshot(OffsetDateTime asOf, Long accountId, Long userId, Integer roundNo,
                                BigDecimal equity, BigDecimal returnRate, int rank, int participants) {
        this.asOf = asOf;
        this.accountId = accountId;
        this.userId = userId;
        this.roundNo = roundNo;
        this.equity = equity;
        this.returnRate = returnRate;
        this.rank = rank;
        this.participants = participants;
    }

    public static LeaderboardSnapshot of(OffsetDateTime asOf, Long accountId, Long userId, Integer roundNo,
                                         BigDecimal equity, BigDecimal returnRate, int rank, int participants) {
        return new LeaderboardSnapshot(asOf, accountId, userId, roundNo, equity, returnRate, rank, participants);
    }

    public Long getSnapshotId() { return snapshotId; }
    public OffsetDateTime getAsOf() { return asOf; }
    public Long getAccountId() { return accountId; }
    public Long getUserId() { return userId; }
    public Integer getRoundNo() { return roundNo; }
    public BigDecimal getEquity() { return equity; }
    public BigDecimal getReturnRate() { return returnRate; }
    public Integer getRank() { return rank; }
    public Integer getParticipants() { return participants; }
}
