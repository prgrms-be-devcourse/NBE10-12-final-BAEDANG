package com.baedang.report.leaderboard.repository;

import com.baedang.report.leaderboard.entity.LeaderboardSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface LeaderboardSnapshotRepository extends JpaRepository<LeaderboardSnapshot, Long> {

    /** 상위 N 발췌. */
    List<LeaderboardSnapshot> findByAsOfAndRankLessThanEqualOrderByRankAsc(OffsetDateTime asOf, int maxRank);

    /** 내 순위 주변 발췌. */
    List<LeaderboardSnapshot> findByAsOfAndRankBetweenOrderByRankAsc(OffsetDateTime asOf, int lowRank, int highRank);

    /** 내 계좌의 순위 행. */
    Optional<LeaderboardSnapshot> findByAsOfAndAccountId(OffsetDateTime asOf, Long accountId);
}
