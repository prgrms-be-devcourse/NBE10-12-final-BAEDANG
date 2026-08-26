package com.baedang.account.service;

import com.baedang.account.dto.AccountSummaryResponse;
import com.baedang.account.support.HoldingValuation;
import com.baedang.global.error.BusinessException;
import com.baedang.global.error.ErrorCode;
import com.baedang.market.entity.ExchangeRate;
import com.baedang.market.entity.QuoteSnapshot;
import com.baedang.market.repository.ExchangeRateRepository;
import com.baedang.market.repository.QuoteSnapshotRepository;
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
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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

    private final AccountRepository accountRepository;
    private final HoldingRepository holdingRepository;
    private final QuoteSnapshotRepository quoteSnapshotRepository;
    private final ExchangeRateRepository exchangeRateRepository;
    private final HoldingValuator holdingValuator;
    private final Clock clock;

    public AccountService(AccountRepository accountRepository,
                          HoldingRepository holdingRepository,
                          QuoteSnapshotRepository quoteSnapshotRepository,
                          ExchangeRateRepository exchangeRateRepository,
                          HoldingValuator holdingValuator,
                          Clock clock) {
        this.accountRepository = accountRepository;
        this.holdingRepository = holdingRepository;
        this.quoteSnapshotRepository = quoteSnapshotRepository;
        this.exchangeRateRepository = exchangeRateRepository;
        this.holdingValuator = holdingValuator;
        this.clock = clock;
    }

    public AccountSummaryResponse getSummary(Long userId) {
        Account account = accountRepository.findByUserIdAndStatus(userId, AccountStatus.ACTIVE)
                .orElseThrow(() -> new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND));

        List<Holding> holdings =
                holdingRepository.findByAccountIdAndQuantityGreaterThan(account.getAccountId(), BigDecimal.ZERO);

        BigDecimal usdKrwRate = latestUsdKrwRate();
        List<HoldingValuation> valuations =
                holdingValuator.valuate(holdings, quotesByStockId(holdings), usdKrwRate);

        BigDecimal stockValue = sum(valuations, HoldingValuation::evalWon);
        BigDecimal costBasis = sum(valuations, HoldingValuation::costWon);
        BigDecimal unrealizedPnl = stockValue.subtract(costBasis);
        BigDecimal totalAsset = account.getCashBalance().add(stockValue);
        BigDecimal pnlRate = costBasis.signum() > 0
                ? unrealizedPnl.divide(costBasis, 4, RoundingMode.HALF_UP)
                : null;

        OffsetDateTime asOf = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        return AccountSummaryResponse.of(
                account, stockValue, totalAsset, unrealizedPnl, pnlRate, usdKrwRate, asOf);
    }

    private Map<Long, QuoteSnapshot> quotesByStockId(List<Holding> holdings) {
        if (holdings.isEmpty()) {
            return Map.of();
        }
        List<Long> stockIds = holdings.stream().map(Holding::getStockId).toList();
        return quoteSnapshotRepository.findByStockIdIn(stockIds).stream()
                .collect(Collectors.toMap(QuoteSnapshot::getStockId, Function.identity()));
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
