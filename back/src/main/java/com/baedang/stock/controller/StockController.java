package com.baedang.stock.controller;


import com.baedang.stock.dto.StockSearchResponse;
import com.baedang.stock.service.StockSearchService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/stocks")
public class StockController {

    private final StockSearchService stockSearchService;

    public StockController(StockSearchService stockSearchService) {
        this.stockSearchService = stockSearchService;
    }

    @GetMapping("/search")
    public ResponseEntity<StockSearchResponse> search(
            @RequestParam("q") String query,
            @RequestParam(defaultValue = "10") int size
    ){
        return ResponseEntity.ok(stockSearchService.search(query, size));
    }
}
