package com.baedang.stock.service;

import com.baedang.market.entity.DailyCandle;
import com.baedang.market.entity.QuoteSnapshot;
import com.baedang.market.port.Candle;
import com.baedang.market.port.CandleInterval;
import com.baedang.market.port.MarketDataPort;
import com.baedang.market.port.PriceQuote;
import com.baedang.market.repository.DailyCandleRepository;
import com.baedang.market.repository.QuoteSnapshotRepository;
import com.baedang.market.service.DailyCandlePersistenceService;
import com.baedang.market.service.QuoteSnapshotPersistenceService;
import com.baedang.stock.entity.MarketCountry;
import com.baedang.stock.entity.Stock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

/**
 * 거래대금 랭킹 상위 100(={@code is_ranked=true}) 밖 종목의 시세·일봉을 온디맨드로 채운다.
 *
 * <p>상위 100종목은 {@code QuoteSnapshotScheduler}(5초)·{@code DailyCandleCollectionScheduler}
 * (장 마감 후)가 이미 최신으로 유지하므로, 이 서비스는 <b>{@code is_ranked=false}인
 * 종목에 대해서만</b> 동작한다 — 상위 100종목에는 항상 무조건 그대로 통과(no-op)한다.
 *
 * <p>{@code infra/schema.sql}에 문서화된 "그 외 전 종목" 정책을 그대로 구현한다: 상세
 * 화면을 여는 순간 시세·일봉을 함께 채워 UPSERT하고, 8,500종목을 매일 도는 배치는
 * 만들지 않는다("대부분 아무도 안 보는 종목"). 그래서 두 데이터의 신선도 기준이 다르다.
 *
 * <ul>
 *   <li><b>시세({@code quote_snapshot})</b>는 하루에 한 번(그 종목을 그날 처음 조회할 때만)
 *   갱신한다 — 화면에 보이는 "현재가"라 하루 이상 묵히면 눈에 띄게 틀려 보인다.</li>
 *   <li><b>일봉({@code daily_candle})</b>은 완전히 비어 있을 때만 백필한다 — 이미 값이
 *   있으면(며칠 지났더라도) 다시 부르지 않는다. 차트 모양은 며칠 지나도 거의 안 바뀌고,
 *   보는 사람도 거의 없어서 매번 다시 부를 값어치가 없다.</li>
 * </ul>
 *
 * <p>Toss 호출이 실패해도(네트워크 오류, 상장폐지 등으로 심볼이 없는 경우 등) 예외를
 * 밖으로 던지지 않고 조용히 건너뛴다 — 온디맨드 갱신 실패가 상세 화면 조회 자체를
 * 막으면 안 된다("조회 불가"와 "화면 오류"는 다르다). 이 경우 화면은 기존처럼
 * quote_snapshot이 없는 상태(QUOTE_NOT_FOUND) 그대로 보여준다.
 */
@Service
public class StockOnDemandQuoteService {

    private static final Logger log = LoggerFactory.getLogger(StockOnDemandQuoteService.class);

    /** prev_close를 구할 수 있을 만큼(주말·공휴일이 껴도) 여유 있게 받아온다. */
    private static final int DAILY_CANDLE_BACKFILL_COUNT = 5;
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final ZoneId NY = ZoneId.of("America/New_York");

    private final MarketDataPort marketDataPort;
    private final QuoteSnapshotRepository quoteSnapshotRepository;
    private final QuoteSnapshotPersistenceService quoteSnapshotPersistenceService;
    private final DailyCandleRepository dailyCandleRepository;
    private final DailyCandlePersistenceService dailyCandlePersistenceService;
    private final Clock clock;

    public StockOnDemandQuoteService(
            MarketDataPort marketDataPort,
            QuoteSnapshotRepository quoteSnapshotRepository,
            QuoteSnapshotPersistenceService quoteSnapshotPersistenceService,
            DailyCandleRepository dailyCandleRepository,
            DailyCandlePersistenceService dailyCandlePersistenceService,
            Clock clock
    ) {
        this.marketDataPort = marketDataPort;
        this.quoteSnapshotRepository = quoteSnapshotRepository;
        this.quoteSnapshotPersistenceService = quoteSnapshotPersistenceService;
        this.dailyCandleRepository = dailyCandleRepository;
        this.dailyCandlePersistenceService = dailyCandlePersistenceService;
        this.clock = clock;
    }

    /**
     * 필요하면 시세·일봉을 온디맨드로 채우고, 최신(또는 기존) 시세 스냅샷을 돌려준다.
     *
     * @param stock    조회 대상 종목
     * @param existing 현재 DB에 있는 시세 스냅샷 (없으면 {@code null})
     */
    public QuoteSnapshot ensureQuote(Stock stock, QuoteSnapshot existing) {
        if (Boolean.TRUE.equals(stock.getIsRanked())) {
            return existing;
        }
        ensureDailyCandles(stock);
        if (!isStale(existing)) {
            return existing;
        }
        return refreshQuote(stock);
    }

    /**
     * 랭킹 밖 종목의 일봉이 완전히 비어 있으면 온디맨드로 백필한다. 상세 화면뿐 아니라
     * 캔들 조회({@code CandleQueryService})에서도 독립적으로 호출된다 — 상세를 거치지
     * 않고 캔들만 바로 열어볼 수도 있기 때문이다.
     */
    public void ensureDailyCandles(Stock stock) {
        if (Boolean.TRUE.equals(stock.getIsRanked())) {
            return;
        }
        boolean hasAnyDailyCandle = !dailyCandleRepository
                .findByStockIdOrderByTradeDateDesc(stock.getStockId(), PageRequest.of(0, 1))
                .isEmpty();
        if (hasAnyDailyCandle) {
            return;
        }

        try {
            List<Candle> candles = marketDataPort.fetchCandles(
                    stock.getSymbol(), CandleInterval.ONE_DAY, DAILY_CANDLE_BACKFILL_COUNT);
            dailyCandlePersistenceService.upsert(stock.getStockId(), stock.getCurrency(), candles);
        } catch (RuntimeException exception) {
            log.warn("[on-demand] {} 일봉 백필 실패", stock.getSymbol(), exception);
        }
    }

    private boolean isStale(QuoteSnapshot quote) {
        if (quote == null) return true;
        LocalDate collectedDate = quote.getCollectedAt().atZoneSameInstant(KST).toLocalDate();
        LocalDate today = OffsetDateTime.now(clock).atZoneSameInstant(KST).toLocalDate();
        return !collectedDate.equals(today);
    }

    private QuoteSnapshot refreshQuote(Stock stock) {
        List<PriceQuote> quotes;
        try {
            quotes = marketDataPort.fetchPrices(List.of(stock.getSymbol()));
        } catch (RuntimeException exception) {
            log.warn("[on-demand] {} 시세 조회 실패", stock.getSymbol(), exception);
            return quoteSnapshotRepository.findById(stock.getStockId()).orElse(null);
        }
        if (quotes.isEmpty()) {
            log.warn("[on-demand] {} 시세 응답이 비어 있음", stock.getSymbol());
            return quoteSnapshotRepository.findById(stock.getStockId()).orElse(null);
        }

        OffsetDateTime collectedAt = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);
        quoteSnapshotPersistenceService.saveOrUpdate(List.of(stock), quotes, collectedAt);

        QuoteSnapshot snapshot = quoteSnapshotRepository.findById(stock.getStockId()).orElse(null);
        if (snapshot != null) {
            BigDecimal prevClose = derivePrevClose(stock);
            if (prevClose != null) {
                snapshot.updatePrevClose(prevClose);
            }
        }
        return snapshot;
    }

    /** 방금 채운(또는 이미 있던) 일봉 중, "오늘"(그 시장 기준) 이전의 가장 최신 종가를 고른다. */
    private BigDecimal derivePrevClose(Stock stock) {
        ZoneId marketZone = stock.getMarketCountry() == MarketCountry.US ? NY : KST;
        LocalDate marketToday = OffsetDateTime.now(clock).atZoneSameInstant(marketZone).toLocalDate();

        return dailyCandleRepository
                .findByStockIdOrderByTradeDateDesc(stock.getStockId(), PageRequest.of(0, DAILY_CANDLE_BACKFILL_COUNT))
                .stream()
                .filter(candle -> candle.getTradeDate().isBefore(marketToday))
                .findFirst()
                .map(DailyCandle::getClosePrice)
                .orElse(null);
    }
}
