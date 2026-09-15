package com.baedang.report.leaderboard.repository;

import com.baedang.report.leaderboard.entity.LeaderboardRun;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface LeaderboardRunRepository extends JpaRepository<LeaderboardRun, java.time.OffsetDateTime> {

    /** 가장 최근 배치 실행. 조회는 이 실행을 기준으로 한다(스냅샷 행 max(as_of) 아님). */
    Optional<LeaderboardRun> findTopByOrderByAsOfDesc();
}
