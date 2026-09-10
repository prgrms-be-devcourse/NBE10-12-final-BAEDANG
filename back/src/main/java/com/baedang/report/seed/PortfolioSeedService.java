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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * 개발/데모용 합성(시드) 포트폴리오 적재 서비스 (#152 Phase 2 검증 게이트).
 *
 * <p>서비스 가동 초기라 실유저 모집단이 없어 리더보드·유형 분포·컷오프를 검증할 수 없다.
 * 합성 계좌를 대량으로 만들어 그 모집단으로 기능을 만들고 검증한다(설계문서 §8).
 *
 * <p><b>도메인 팩토리 하이브리드((c)):</b> 보유는 {@link Holding#firstBuy}/{@link Holding#addBuy}
 * 로 만들어 원가·평단가 불변식을 코드가 강제하고, 예수금은 {@link Account#debitMarketBuy}
 * 로 차감해 {@code cash_balance = initial_cash − Σ원가} 를 유지한다. 체결/원장은 생략한다
 * (수수료 무시 — 마커로 분리된 시드라 허용). 4주 성과·변화 감지용 백데이트 체결은 후속.
 *
 * <p><b>현실 샘플러:</b> 종목은 거래 유니버스({@code is_ranked})에서 {@code trading_amount}
 * 가중으로 뽑고 비중을 랜덤 배정한다. 유형(MBTI)은 <b>배정이 아니라 emergent</b> 여야
 * 컷오프 튜닝이 유효하다(디자인 매트릭스로 분포를 만들면 순환논리). 유니버스가 개별주에
 * 기울어 있으면 표본도 개별주로 쏠리는데, 그 쏠림 자체가 컷오프 판단의 신호다.
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

        List<Stock> universe = new ArrayList<>();
        universe.addAll(stockRepository.findByMarketCountryAndIsRankedTrue(MarketCountry.KR));
        universe.addAll(stockRepository.findByMarketCountryAndIsRankedTrue(MarketCountry.US));
        if (universe.isEmpty()) {
            log.warn("is_ranked 유니버스가 비어 있음 — 마스터·랭킹 적재 후 다시 호출하세요");
            return new SeedResult(0, 0, 0, 0);
        }

        Random random = new Random(RANDOM_SEED);
        OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        Set<Long> quotedStocks = new HashSet<>();
        int accounts = 0;
        int holdings = 0;

        for (int i = 1; i <= accountCount; i++) {
            User user = userRepository.save(User.createSeed(
                    "seed+" + i + "@seed.baedang.local", SEED_PASSWORD_HASH, "시드투자자" + i));
            // 4~8주 전 개설 → 리포트/리더보드 4주 게이트를 이미 통과(후속 자격 판정용).
            OffsetDateTime openedAt = now.minusDays(28 + random.nextInt(29));
            Account account = accountRepository.save(
                    Account.open(user.getUserId(), 1, initialCash, openedAt));

            holdings += buildPortfolio(account, universe, random, openedAt, now, quotedStocks);
            accounts++;
        }

        log.info("시드 적재 완료 — 계좌 {}, 보유 {}, 시세 {}", accounts, holdings, quotedStocks.size());
        return new SeedResult(accounts, holdings, quotedStocks.size(), 0);
    }

    /** 한 계좌의 보유를 현실 샘플러로 구성하고, 총원가만큼 예수금을 차감한다. 생성한 보유 수 반환. */
    private int buildPortfolio(Account account, List<Stock> universe, Random random,
                               OffsetDateTime at, OffsetDateTime now, Set<Long> quotedStocks) {
        int k = 1 + random.nextInt(maxHoldings);
        List<Stock> picked = weightedSampleWithoutReplacement(universe, k, random);
        double[] weights = randomWeights(picked.size(), random);
        // 초기자본의 30~95% 를 투자(나머지는 현금).
        BigDecimal totalInvest = initialCash
                .multiply(BigDecimal.valueOf(0.30 + random.nextDouble() * 0.65))
                .setScale(0, RoundingMode.DOWN);

        BigDecimal spent = BigDecimal.ZERO;
        int created = 0;
        for (int j = 0; j < picked.size(); j++) {
            Stock stock = picked.get(j);
            boolean us = stock.getMarketCountry() == MarketCountry.US;
            BigDecimal nativePrice = syntheticPrice(stock, us);
            BigDecimal priceKrw = us ? nativePrice.multiply(SEED_USD_KRW) : nativePrice;

            BigDecimal targetCost = totalInvest.multiply(BigDecimal.valueOf(weights[j]));
            long qty = targetCost.divide(priceKrw, 0, RoundingMode.DOWN).longValueExact();
            if (qty <= 0) {
                continue; // 이 비중으로는 1주도 못 사면 건너뛴다(원가 ≤ 목표 유지).
            }
            BigDecimal quantity = BigDecimal.valueOf(qty);
            BigDecimal krwCost = priceKrw.multiply(quantity);
            BigDecimal usdCost = us ? nativePrice.multiply(quantity) : BigDecimal.ZERO;

            holdingRepository.save(Holding.firstBuy(
                    account.getAccountId(), stock.getStockId(), quantity, usdCost, krwCost, at));
            spent = spent.add(krwCost);
            created++;

            // return% 배관용 현재가(원가 × 드리프트 ±30%). 종목당 한 번만.
            if (quotedStocks.add(stock.getStockId())) {
                BigDecimal lastPrice = nativePrice
                        .multiply(BigDecimal.valueOf(0.70 + random.nextDouble() * 0.60))
                        .setScale(4, RoundingMode.HALF_UP);
                quoteSnapshotRepository.save(new QuoteSnapshot(
                        stock.getStockId(), lastPrice, us ? "USD" : "KRW", now, now));
            }
        }

        if (spent.signum() > 0) {
            account.debitMarketBuy(spent); // cash_balance = initial − Σ원가 (managed 엔티티, 트랜잭션 커밋 시 flush)
        }
        return created;
    }

    /** {@code trading_amount} 가중 비복원 추출. 인기 종목일수록 자주 뽑힌다. */
    private List<Stock> weightedSampleWithoutReplacement(List<Stock> universe, int k, Random random) {
        List<Stock> pool = new ArrayList<>(universe);
        List<Stock> chosen = new ArrayList<>();
        int limit = Math.min(k, pool.size());
        for (int n = 0; n < limit; n++) {
            double total = 0;
            for (Stock s : pool) {
                total += weight(s);
            }
            double dart = random.nextDouble() * total;
            double acc = 0;
            int idx = pool.size() - 1;
            for (int p = 0; p < pool.size(); p++) {
                acc += weight(pool.get(p));
                if (dart <= acc) {
                    idx = p;
                    break;
                }
            }
            chosen.add(pool.remove(idx));
        }
        return chosen;
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

    /** 종목별 결정적 합성 단가(종목 통화 기준). 분포 검증엔 상대 크기만 의미 있다. */
    private static BigDecimal syntheticPrice(Stock stock, boolean us) {
        long h = Math.abs(stock.getStockId() * 2654435761L);
        if (us) {
            return BigDecimal.valueOf(10 + (h % 890)).setScale(4, RoundingMode.HALF_UP); // $10~$900
        }
        return BigDecimal.valueOf(1000 + (h % 299000)).setScale(4, RoundingMode.HALF_UP); // 1,000~300,000원
    }
}
