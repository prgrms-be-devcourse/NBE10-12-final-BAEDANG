package com.baedang.stock.controller;

import com.baedang.stock.dto.CandleResponse;
import com.baedang.stock.dto.RankingResponse;
import com.baedang.stock.dto.StockSearchResponse;
import com.baedang.stock.dto.StockDetailResponse;
import com.baedang.stock.service.CandleQueryService;
import com.baedang.stock.service.RankingService;
import com.baedang.stock.service.StockSearchService;
import com.baedang.stock.service.StockDetailService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/stocks")
public class StockController {

    private final StockSearchService stockSearchService;
    private final RankingService rankingService;
    private final CandleQueryService candleQueryService;
    private final StockDetailService stockDetailService;

    public StockController(
            StockSearchService stockSearchService,
            RankingService rankingService,
            CandleQueryService candleQueryService,
            StockDetailService stockDetailService
    ) {
        this.stockSearchService = stockSearchService;
        this.rankingService = rankingService;
        this.candleQueryService = candleQueryService;
        this.stockDetailService = stockDetailService;
    }

    @GetMapping("/{symbol}")
    public ResponseEntity<StockDetailResponse> detail(
            @PathVariable String symbol,
            @RequestParam String marketCountry
    ) {
        return ResponseEntity.ok(stockDetailService.getDetail(symbol, marketCountry));
    }

    @GetMapping("/search")
    public ResponseEntity<StockSearchResponse> search(
            @RequestParam("q") String query,
            @RequestParam(defaultValue = "10") int size
    ) {
        return ResponseEntity.ok(stockSearchService.search(query, size));
    }

    @GetMapping("/rankings")
    public ResponseEntity<RankingResponse> rankings(
            @RequestParam String market,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String cursor
    ) {
        return ResponseEntity.ok(
                rankingService.getRankings(market, size, cursor)
        );
    }

    @GetMapping("/{symbol}/candles")
    public ResponseEntity<CandleResponse> candles(
            @PathVariable String symbol,
            @RequestParam String marketCountry,
            @RequestParam String interval,
            @RequestParam String range
    ) {
        return ResponseEntity.ok(
                candleQueryService.getCandles(symbol, marketCountry, interval, range));
    }
}
