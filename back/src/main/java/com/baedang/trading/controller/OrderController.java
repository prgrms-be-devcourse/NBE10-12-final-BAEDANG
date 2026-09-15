package com.baedang.trading.controller;

import com.baedang.trading.dto.OrderExecutionsResponse;

import com.baedang.global.error.BusinessException;
import com.baedang.global.error.ErrorCode;
import com.baedang.trading.dto.LimitOrderQuoteResponse;
import com.baedang.trading.dto.LimitOrderRequest;
import com.baedang.trading.dto.OrderDetailResponse;
import com.baedang.trading.dto.MarketOrderQuoteResponse;
import com.baedang.trading.dto.MarketOrderRequest;
import com.baedang.trading.dto.MarketOrderResponse;
import com.baedang.trading.service.LimitOrderService;
import com.baedang.trading.service.MarketOrderService;
import com.baedang.trading.service.MarketOrderQuoteService;
import com.baedang.trading.service.OrderReadService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final MarketOrderQuoteService marketOrderQuoteService;
    private final MarketOrderService marketOrderService;
    private final LimitOrderService limitOrderService;
    private final OrderReadService orderReadService;

    public OrderController(
            MarketOrderQuoteService marketOrderQuoteService,
            MarketOrderService marketOrderService,
            LimitOrderService limitOrderService,
            OrderReadService orderReadService
    ) {
        this.marketOrderQuoteService = marketOrderQuoteService;
        this.marketOrderService = marketOrderService;
        this.limitOrderService = limitOrderService;
        this.orderReadService = orderReadService;
    }

    @GetMapping("/quote/market")
    public ResponseEntity<MarketOrderQuoteResponse> quoteMarket(
            @AuthenticationPrincipal Long userId,
            @RequestParam String symbol,
            @RequestParam String marketCountry,
            @RequestParam String side,
            @RequestParam String quantity
    ) {
        return ResponseEntity.ok(marketOrderQuoteService.getQuote(userId, symbol, marketCountry, side, quantity));
    }

    @GetMapping("/quote/limit")
    public ResponseEntity<LimitOrderQuoteResponse> quoteLimit(
            @AuthenticationPrincipal Long userId,
            @RequestParam String symbol,
            @RequestParam String marketCountry,
            @RequestParam String side,
            @RequestParam String quantity,
            @RequestParam String limitPrice,
            @RequestParam String limitCurrency
    ) {
        return ResponseEntity.ok(limitOrderService.quote(
                userId, symbol, marketCountry, side, quantity, limitPrice, limitCurrency));
    }

    @PostMapping("/market")
    public ResponseEntity<MarketOrderResponse> placeMarketOrder(
            @AuthenticationPrincipal Long userId,
            @RequestBody MarketOrderRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED).body(marketOrderService.place(userId, request));
    }

    @PostMapping("/limit")
    public ResponseEntity<OrderDetailResponse> placeLimitOrder(
            @AuthenticationPrincipal Long userId,
            @RequestBody LimitOrderRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED).body(limitOrderService.place(userId, request));
    }

    @GetMapping("/{orderId}")
    public OrderDetailResponse detail(@AuthenticationPrincipal Long userId, @PathVariable Long orderId) {
        return orderReadService.detail(userId, orderId);
    }

    @GetMapping("/{orderId}/executions")
    public OrderExecutionsResponse executions(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long orderId,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int size
    ) {
        return orderReadService.executions(userId, orderId, cursor, size);
    }

    @PatchMapping("/{orderId}")
    public OrderDetailResponse cancel(@AuthenticationPrincipal Long userId, @PathVariable Long orderId,
            @RequestBody JsonNode request) {
        if (!request.isObject() || request.size() != 1 || !request.path("status").isTextual()
                || !"CANCELED".equals(request.path("status").textValue())) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
        return limitOrderService.cancel(userId, orderId);
    }
}
