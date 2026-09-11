package com.baedang.market.event.controller;

import com.baedang.global.error.BusinessException;
import com.baedang.global.error.ErrorCode;
import com.baedang.market.event.dto.MarketEventListResponse;
import com.baedang.market.event.entity.KrMarket;
import com.baedang.market.event.service.MarketEventQueryService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Map;

/**
 * KRX 시장조치 이력 조회. 공개 엔드포인트다.
 *
 * <p>시장·날짜를 enum/날짜 타입으로 바로 받지 않고 문자열로 받아 직접 변환한다. 타입 바인딩에
 * 맡기면 프레임워크가 만든 메시지가 나가는데, 이 프로젝트의 다른 API는 {@code INVALID_INPUT}과
 * 문제 필드를 {@code data}에 담는 계약을 쓴다. 같은 입력 오류가 엔드포인트마다 다르게 보이면 안 된다.
 */
@RestController
public class MarketEventController {

    private final MarketEventQueryService queryService;

    public MarketEventController(MarketEventQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping("/api/market/events")
    public ResponseEntity<MarketEventListResponse> events(
            @RequestParam String market,
            @RequestParam String date
    ) {
        return ResponseEntity.ok(queryService.get(parseMarket(market), parseDate(date)));
    }

    private static KrMarket parseMarket(String raw) {
        return KrMarket.fromStockMarket(raw)
                .orElseThrow(() -> invalid("market", "지원하지 않는 시장입니다: " + raw));
    }

    private static LocalDate parseDate(String raw) {
        try {
            return LocalDate.parse(raw);
        } catch (DateTimeParseException e) {
            throw invalid("date", "날짜 형식이 올바르지 않습니다: " + raw);
        }
    }

    private static BusinessException invalid(String field, String detail) {
        return new BusinessException(ErrorCode.INVALID_INPUT, detail, Map.of("field", field));
    }
}
