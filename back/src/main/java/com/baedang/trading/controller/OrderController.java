package com.baedang.trading.controller;

import com.baedang.trading.dto.OrderQuoteResponse;
import com.baedang.trading.dto.OrderResponse;
import com.baedang.trading.dto.PlaceOrderRequest;
import com.baedang.trading.service.MarketOrderService;
import com.baedang.trading.service.OrderQuoteService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderQuoteService orderQuoteService;
    private final MarketOrderService marketOrderService;

    public OrderController(
            OrderQuoteService orderQuoteService,
            MarketOrderService marketOrderService
    ) {
        this.orderQuoteService = orderQuoteService;
        this.marketOrderService = marketOrderService;
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

    @PostMapping
    public ResponseEntity<OrderResponse> placeMarketOrder(
            @RequestHeader("X-User-Id") Long userId,
            @RequestBody PlaceOrderRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED).body(marketOrderService.place(userId, request));
    }
}
