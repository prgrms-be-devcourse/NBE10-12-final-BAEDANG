package com.baedang.report.controller;

import com.baedang.report.seed.PortfolioSeedService;
import com.baedang.report.seed.PortfolioSeedService.SeedResult;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 합성(시드) 포트폴리오 적재 관리자 트리거 (#152 Phase 2 검증 게이트).
 *
 * <p><b>기본 비활성.</b> {@code report.seed.enabled=true} 일 때만 빈으로 등록됩니다. 인증이
 * 붙기 전까지 상시 노출을 피하려는 것으로, 운영자가 시딩이 필요한 구간에만 플래그를 켜고 끕니다.
 * {@code /internal} 경로는 인프라(방화벽·게이트웨이)에서도 차단하세요.
 *
 * <p>종목 마스터·랭킹 적재로 {@code is_ranked} 유니버스가 채워진 뒤 호출해야 합니다.
 * 프로덕션에는 켜지 마세요 — 시드는 개발/데모 전용입니다.
 */
@RestController
@RequestMapping("/internal/admin/seed")
@ConditionalOnProperty(prefix = "report.seed", name = "enabled", havingValue = "true")
public class PortfolioSeedAdminController {

    private final PortfolioSeedService portfolioSeedService;

    public PortfolioSeedAdminController(PortfolioSeedService portfolioSeedService) {
        this.portfolioSeedService = portfolioSeedService;
    }

    /** 합성 포트폴리오 시드를 적재합니다. 이미 시드가 있으면 no-op(멱등). */
    @PostMapping("/portfolios")
    public ResponseEntity<SeedResult> seedPortfolios() {
        return ResponseEntity.ok(portfolioSeedService.seed());
    }
}
