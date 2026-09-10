package com.baedang.report.controller;

import com.baedang.report.dto.PersonalityReportResponse;
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

    public ReportController(PersonalityReportService personalityReportService) {
        this.personalityReportService = personalityReportService;
    }

    /** 내 투자 성향 리포트(현재 활성 계좌=라운드 기준). */
    @GetMapping("/me")
    public ResponseEntity<PersonalityReportResponse> getMyReport(
            @AuthenticationPrincipal Long userId
    ) {
        return ResponseEntity.ok(personalityReportService.getReport(userId));
    }
}
