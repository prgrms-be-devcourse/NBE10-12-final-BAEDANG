package com.baedang.market.controller;

import com.baedang.market.dto.ExchangeRateHistoryResponse;
import com.baedang.market.dto.ExchangeRateLatestResponse;
import com.baedang.market.service.ExchangeRateService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/exchange-rates")
public class ExchangeRateController {

    private final ExchangeRateService exchangeRateService;

    public ExchangeRateController(ExchangeRateService exchangeRateService) {
        this.exchangeRateService = exchangeRateService;
    }

    /** 랭킹 화면 환율 배너. base/quote 모두 생략 가능 — 기본값 USD/KRW (docs/api-spec.md 참고). */
    @GetMapping("/latest")
    public ResponseEntity<ExchangeRateLatestResponse> latest(
            @RequestParam(defaultValue = "USD") String base,
            @RequestParam(defaultValue = "KRW") String quote
    ) {
        return ResponseEntity.ok(exchangeRateService.getLatest(base, quote));
    }

    @GetMapping("/history")
    public ResponseEntity<ExchangeRateHistoryResponse> history(
            @RequestParam String period
    ) {
        return ResponseEntity.ok(exchangeRateService.getHistory(period));
    }
}
