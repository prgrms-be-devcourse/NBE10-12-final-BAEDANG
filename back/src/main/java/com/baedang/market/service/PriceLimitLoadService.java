package com.baedang.market.service;

import com.baedang.global.clients.FixedIntervalGate;
import com.baedang.market.entity.QuoteSnapshot;
import com.baedang.market.port.MarketCalendarDay;
import com.baedang.market.port.MarketDataPort;
import com.baedang.market.port.PriceLimits;
import com.baedang.market.repository.PriceLimitRepository;
import com.baedang.market.repository.QuoteSnapshotRepository;
import com.baedang.stock.entity.MarketCountry;
import com.baedang.stock.entity.Stock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

/** 상하한가 전용 중복 요청 억제와 실패 대기. 일봉/현재가 잠금과는 공유하지 않습니다. */
@Service
@Transactional(propagation = Propagation.NEVER)
public class PriceLimitLoadService {
    private static final Logger log = LoggerFactory.getLogger(PriceLimitLoadService.class);
    private static final Duration RETRY_DELAY = Duration.ofMinutes(1);
    private static final int MAX_PENDING = 1000;
    private final Map<Long, Attempt> attempts = new HashMap<>();
    private final FixedIntervalGate requests;
    private final MarketDataPort data;
    private final MarketTradingDayPolicy tradingDays;
    private final QuoteSnapshotRepository quotes;
    private final PriceLimitRepository persistence;
    private final Clock clock;
    private final boolean enabled;

    @Autowired
    public PriceLimitLoadService(MarketDataPort data, MarketTradingDayPolicy tradingDays,
            QuoteSnapshotRepository quotes, PriceLimitRepository persistence, Clock clock,
            @Value("${toss.enabled:false}") boolean enabled) {
        this(data, tradingDays, quotes, persistence, clock, enabled, new FixedIntervalGate(2));
    }

    PriceLimitLoadService(MarketDataPort data, MarketTradingDayPolicy tradingDays,
            QuoteSnapshotRepository quotes, PriceLimitRepository persistence, Clock clock,
            boolean enabled, FixedIntervalGate requests) {
        this.requests = requests;
        this.data = data;
        this.tradingDays = tradingDays;
        this.quotes = quotes;
        this.persistence = persistence;
        this.clock = clock;
        this.enabled = enabled;
    }

    /** 배경 수집은 속도 제한 순서를 기다려 누락 대상 전체를 처리합니다. */
    public void ensure(Stock stock) {
        ensure(stock, true);
    }

    /** 상세 조회는 상하한가 전용 게이트에서 대기하지 않습니다. */
    public void ensureForDisplay(Stock stock) {
        ensure(stock, false);
    }

    private void ensure(Stock stock, boolean waitForPermit) {
        if (!enabled || stock.getMarketCountry() != MarketCountry.KR) return;
        Instant started = clock.instant();
        LocalDate date = started.atZone(MarketCountry.KR.zoneId()).toLocalDate();
        Attempt attempt = claim(stock.getStockId(), date, started);
        if (attempt == null) return;
        boolean success = false;
        try {
            MarketCalendarDay day = tradingDays.calendar(MarketCountry.KR, date);
            // 장전 갱신 보장 시점이 명세에 없으므로 정규장 중에만 새 값을 수집합니다.
            if (!day.isRegularSessionAt(started)) { success = true; return; }
            QuoteSnapshot quote = quotes.findById(stock.getStockId()).orElse(null);
            if (quote == null) return;
            if (date.equals(quote.getPriceLimitDate())) { success = true; return; }
            if (waitForPermit) requests.acquire();
            else if (!requests.tryAcquire()) {
                // 호출을 시도하지 않은 경우 실패 대기를 남기지 않아 다음 조회에서 다시 확인합니다.
                success = true;
                return;
            }
            if (!day.isRegularSessionAt(clock.instant())) return;
            PriceLimits limits = data.fetchPriceLimits(stock.getSymbol());
            Instant received = clock.instant();
            if (limits == null || limits.timestamp() == null || !"KRW".equals(limits.currency())
                    || !date.equals(limits.timestamp().atZoneSameInstant(MarketCountry.KR.zoneId()).toLocalDate())
                    || limits.timestamp().toInstant().isAfter(received)
                    || !date.equals(received.atZone(MarketCountry.KR.zoneId()).toLocalDate())
                    || !validPrice(limits.upperLimit()) || !validPrice(limits.lowerLimit())
                    || limits.lowerLimit().compareTo(limits.upperLimit()) > 0) {
                throw new IllegalArgumentException("상하한가 날짜 또는 가격이 유효하지 않습니다");
            }
            success = persistence.save(stock.getStockId(), date, limits);
        } catch (RuntimeException exception) {
            log.warn("상하한가 수집 보류: stockId={} type={}", stock.getStockId(), exception.getClass().getSimpleName());
        } finally {
            finish(stock.getStockId(), attempt, success);
        }
    }

    private boolean validPrice(BigDecimal price) {
        return price != null && price.signum() > 0 && price.stripTrailingZeros().scale() <= 4
                && price.compareTo(new BigDecimal("1000000000000000")) < 0;
    }

    private synchronized Attempt claim(Long id, LocalDate date, Instant now) {
        attempts.entrySet().removeIf(entry -> !entry.getValue().running
                && (!entry.getValue().date.equals(date) || !now.isBefore(entry.getValue().retryAt)));
        if (attempts.containsKey(id) || attempts.size() >= MAX_PENDING) return null;
        Attempt attempt = new Attempt(date);
        attempts.put(id, attempt);
        return attempt;
    }

    private synchronized void finish(Long id, Attempt attempt, boolean success) {
        if (success) attempts.remove(id, attempt);
        else {
            attempt.running = false;
            attempt.retryAt = clock.instant().plus(RETRY_DELAY);
        }
    }

    private static final class Attempt {
        private final LocalDate date;
        private boolean running = true;
        private Instant retryAt;
        private Attempt(LocalDate date) { this.date = date; }
    }

    /** 당일 정규장에는 당일 값, 장외에는 화면 시세와 같은 거래일의 값만 노출합니다. */
    public boolean canDisplay(Stock stock, QuoteSnapshot quote) {
        if (stock.getMarketCountry() != MarketCountry.KR || quote == null || quote.getPriceLimitDate() == null) return false;
        try {
            Instant now = clock.instant();
            LocalDate today = now.atZone(MarketCountry.KR.zoneId()).toLocalDate();
            LocalDate expected = tradingDays.calendar(MarketCountry.KR, today).isRegularSessionAt(now)
                    ? today : tradingDays.quoteTradeDate(MarketCountry.KR, quote.getQuoteAt().toInstant()).orElse(null);
            return quote.getPriceLimitDate().equals(expected);
        } catch (RuntimeException exception) {
            return false;
        }
    }
}
