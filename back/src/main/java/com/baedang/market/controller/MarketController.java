package com.baedang.market.controller;

import com.baedang.market.dto.MarketStatusResponse;
import com.baedang.market.service.MarketStatusService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 시장 운영 정보 API. 프론트가 거래 버튼 활성 여부와 "실시간 / 종가" 라벨을 결정한다.
 */
@RestController
@RequestMapping("/api/market")
public class MarketController {

    private final MarketStatusService marketStatusService;

    public MarketController(MarketStatusService marketStatusService) {
        this.marketStatusService = marketStatusService;
    }

    @GetMapping("/status")
    public ResponseEntity<MarketStatusResponse> status() {
        return ResponseEntity.ok(marketStatusService.getStatus());
    }
}
