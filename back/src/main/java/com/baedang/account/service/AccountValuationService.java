package com.baedang.account.service;

import com.baedang.account.support.AccountValuation;
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
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 활성 계좌의 조회·평가 패스: 계좌 → 보유(수량&gt;0) → 시세 → 환율 → 원화 평가.
 *
 * <p>계좌 요약(#1)·보유 목록(#2)·투자 성향 리포트가 모두 이 하나를 거친다. 평가 금액은
 * 이 서비스에서만 계산해 {@link HoldingValuator} 규약(외화는 센트→환율→1원 반올림)을
 * 단일 지점으로 강제한다. 화면마다 다시 계산하면 반드시 값이 어긋난다(AGENTS.md 공용 규칙).
 */
@Service
@Transactional(readOnly = true)
public class AccountValuationService {

    private static final String USD = "USD";
    private static final String KRW = "KRW";

    private final AccountRepository accountRepository;
    private final HoldingRepository holdingRepository;
    private final QuoteSnapshotRepository quoteSnapshotRepository;
    private final ExchangeRateRepository exchangeRateRepository;
    private final HoldingValuator holdingValuator;

    public AccountValuationService(AccountRepository accountRepository,
                                   HoldingRepository holdingRepository,
                                   QuoteSnapshotRepository quoteSnapshotRepository,
                                   ExchangeRateRepository exchangeRateRepository,
                                   HoldingValuator holdingValuator) {
        this.accountRepository = accountRepository;
        this.holdingRepository = holdingRepository;
        this.quoteSnapshotRepository = quoteSnapshotRepository;
        this.exchangeRateRepository = exchangeRateRepository;
        this.holdingValuator = holdingValuator;
    }

    /** 유저의 ACTIVE 계좌를 조회해 보유 종목까지 원화로 평가한다. 계좌가 없으면 ACCOUNT_NOT_FOUND. */
    public AccountValuation valuateActiveAccount(Long userId) {
        Account account = accountRepository.findByUserIdAndStatus(userId, AccountStatus.ACTIVE)
                .orElseThrow(() -> new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND));

        List<Holding> holdings =
                holdingRepository.findByAccountIdAndQuantityGreaterThan(account.getAccountId(), BigDecimal.ZERO);

        Map<Long, QuoteSnapshot> quotes = quotesByStockId(holdings);
        BigDecimal usdKrwRate = latestUsdKrwRate();
        List<HoldingValuation> valuations = holdingValuator.valuate(holdings, quotes, usdKrwRate);

        return new AccountValuation(account, holdings, quotes, valuations, usdKrwRate);
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
                .findTopByBaseCurrencyAndQuoteCurrencyOrderByValidFromDesc(USD, KRW)
                .map(this::displayRate)
                .orElse(null);
    }

    private BigDecimal displayRate(ExchangeRate exchangeRate) {
        return exchangeRate.getMidRate() != null ? exchangeRate.getMidRate() : exchangeRate.getRate();
    }
}
