package com.baedang.report.leaderboard.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/**
 * 리더보드 배치 실행 기록 한 건(설계문서 §6.4 리뷰 반영). 매 실행마다(참가자 0 포함) 한 행을
 * 남겨, 조회가 <b>최신 실행</b>을 기준으로 빈 보드를 판별하게 한다 — 스냅샷 행의 {@code max(as_of)}
 * 만 보면 전원 리셋·시드 제외 전환 시 과거 순위가 계속 노출되는 문제를 막는다.
 */
@Entity
@Table(name = "leaderboard_run")
public class LeaderboardRun {

    @Id
    @Column(name = "as_of", nullable = false)
    private OffsetDateTime asOf;

    @Column(name = "participants", nullable = false)
    private int participants;

    protected LeaderboardRun() {
    }

    private LeaderboardRun(OffsetDateTime asOf, int participants) {
        this.asOf = asOf;
        this.participants = participants;
    }

    public static LeaderboardRun of(OffsetDateTime asOf, int participants) {
        return new LeaderboardRun(asOf, participants);
    }

    public OffsetDateTime getAsOf() { return asOf; }
    public int getParticipants() { return participants; }
}
