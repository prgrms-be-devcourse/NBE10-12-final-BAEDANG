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
import com.baedang.market.service.LatestCompletedTradingDayResolver;
import com.baedang.market.service.QuoteSnapshotPersistenceService;
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
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 종목 상세 조회에 필요한 시세와 일봉을 온디맨드로 채운다.
 *
 * <p>상위 100종목의 시세는 {@code QuoteSnapshotScheduler}(5초)가 유지하므로 온디맨드로
 * 갱신하지 않는다. 일봉 차트는 랭킹 여부와 관계없이 저장 이력이 부족할 수 있으므로,
 * 상세 또는 차트 최초 요청에서 최신 200개를 한 번 백필한다.
 *
 * <p>{@code infra/schema.sql}에 문서화된 "그 외 전 종목" 정책을 그대로 구현한다: 상세
 * 화면을 여는 순간 시세·일봉을 함께 채워 UPSERT하고, 8,500종목을 매일 도는 배치는
 * 만들지 않는다("대부분 아무도 안 보는 종목"). 그래서 두 데이터의 신선도 기준이 다르다.
 *
 * <ul>
 *   <li><b>시세({@code quote_snapshot})</b>는 하루에 한 번(그 종목을 그날 처음 조회할 때만)
 *   갱신한다 — 화면에 보이는 "현재가"라 하루 이상 묵히면 눈에 띄게 틀려 보인다.</li>
 *   <li><b>일봉({@code daily_candle})</b>은 과거 200개 백필이 끝나지 않았거나 DB 최신
 *   거래일이 시장의 최신 확정 거래일보다 오래됐을 때 최신 200개를 요청한다. 차트 기간을
 *   바꿔도 같은 DB 데이터와 완료 기록을 재사용하므로 추가 외부 호출이 발생하지 않는다.</li>
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

    /** Toss 단일 호출 상한. 페이지네이션 없이 상세·차트가 공유할 최신 일봉을 확보한다. */
    private static final int DAILY_CANDLE_BACKFILL_COUNT = 200;
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    /** {@code CandleQueryService.refreshMinuteCandlesIfNeeded}와 같은 개수·같은 이유. */
    private static final int REFRESH_LOCK_STRIPES = 64;

    private final MarketDataPort marketDataPort;
    private final QuoteSnapshotRepository quoteSnapshotRepository;
    private final QuoteSnapshotPersistenceService quoteSnapshotPersistenceService;
    private final DailyCandleRepository dailyCandleRepository;
    private final DailyCandlePersistenceService dailyCandlePersistenceService;
    private final OnDemandDailyCandleBackfillTracker onDemandDailyCandleBackfillTracker;
    private final LatestCompletedTradingDayResolver latestCompletedTradingDayResolver;
    private final Clock clock;
    private final ReentrantLock[] refreshLocks = createRefreshLocks();

    public StockOnDemandQuoteService(
            MarketDataPort marketDataPort,
            QuoteSnapshotRepository quoteSnapshotRepository,
            QuoteSnapshotPersistenceService quoteSnapshotPersistenceService,
            DailyCandleRepository dailyCandleRepository,
            DailyCandlePersistenceService dailyCandlePersistenceService,
            OnDemandDailyCandleBackfillTracker onDemandDailyCandleBackfillTracker,
            LatestCompletedTradingDayResolver latestCompletedTradingDayResolver,
            Clock clock
    ) {
        this.marketDataPort = marketDataPort;
        this.quoteSnapshotRepository = quoteSnapshotRepository;
        this.quoteSnapshotPersistenceService = quoteSnapshotPersistenceService;
        this.dailyCandleRepository = dailyCandleRepository;
        this.dailyCandlePersistenceService = dailyCandlePersistenceService;
        this.onDemandDailyCandleBackfillTracker = onDemandDailyCandleBackfillTracker;
        this.latestCompletedTradingDayResolver = latestCompletedTradingDayResolver;
        this.clock = clock;
    }

    /**
     * 필요하면 시세·일봉을 온디맨드로 채우고, 최신(또는 기존) 시세 스냅샷을 돌려준다.
     *
     * @param stock    조회 대상 종목
     * @param existing 현재 DB에 있는 시세 스냅샷 (없으면 {@code null})
     */
    public QuoteSnapshot ensureQuote(Stock stock, QuoteSnapshot existing) {
        ensureDailyCandles(stock);
        if (Boolean.TRUE.equals(stock.getIsRanked())) {
            return existing;
        }
        if (!isStale(existing)) {
            return existing;
        }
        return refreshQuoteIfStillStale(stock);
    }

    /**
     * 과거 이력이 부족하거나 최신 확정 거래일의 일봉이 없으면 최신 200개를 한 번
     * 온디맨드로 조회한다. 상세 화면과 캔들 조회({@code CandleQueryService})가 같은 데이터를
     * 공유하므로 어떤 경로로 먼저 진입하거나 차트 기간을 바꾸더라도 최신 상태에서는
     * 추가 호출 없이 DB에서 응답한다.
     *
     * <p>{@code CandleQueryService.refreshMinuteCandlesIfNeeded}와 같은 이중 확인(더블
     * 체크) 락 패턴을 쓴다 — 같은 종목에 짧은 시간 안에 여러 요청이 몰려도(동시 사용자)
     * 실제 Toss 호출과 저장은 한 번만 일어나게 하기 위해서다.
     */
    public void ensureDailyCandles(Stock stock) {
        boolean initialDailyCandleBackfillSatisfied =
                isInitialDailyCandleBackfillSatisfied(stock.getStockId());
        boolean tradingDayResolved = initialDailyCandleBackfillSatisfied;
        Optional<LocalDate> expectedTradeDate = initialDailyCandleBackfillSatisfied
                ? latestCompletedTradingDayResolver.resolve(stock.getMarketCountry())
                : Optional.empty();
        if (initialDailyCandleBackfillSatisfied
                && isLatestRefreshSatisfied(stock.getStockId(), expectedTradeDate)) {
            return;
        }

        ReentrantLock lock = lockFor(stock.getStockId());
        lock.lock();
        try {
            initialDailyCandleBackfillSatisfied =
                    isInitialDailyCandleBackfillSatisfied(stock.getStockId());
            if (initialDailyCandleBackfillSatisfied) {
                if (!tradingDayResolved) {
                    expectedTradeDate = latestCompletedTradingDayResolver.resolve(stock.getMarketCountry());
                }
                if (isLatestRefreshSatisfied(stock.getStockId(), expectedTradeDate)) return;
            }

            List<Candle> candles = marketDataPort.fetchCandles(
                    stock.getSymbol(), CandleInterval.ONE_DAY, DAILY_CANDLE_BACKFILL_COUNT);
            dailyCandlePersistenceService.upsert(stock.getStockId(), stock.getCurrency(), candles);
            onDemandDailyCandleBackfillTracker.markInitialBackfillCompleted(stock.getStockId());
            if (expectedTradeDate.isPresent()) {
                onDemandDailyCandleBackfillTracker.markRefreshedThrough(
                        stock.getStockId(), expectedTradeDate.get());
            }
        } catch (RuntimeException exception) {
            log.warn("[on-demand] {} 일봉 백필 실패", stock.getSymbol(), exception);
        } finally {
            lock.unlock();
        }
    }

    private boolean isInitialDailyCandleBackfillSatisfied(Long stockId) {
        return onDemandDailyCandleBackfillTracker.isInitialBackfillCompleted(stockId)
                || dailyCandleRepository.hasAtLeastCandles(
                        stockId,
                        DAILY_CANDLE_BACKFILL_COUNT
                );
    }

    private boolean isLatestRefreshSatisfied(
            Long stockId,
            Optional<LocalDate> expectedTradeDate
    ) {
        if (expectedTradeDate.isEmpty()) return true;
        if (onDemandDailyCandleBackfillTracker.wasRefreshedThrough(
                stockId,
                expectedTradeDate.get()
        )) {
            return true;
        }
        return dailyCandleRepository.findTopByStockIdOrderByTradeDateDesc(stockId)
                .map(DailyCandle::getTradeDate)
                .filter(latest -> !latest.isBefore(expectedTradeDate.get()))
                .isPresent();
    }

    /** 락을 잡은 뒤 다시 한번 신선도를 확인한다 — 락 대기 중 다른 스레드가 이미 갱신했을 수 있다. */
    private QuoteSnapshot refreshQuoteIfStillStale(Stock stock) {
        ReentrantLock lock = lockFor(stock.getStockId());
        lock.lock();
        try {
            QuoteSnapshot current = quoteSnapshotRepository.findById(stock.getStockId()).orElse(null);
            if (!isStale(current)) {
                return current;
            }
            return refreshQuote(stock);
        } finally {
            lock.unlock();
        }
    }

    private ReentrantLock lockFor(Long stockId) {
        int index = Long.hashCode(stockId) & (REFRESH_LOCK_STRIPES - 1);
        return refreshLocks[index];
    }

    private static ReentrantLock[] createRefreshLocks() {
        ReentrantLock[] locks = new ReentrantLock[REFRESH_LOCK_STRIPES];
        for (int index = 0; index < locks.length; index++) {
            locks[index] = new ReentrantLock();
        }
        return locks;
    }

    private boolean isStale(QuoteSnapshot quote) {
        if (quote == null) return true;
        LocalDate collectedDate = quote.getCollectedAt().atZoneSameInstant(KST).toLocalDate();
        LocalDate today = clock.instant().atZone(KST).toLocalDate();
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

        OffsetDateTime collectedAt = clock.instant().atOffset(ZoneOffset.UTC);
        quoteSnapshotPersistenceService.saveOrUpdate(List.of(stock), quotes, collectedAt);

        QuoteSnapshot snapshot = quoteSnapshotRepository.findById(stock.getStockId()).orElse(null);
        if (snapshot != null) {
            BigDecimal prevClose = derivePrevClose(stock);
            if (prevClose != null) {
                snapshot.updatePrevClose(prevClose);
                // findById가 이미 끝난 읽기 전용 트랜잭션 밖이라 snapshot은 detached 상태다 —
                // 여기서 명시적으로 save하지 않으면 변경이 이 응답에만 반영되고 DB에는
                // 저장되지 않는다(제미나이 코드 리뷰, PR #80).
                snapshot = quoteSnapshotRepository.save(snapshot);
            }
        }
        return snapshot;
    }

    /** 방금 채운(또는 이미 있던) 일봉 중, "오늘"(그 시장 기준) 이전의 가장 최신 종가를 고른다. */
    private BigDecimal derivePrevClose(Stock stock) {
        LocalDate marketToday = clock.instant()
                .atZone(stock.getMarketCountry().zoneId())
                .toLocalDate();

        return dailyCandleRepository
                .findByStockIdOrderByTradeDateDesc(stock.getStockId(), PageRequest.of(0, DAILY_CANDLE_BACKFILL_COUNT))
                .stream()
                .filter(candle -> candle.getTradeDate().isBefore(marketToday))
                .findFirst()
                .map(DailyCandle::getClosePrice)
                .orElse(null);
    }
}
