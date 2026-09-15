package com.baedang.orderbook.controller;

import com.baedang.orderbook.dto.OrderBookResponse;
import com.baedang.orderbook.service.OrderBookQueryService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/stocks")
public class OrderBookController {

    private final OrderBookQueryService queryService;

    public OrderBookController(OrderBookQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping("/{symbol}/orderbook")
    public ResponseEntity<OrderBookResponse> orderBook(
            @PathVariable String symbol,
            @RequestParam(required = false) String marketCountry
    ) {
        return ResponseEntity.ok(queryService.getOrderBook(symbol, marketCountry));
    }
}
