package com.baedang.report.leaderboard.scheduler;

import com.baedang.report.leaderboard.service.LeaderboardSnapshotService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 하루 1회 오전 저트래픽 창에 리더보드 순위 스냅샷을 갱신한다(설계문서 §6.4).
 *
 * <p>US 폐장~KR 개장 사이(07:30 KST)에 돌려 장중 순위를 아침 기준으로 고정하고, 일봉 배치와
 * 시각을 분리해 커넥션 풀 경합(#145)을 피한다. 전용 단일 스레드({@code leaderboardTaskScheduler})
 * 에서 실행한다.
 */
@Component
public class LeaderboardSnapshotScheduler {

    private static final Logger log = LoggerFactory.getLogger(LeaderboardSnapshotScheduler.class);

    private final LeaderboardSnapshotService leaderboardSnapshotService;

    public LeaderboardSnapshotScheduler(LeaderboardSnapshotService leaderboardSnapshotService) {
        this.leaderboardSnapshotService = leaderboardSnapshotService;
    }

    @Scheduled(cron = "0 30 7 * * *", zone = "Asia/Seoul", scheduler = "leaderboardTaskScheduler")
    public void refreshLeaderboard() {
        try {
            leaderboardSnapshotService.refresh();
        } catch (Exception e) {
            log.error("리더보드 스냅샷 배치 실행 중 오류", e);
        }
    }
}
