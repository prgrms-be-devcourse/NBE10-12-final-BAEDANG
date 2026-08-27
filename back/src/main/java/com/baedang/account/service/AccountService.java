package com.baedang.account.service;

import com.baedang.account.dto.AccountSummaryResponse;
import com.baedang.account.dto.HoldingsResponse;
import com.baedang.account.support.HoldingValuation;
import com.baedang.global.error.BusinessException;
import com.baedang.global.error.ErrorCode;
import com.baedang.market.entity.ExchangeRate;
import com.baedang.market.entity.QuoteSnapshot;
import com.baedang.market.repository.ExchangeRateRepository;
import com.baedang.market.repository.QuoteSnapshotRepository;
import com.baedang.stock.entity.Stock;
import com.baedang.stock.repository.StockRepository;
import com.baedang.trading.entity.Holding;
import com.baedang.trading.repository.HoldingRepository;
import com.baedang.user.entity.Account;
import com.baedang.user.entity.AccountStatus;
import com.baedang.user.repository.AccountRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 계좌·마이페이지 조회 서비스.
 *
 * <p>평가금액은 백엔드가 최신 시세·환율로 원화 집계합니다 (믿어야 하는 값).
 * 미실현 손익만 제공하며(1주차), 실현 손익은 2주차에 분리됩니다.
 */
@Service
@Transactional(readOnly = true)
public class AccountService {

    private static final String USD = "USD";
    private static final String KRW = "KRW";

    /**
     * 시세가 "실시간"으로 갱신 중인지 판정하는 신선도 임계값.
     *
     * <p>정규장 중에는 5초 수집기가 {@code quote_at} 을 계속 갱신하고, 장 밖에서는 전일 종가에 멈춥니다
     * (수 시간~수 일 정체). 두 상태의 간격이 크므로 임계값에 민감하지 않습니다 — 수집 지연·GC 로 인한
     * 깜빡임을 피하려고 5초보다 넉넉하게, 장외 정체(시간 단위)보다는 훨씬 짧게 2분으로 둡니다.
     *
     * <p>장 운영 캘린더({@code MarketSessionProvider}) 대신 {@code quote_at} 을 쓰는 이유:
     * (1) 10초 폴링·읽기 트랜잭션 안에서 외부(Toss) 호출/예외로 엔드포인트를 죽이지 않고,
     * (2) 장이 열려 있어도 랭킹에서 빠진 보유 종목은 시세가 정지되는데 이 방식이 그걸 올바르게 잡아냅니다.
     */
    private static final Duration REALTIME_STALE_THRESHOLD = Duration.ofMinutes(2);

    private final AccountRepository accountRepository;
    private final HoldingRepository holdingRepository;
    private final QuoteSnapshotRepository quoteSnapshotRepository;
    private final ExchangeRateRepository exchangeRateRepository;
    private final StockRepository stockRepository;
    private final HoldingValuator holdingValuator;
    private final Clock clock;

    public AccountService(AccountRepository accountRepository,
                          HoldingRepository holdingRepository,
                          QuoteSnapshotRepository quoteSnapshotRepository,
                          ExchangeRateRepository exchangeRateRepository,
                          StockRepository stockRepository,
                          HoldingValuator holdingValuator,
                          Clock clock) {
        this.accountRepository = accountRepository;
        this.holdingRepository = holdingRepository;
        this.quoteSnapshotRepository = quoteSnapshotRepository;
        this.exchangeRateRepository = exchangeRateRepository;
        this.stockRepository = stockRepository;
        this.holdingValuator = holdingValuator;
        this.clock = clock;
    }

    public AccountSummaryResponse getSummary(Long userId) {
        AccountValuation valued = valuateActiveAccount(userId);
        Account account = valued.account();

        BigDecimal stockValue = sum(valued.valuations(), HoldingValuation::evalWon);
        BigDecimal costBasis = sum(valued.valuations(), HoldingValuation::costWon);
        BigDecimal unrealizedPnl = stockValue.subtract(costBasis);
        BigDecimal totalAsset = account.getCashBalance().add(stockValue);
        BigDecimal pnlRate = costBasis.signum() > 0
                ? unrealizedPnl.divide(costBasis, 4, RoundingMode.HALF_UP)
                : null;

        OffsetDateTime asOf = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        return AccountSummaryResponse.of(
                account, stockValue, totalAsset, unrealizedPnl, pnlRate, valued.usdKrwRate(), asOf);
    }

    public HoldingsResponse getHoldings(Long userId) {
        AccountValuation valued = valuateActiveAccount(userId);
        Map<Long, Stock> stocks = stocksByStockId(valued.holdings());
        Instant now = clock.instant();

        // 평가금액 내림차순 — 큰 비중부터 보여준다.
        List<HoldingsResponse.Item> items = valued.valuations().stream()
                .sorted(Comparator.comparing(HoldingValuation::evalWon).reversed())
                .map(valuation -> HoldingsResponse.Item.of(
                        valuation,
                        stocks.get(valuation.stockId()),
                        isRealtime(valued.quotes().get(valuation.stockId()), now)))
                .toList();

        OffsetDateTime asOf = OffsetDateTime.ofInstant(now, ZoneOffset.UTC);
        return new HoldingsResponse(items, asOf);
    }

    /** 요약(#1)·보유 목록(#2)이 공유하는 조회·평가 패스: 계좌 → 보유 → 시세 → 환율 → 평가. */
    private AccountValuation valuateActiveAccount(Long userId) {
        Account account = accountRepository.findByUserIdAndStatus(userId, AccountStatus.ACTIVE)
                .orElseThrow(() -> new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND));

        List<Holding> holdings =
                holdingRepository.findByAccountIdAndQuantityGreaterThan(account.getAccountId(), BigDecimal.ZERO);

        Map<Long, QuoteSnapshot> quotes = quotesByStockId(holdings);
        BigDecimal usdKrwRate = latestUsdKrwRate();
        List<HoldingValuation> valuations = holdingValuator.valuate(holdings, quotes, usdKrwRate);

        return new AccountValuation(account, holdings, quotes, valuations, usdKrwRate);
    }

    private record AccountValuation(
            Account account,
            List<Holding> holdings,
            Map<Long, QuoteSnapshot> quotes,
            List<HoldingValuation> valuations,
            BigDecimal usdKrwRate
    ) {
    }

    private Map<Long, QuoteSnapshot> quotesByStockId(List<Holding> holdings) {
        if (holdings.isEmpty()) {
            return Map.of();
        }
        List<Long> stockIds = holdings.stream().map(Holding::getStockId).toList();
        return quoteSnapshotRepository.findByStockIdIn(stockIds).stream()
                .collect(Collectors.toMap(QuoteSnapshot::getStockId, Function.identity()));
    }

    private Map<Long, Stock> stocksByStockId(List<Holding> holdings) {
        if (holdings.isEmpty()) {
            return Map.of();
        }
        List<Long> stockIds = holdings.stream().map(Holding::getStockId).toList();
        return stockRepository.findByStockIdIn(stockIds).stream()
                .collect(Collectors.toMap(Stock::getStockId, Function.identity()));
    }

    /** {@code quote_at} 이 임계값 안이면 실시간. 시세가 없으면(방어적) 실시간 아님. */
    private boolean isRealtime(QuoteSnapshot quote, Instant now) {
        return quote != null
                && quote.getQuoteAt().toInstant().isAfter(now.minus(REALTIME_STALE_THRESHOLD));
    }

    /** 응답에 노출하고 외화 평가에도 쓰는 최신 USD/KRW 환율. 표시용 mid_rate 우선. */
    private BigDecimal latestUsdKrwRate() {
        return exchangeRateRepository
                .findTopByBaseCurrencyAndQuoteCurrencyOrderByRateAtDesc(USD, KRW)
                .map(this::displayRate)
                .orElse(null);
    }

    private BigDecimal displayRate(ExchangeRate exchangeRate) {
        return exchangeRate.getMidRate() != null ? exchangeRate.getMidRate() : exchangeRate.getRate();
    }

    private BigDecimal sum(List<HoldingValuation> valuations, Function<HoldingValuation, BigDecimal> field) {
        return valuations.stream().map(field).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
