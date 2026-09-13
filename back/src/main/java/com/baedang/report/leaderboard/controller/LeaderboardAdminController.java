package com.baedang.report.leaderboard.controller;

import com.baedang.report.leaderboard.service.LeaderboardSnapshotService;
import com.baedang.report.leaderboard.service.LeaderboardSnapshotService.SnapshotResult;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 리더보드 스냅샷 배치의 개발용 수동 트리거(설계문서 §6.4).
 *
 * <p><b>기본 비활성.</b> {@code report.leaderboard.admin-trigger-enabled=true} 일 때만 빈으로
 * 등록됩니다. 하루 1회 스케줄을 기다리지 않고 시드 모집단으로 배치를 검증할 때 켭니다.
 * {@code /internal} 경로는 인프라에서도 차단하세요.
 */
@RestController
@RequestMapping("/internal/admin/leaderboard")
@ConditionalOnProperty(prefix = "report.leaderboard", name = "admin-trigger-enabled", havingValue = "true")
public class LeaderboardAdminController {

    private final LeaderboardSnapshotService leaderboardSnapshotService;

    public LeaderboardAdminController(LeaderboardSnapshotService leaderboardSnapshotService) {
        this.leaderboardSnapshotService = leaderboardSnapshotService;
    }

    /** 리더보드 순위 스냅샷을 지금 갱신합니다(새 as_of 적재). */
    @PostMapping("/refresh")
    public ResponseEntity<SnapshotResult> refresh() {
        return ResponseEntity.ok(leaderboardSnapshotService.refresh());
    }
}
