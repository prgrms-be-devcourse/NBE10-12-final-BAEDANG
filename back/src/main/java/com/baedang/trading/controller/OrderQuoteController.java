package com.baedang.trading.controller;

import com.baedang.trading.dto.OrderQuoteResponse;
import com.baedang.trading.service.OrderQuoteService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/orders")
public class OrderQuoteController {

    private final OrderQuoteService orderQuoteService;

    public OrderQuoteController(OrderQuoteService orderQuoteService) {
        this.orderQuoteService = orderQuoteService;
    }

    @GetMapping("/quote")
    public ResponseEntity<OrderQuoteResponse> quote(
            @RequestHeader("X-User-Id") Long userId,
            @RequestParam(required = false) String symbol,
            @RequestParam(required = false) String side,
            @RequestParam(required = false) String quantity
    ) {
        return ResponseEntity.ok(orderQuoteService.getQuote(userId, symbol, side, quantity));
    }
}
