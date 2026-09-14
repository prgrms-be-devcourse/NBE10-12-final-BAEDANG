package com.baedang.report.controller;

import com.baedang.report.dto.PersonalityReportResponse;
import com.baedang.report.leaderboard.dto.LeaderboardResponse;
import com.baedang.report.leaderboard.dto.LeaderboardTypesResponse;
import com.baedang.report.leaderboard.service.LeaderboardQueryService;
import com.baedang.report.service.PersonalityReportService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 투자 성향 리포트 API. 인증된 JWT subject 의 userId 만 신뢰합니다. */
@RestController
@RequestMapping("/api/reports")
public class ReportController {

    private final PersonalityReportService personalityReportService;
    private final LeaderboardQueryService leaderboardQueryService;

    public ReportController(PersonalityReportService personalityReportService,
                            LeaderboardQueryService leaderboardQueryService) {
        this.personalityReportService = personalityReportService;
        this.leaderboardQueryService = leaderboardQueryService;
    }

    /** 내 투자 성향 리포트(현재 활성 계좌=라운드 기준). */
    @GetMapping("/me")
    public ResponseEntity<PersonalityReportResponse> getMyReport(
            @AuthenticationPrincipal Long userId
    ) {
        return ResponseEntity.ok(personalityReportService.getReport(userId));
    }

    /** 리더보드(아침 배치 스냅샷 기준·as-of 노출). 자격 미달/스냅샷 없으면 me=null. */
    @GetMapping("/leaderboard")
    public ResponseEntity<LeaderboardResponse> getLeaderboard(
            @AuthenticationPrincipal Long userId
    ) {
        return ResponseEntity.ok(leaderboardQueryService.getLeaderboard(userId));
    }

    /** 유형별 성과 비교(최신 배치 스냅샷·같은 라운드 코호트·as-of 노출). 미분류 제외. */
    @GetMapping("/leaderboard/types")
    public ResponseEntity<LeaderboardTypesResponse> getLeaderboardTypes() {
        return ResponseEntity.ok(leaderboardQueryService.getTypeComparison());
    }
}
