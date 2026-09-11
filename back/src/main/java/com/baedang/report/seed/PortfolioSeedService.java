package com.baedang.report.seed;

import com.baedang.market.entity.QuoteSnapshot;
import com.baedang.market.repository.QuoteSnapshotRepository;
import com.baedang.stock.entity.MarketCountry;
import com.baedang.stock.entity.Stock;
import com.baedang.stock.repository.StockRepository;
import com.baedang.trading.entity.Holding;
import com.baedang.trading.repository.HoldingRepository;
import com.baedang.user.entity.Account;
import com.baedang.user.entity.User;
import com.baedang.user.repository.AccountRepository;
import com.baedang.user.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * 개발/데모용 합성(시드) 포트폴리오 적재 서비스 (#152 Phase 2 검증 게이트).
 *
 * <p>서비스 가동 초기라 실유저 모집단이 없어 리더보드·유형 분포·컷오프를 검증할 수 없다.
 * 합성 계좌를 대량으로 만들어 그 모집단으로 기능을 만들고 검증한다(설계문서 §8).
 *
 * <p><b>도메인 팩토리 하이브리드((c)):</b> 보유는 {@link Holding#firstBuy}/{@link Holding#addBuy}
 * 로 만들어 원가·평단가 불변식을 코드가 강제하고, 예수금은 {@link Account#debitMarketBuy}
 * 로 차감해 {@code cash_balance = initial_cash − Σ원가} 를 유지한다. 원장은 생략한다
 * (수수료·세금 무시 — 마커로 분리된 시드라 허용).
 *
 * <p><b>시세는 읽기만 한다(공용 상태 불변):</b> {@code quote_snapshot} 은 전 유저 공용이라 시드가
 * 쓰면 실계좌 평가·거래 입력까지 오염된다(+ prevClose·상/하한가도 지워진다). 그래서 합성 시세를
 * 쓰지 않고, 종목의 <b>기존 현재가</b>를 읽어 평단가를 {@code lastPrice ÷ drift(±30%)} 로 잡는다.
 * 손대지 않은 실 시세로 평가하면 {@code return% ≈ drift−1} 이라 수익률 스프레드가 생긴다. 기존
 * 시세가 없는 종목은 제외한다(랭킹 시세 수집이 끝난 환경에서 실행).
 *
 * <p><b>백데이트 체결:</b> 종목마다 매수 {@code trade_order}(FILLED·MARKET) + {@code trade_execution}
 * 한 건을 <b>계좌 개설~현재 사이로 분산된 시각</b>에 적재한다. 종목이 서로 다른 시점에 편입되므로
 * 4주 창 안에서 원가 구성이 변해, 원가 4주 <b>평균</b> 분류기(§6.2)가 스냅샷과 다른 입력을 갖는다.
 * 체결 팩토리({@link com.baedang.trading.entity.TradeExecution#market})는 완전 정합한 주문·환율
 * 근거를 요구해 대량 시드엔 과중하므로 {@link JdbcTemplate}로 직접 적재한다(수수료·세금 0이라
 * {@code net = gross}, BUY 정산 규칙 충족). 보유와 체결이 어긋나지 않게 같은 단가·수량·환율을 쓴다.
 *
 * <p><b>현실 샘플러:</b> 종목은 거래 유니버스({@code is_ranked})에서 <b>시장별로</b>
 * {@code trading_amount} 가중으로 뽑고(계좌마다 국내 편향 무작위 — {@link #marketAwareSample})
 * 비중을 랜덤 배정한다. 유형(MBTI)은 <b>배정이 아니라 emergent</b> 여야 컷오프 튜닝이
 * 유효하다(디자인 매트릭스로 분포를 만들면 순환논리). 유니버스가 개별주에 기울어 있으면
 * 표본도 개별주로 쏠리는데, 그 쏠림 자체가 컷오프 판단의 신호다. 단 시장 간 거래대금 스케일
 * 차이(미국 ~10배)로 국내/해외 축이 퇴화하지 않도록 <b>시장을 먼저 정하고 시장 안에서만
 * 가중</b>한다(검증 게이트 2026-09-11).
 *
 * <p><b>멱등:</b> 이미 시드 회원이 있으면 no-op. 재적재는 시드 정리 후 다시 호출한다.
 * <p><b>선행:</b> 종목 마스터·랭킹 적재가 끝나 {@code is_ranked} 유니버스가 채워져 있어야 한다.
 */
@Service
public class PortfolioSeedService {

    private static final Logger log = LoggerFactory.getLogger(PortfolioSeedService.class);

    /** 재현 가능한 분포를 위해 고정 시드를 쓴다(같은 유니버스면 같은 결과). */
    private static final long RANDOM_SEED = 20260911L;
    private static final String SEED_PASSWORD_HASH = "SEED_ACCOUNT_NO_LOGIN";
    /** 합성 미국 종목 환산 환율(원가·평가 공통). 데모용 상수. */
    private static final BigDecimal SEED_USD_KRW = new BigDecimal("1350");

    private final UserRepository userRepository;
    private final AccountRepository accountRepository;
    private final HoldingRepository holdingRepository;
    private final QuoteSnapshotRepository quoteSnapshotRepository;
    private final StockRepository stockRepository;
    private final JdbcTemplate jdbcTemplate;
    private final BigDecimal initialCash;
    private final int accountCount;
    private final int maxHoldings;
    private final Clock clock;

    public PortfolioSeedService(
            UserRepository userRepository,
            AccountRepository accountRepository,
            HoldingRepository holdingRepository,
            QuoteSnapshotRepository quoteSnapshotRepository,
            StockRepository stockRepository,
            JdbcTemplate jdbcTemplate,
            @Value("${trading.initial-cash}") BigDecimal initialCash,
            @Value("${report.seed.account-count:50}") int accountCount,
            @Value("${report.seed.max-holdings:8}") int maxHoldings,
            Clock clock
    ) {
        this.userRepository = userRepository;
        this.accountRepository = accountRepository;
        this.holdingRepository = holdingRepository;
        this.quoteSnapshotRepository = quoteSnapshotRepository;
        this.stockRepository = stockRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.initialCash = initialCash;
        this.accountCount = accountCount;
        this.maxHoldings = maxHoldings;
        this.clock = clock;
    }

    public record SeedResult(int accounts, int holdings, int quotes, int skipped) {
        static SeedResult skipped(int existing) {
            return new SeedResult(0, 0, 0, existing);
        }
    }

    @Transactional
    public SeedResult seed() {
        long existing = userRepository.countBySeedTrue();
        if (existing > 0) {
            log.info("시드 회원 {}명 이미 존재 — 시딩 건너뜀", existing);
            return SeedResult.skipped((int) existing);
        }

        List<Stock> krUniverse = stockRepository.findByMarketCountryAndIsRankedTrue(MarketCountry.KR);
        List<Stock> usUniverse = stockRepository.findByMarketCountryAndIsRankedTrue(MarketCountry.US);
        if (krUniverse.isEmpty() && usUniverse.isEmpty()) {
            log.warn("is_ranked 유니버스가 비어 있음 — 마스터·랭킹 적재 후 다시 호출하세요");
            return new SeedResult(0, 0, 0, 0);
        }

        // 종목별 "현재 시세"는 기존 quote_snapshot 을 읽어 쓴다. 합성 시세를 새로 쓰지 않는다 —
        // quote_snapshot 은 전 유저 공용이라, 시드가 덮어쓰면 실계좌 평가·거래 입력까지 오염된다.
        Map<Long, BigDecimal> priceByStock = existingPrices(krUniverse, usUniverse);
        if (priceByStock.isEmpty()) {
            log.warn("랭킹 종목의 시세가 없음 — 시세 수집(Toss) 후 다시 호출하세요");
            return new SeedResult(0, 0, 0, 0);
        }

        Random random = new Random(RANDOM_SEED);
        OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        Set<Long> pricedStocks = new HashSet<>();
        int accounts = 0;
        int holdings = 0;

        for (int i = 1; i <= accountCount; i++) {
            User user = userRepository.save(User.createSeed(
                    "seed+" + i + "@seed.baedang.local", SEED_PASSWORD_HASH, "시드투자자" + i));
            // 4~8주 전 개설 → 리포트/리더보드 4주 게이트를 이미 통과(후속 자격 판정용).
            OffsetDateTime openedAt = now.minusDays(28 + random.nextInt(29));
            Account account = accountRepository.save(
                    Account.open(user.getUserId(), 1, initialCash, openedAt));

            holdings += buildPortfolio(account, krUniverse, usUniverse, priceByStock, random, openedAt, now, pricedStocks);
            accounts++;
        }

        log.info("시드 적재 완료 — 계좌 {}, 보유 {}, 평가 기준 시세 {}종목", accounts, holdings, pricedStocks.size());
        return new SeedResult(accounts, holdings, pricedStocks.size(), 0);
    }

    /** 랭킹 유니버스 종목의 기존 시세(현재가)를 stockId→lastPrice 로 모은다. 없으면 제외. */
    private Map<Long, BigDecimal> existingPrices(List<Stock> krUniverse, List<Stock> usUniverse) {
        List<Long> ids = new ArrayList<>();
        krUniverse.forEach(s -> ids.add(s.getStockId()));
        usUniverse.forEach(s -> ids.add(s.getStockId()));
        Map<Long, BigDecimal> prices = new HashMap<>();
        for (QuoteSnapshot q : quoteSnapshotRepository.findByStockIdIn(ids)) {
            if (q.getLastPrice() != null && q.getLastPrice().signum() > 0) {
                prices.put(q.getStockId(), q.getLastPrice());
            }
        }
        return prices;
    }

    /** 한 계좌의 보유를 현실 샘플러로 구성하고, 총원가만큼 예수금을 차감한다. 생성한 보유 수 반환. */
    private int buildPortfolio(Account account, List<Stock> krUniverse, List<Stock> usUniverse,
                               Map<Long, BigDecimal> priceByStock, Random random,
                               OffsetDateTime at, OffsetDateTime now, Set<Long> pricedStocks) {
        int k = 1 + random.nextInt(maxHoldings);
        List<Stock> picked = marketAwareSample(krUniverse, usUniverse, k, random);
        double[] weights = randomWeights(picked.size(), random);
        // 초기자본의 30~95% 를 투자(나머지는 현금).
        BigDecimal totalInvest = initialCash
                .multiply(BigDecimal.valueOf(0.30 + random.nextDouble() * 0.65))
                .setScale(0, RoundingMode.DOWN);

        BigDecimal spent = BigDecimal.ZERO;
        int created = 0;
        for (int j = 0; j < picked.size(); j++) {
            Stock stock = picked.get(j);
            BigDecimal marketPrice = priceByStock.get(stock.getStockId());
            if (marketPrice == null || marketPrice.signum() <= 0) {
                continue; // 기존 시세가 없으면 평가할 수 없어 건너뛴다(시세를 새로 쓰지 않는다).
            }
            boolean us = stock.getMarketCountry() == MarketCountry.US;
            // 합성 원가: 현재 시세 대비 드리프트(±30%)로 평단가를 잡아, 손대지 않은 실 시세로 평가하면
            // return% ≈ 드리프트−1 이 된다(공용 시세 오염 없이 수익률 스프레드 확보).
            double drift = 0.70 + random.nextDouble() * 0.60;
            BigDecimal avgBuyPrice = marketPrice.divide(BigDecimal.valueOf(drift), 4, RoundingMode.HALF_UP);
            if (avgBuyPrice.signum() <= 0) {
                continue;
            }
            BigDecimal priceKrw = us ? avgBuyPrice.multiply(SEED_USD_KRW) : avgBuyPrice;

            BigDecimal targetCost = totalInvest.multiply(BigDecimal.valueOf(weights[j]));
            long qty = targetCost.divide(priceKrw, 0, RoundingMode.DOWN).longValueExact();
            if (qty <= 0) {
                continue; // 이 비중으로는 1주도 못 사면 건너뛴다(원가 ≤ 목표 유지).
            }
            BigDecimal quantity = BigDecimal.valueOf(qty);
            BigDecimal krwCost = priceKrw.multiply(quantity);
            BigDecimal usdCost = us ? avgBuyPrice.multiply(quantity) : BigDecimal.ZERO;

            // 종목마다 매수 시각을 개설~현재 사이로 분산 → 4주 창 안에서 구성이 변한다.
            OffsetDateTime buyAt = staggeredBuyTime(at, now, random);
            holdingRepository.save(Holding.firstBuy(
                    account.getAccountId(), stock.getStockId(), quantity, usdCost, krwCost, buyAt));
            insertBuyExecution(account.getAccountId(), stock.getStockId(), quantity,
                    avgBuyPrice, us ? SEED_USD_KRW : BigDecimal.ONE, krwCost, buyAt);
            spent = spent.add(krwCost);
            pricedStocks.add(stock.getStockId());
            created++;
        }

        if (spent.signum() > 0) {
            account.debitMarketBuy(spent); // cash_balance = initial − Σ원가 (managed 엔티티, 트랜잭션 커밋 시 flush)
        }
        return created;
    }

    /** 개설~현재 사이의 무작위 매수 시각. 종목마다 달라 4주 창 안에서 구성이 변한다. */
    private static OffsetDateTime staggeredBuyTime(OffsetDateTime openedAt, OffsetDateTime now, Random random) {
        long span = Duration.between(openedAt, now).getSeconds();
        return span <= 0 ? openedAt : openedAt.plusSeconds((long) (random.nextDouble() * span));
    }

    /**
     * 매수 체결 한 건을 {@code trade_order}(FILLED·MARKET) + {@code trade_execution} 로 직접 적재한다.
     * 수수료·세금 0이라 {@code net = gross} 라 BUY 정산 규칙({@code net = gross + fee})을 만족한다.
     * 보유와 어긋나지 않게 같은 단가·수량·환율을 쓰고, 원화 거래대금은 정수 원으로 반올림한다.
     */
    private void insertBuyExecution(long accountId, long stockId, BigDecimal quantity,
                                    BigDecimal nativePrice, BigDecimal exchangeRate, BigDecimal krwCost,
                                    OffsetDateTime at) {
        BigDecimal grossKrw = krwCost.setScale(0, RoundingMode.HALF_UP);
        OffsetDateTime ts = at;
        Long orderId = jdbcTemplate.queryForObject("""
                INSERT INTO trade_order(
                        account_id, stock_id, client_order_id, side, order_type, quantity, status,
                        quote_at, exchange_rate, executed_price, gross_amount, fee, tax, net_amount,
                        filled_quantity, execution_count, last_executed_at, reserved_cash, ordered_at, closed_at)
                VALUES (?, ?, ?, 'BUY', 'MARKET', ?, 'FILLED', ?, ?, ?, ?, 0, 0, ?, ?, 1, ?, 0, ?, ?)
                RETURNING order_id
                """, Long.class,
                accountId, stockId, UUID.randomUUID(), quantity, ts, exchangeRate, nativePrice,
                grossKrw, grossKrw, quantity, ts, ts, ts);

        jdbcTemplate.update("""
                INSERT INTO trade_execution(
                        order_id, execution_key, sequence_no, quantity, price, exchange_rate,
                        sec_fee_usd, gross_amount_krw, fee_krw, tax_krw, net_amount_krw, quote_at, executed_at, book_level_id)
                VALUES (?, ?, 1, ?, ?, ?, 0, ?, 0, 0, ?, ?, ?, NULL)
                """,
                orderId, UUID.randomUUID(), quantity, nativePrice, exchangeRate, grossKrw, grossKrw, ts, ts);
    }

    /**
     * 시장 인지형 비복원 추출. 계좌마다 국내 편향 {@code p}(0=전부 해외, 1=전부 국내)를 무작위로
     * 주고, 종목마다 시장을 {@code p} 확률로 고른 뒤 <b>그 시장 안에서만</b> {@code trading_amount}
     * 가중으로 뽑는다.
     *
     * <p>시장을 섞어 하나의 풀로 가중하면, 미국 종목의 {@code trading_amount}(원화 명목)가 국내의
     * ~10배라 표본이 미국으로 쏠려 국내/해외 축이 전부 "해외"로 퇴화한다(검증 게이트 2026-09-11
     * 발견). 시장을 먼저 정하고 시장 내에서만 가중하면 국내/해외 축이 계좌별로 분포를 갖는다.
     */
    private static List<Stock> marketAwareSample(List<Stock> kr, List<Stock> us, int k, Random random) {
        double domesticBias = random.nextDouble();
        List<Stock> krPool = new ArrayList<>(kr);
        List<Stock> usPool = new ArrayList<>(us);
        List<Stock> chosen = new ArrayList<>();
        int limit = Math.min(k, krPool.size() + usPool.size());
        for (int n = 0; n < limit; n++) {
            boolean wantKr = random.nextDouble() < domesticBias;
            List<Stock> pool = wantKr ? krPool : usPool;
            if (pool.isEmpty()) {
                pool = wantKr ? usPool : krPool; // 원하는 시장이 소진되면 다른 시장에서 채운다
            }
            chosen.add(pool.remove(weightedPickIndex(pool, random)));
        }
        return chosen;
    }

    /** 풀 안에서 {@code trading_amount} 가중으로 한 종목 인덱스를 뽑는다. 인기 종목일수록 자주 뽑힌다. */
    private static int weightedPickIndex(List<Stock> pool, Random random) {
        double total = 0;
        for (Stock s : pool) {
            total += weight(s);
        }
        double dart = random.nextDouble() * total;
        double acc = 0;
        for (int p = 0; p < pool.size(); p++) {
            acc += weight(pool.get(p));
            if (dart <= acc) {
                return p;
            }
        }
        return pool.size() - 1;
    }

    private static double weight(Stock stock) {
        BigDecimal ta = stock.getTradingAmount();
        return ta == null || ta.signum() <= 0 ? 1.0 : ta.doubleValue();
    }

    /** 랜덤 양수 비중을 합이 1이 되도록 정규화. */
    private static double[] randomWeights(int k, Random random) {
        double[] w = new double[k];
        double sum = 0;
        for (int i = 0; i < k; i++) {
            w[i] = random.nextDouble() + 0.05;
            sum += w[i];
        }
        for (int i = 0; i < k; i++) {
            w[i] /= sum;
        }
        return w;
    }
}
