package com.baedang.market.controller;

import com.baedang.market.service.DailyCandleSeedService;
import com.baedang.market.service.DailyCandleSeedService.SeedResult;
import com.baedang.stock.entity.MarketCountry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 상위 종목 과거 일봉 초기 시드 적재 관리자 트리거.
 *
 * <p><b>기본 비활성.</b> {@code toss.seed-daily-candles=true} 일 때만 빈으로 등록됩니다. 인증이
 * 없는 상태라 상시 노출을 피하려는 것으로, 운영자가 <b>시딩이 필요한 구간에만</b> 플래그를 켜고,
 * 끝나면 끕니다("조건부 1회 실행"). 실데이터 적재에는 {@code toss.enabled=true} 도 함께 필요합니다
 * (아니면 Toss 호출이 실패). 인증이 붙기 전까지 {@code /internal} 경로는 인프라(방화벽·게이트웨이)
 * 레벨에서도 차단하세요.
 *
 * <p>마스터·랭킹 적재로 유니버스가 채워진 뒤 호출해야 하며, 다른 차트 수집이 없는 시간대에 실행합니다.
 */
@RestController
@RequestMapping("/internal/admin/seed")
@ConditionalOnProperty(prefix = "toss", name = "seed-daily-candles", havingValue = "true")
public class DailyCandleSeedAdminController {

    private final DailyCandleSeedService dailyCandleSeedService;

    public DailyCandleSeedAdminController(DailyCandleSeedService dailyCandleSeedService) {
        this.dailyCandleSeedService = dailyCandleSeedService;
    }

    /**
     * 일봉 시드 적재를 실행합니다.
     *
     * @param market 특정 시장(KR·US)만 시드하려면 지정. 생략하면 전체 시장을 순차 시드.
     */
    @PostMapping("/daily-candles")
    public ResponseEntity<SeedResult> seedDailyCandles(
            @RequestParam(required = false) MarketCountry market
    ) {
        SeedResult result = (market == null)
                ? dailyCandleSeedService.seedAll()
                : dailyCandleSeedService.seed(market);
        return ResponseEntity.ok(result);
    }
}
