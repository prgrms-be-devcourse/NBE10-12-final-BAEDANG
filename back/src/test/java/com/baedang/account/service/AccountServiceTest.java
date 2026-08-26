package com.baedang.account.service;

import com.baedang.account.dto.AccountSummaryResponse;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccountServiceTest {

    @Mock AccountRepository accountRepository;
    @Mock HoldingRepository holdingRepository;
    @Mock QuoteSnapshotRepository quoteSnapshotRepository;
    @Mock ExchangeRateRepository exchangeRateRepository;
    @Mock Account account;

    private AccountService service;

    @BeforeEach
    void setUp() {
        service = new AccountService(
                accountRepository,
                holdingRepository,
                quoteSnapshotRepository,
                exchangeRateRepository,
                new HoldingValuator()
        );
    }

    @Test
    void ACTIVE_계좌가_없으면_ACCOUNT_NOT_FOUND_를_던진다() {
        when(accountRepository.findByUserIdAndStatus(1L, AccountStatus.ACTIVE))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getSummary(1L))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.ACCOUNT_NOT_FOUND));

        verifyNoInteractions(holdingRepository, quoteSnapshotRepository, exchangeRateRepository);
    }

    @Test
    void 보유종목이_없으면_평가액과_손익은_0_이고_총자산은_예수금과_같다() {
        givenAccount(1L, "50000000", "50000000", 1);
        when(holdingRepository.findByAccountIdAndQuantityGreaterThan(1L, BigDecimal.ZERO))
                .thenReturn(List.of());
        when(exchangeRateRepository.findTopByBaseCurrencyAndQuoteCurrencyOrderByRateAtDesc("USD", "KRW"))
                .thenReturn(Optional.empty());

        AccountSummaryResponse response = service.getSummary(1L);

        assertThat(response.accountId()).isEqualTo(1L);
        assertThat(response.roundNo()).isEqualTo(1);
        assertThat(response.stockValue()).isEqualTo("0");
        assertThat(response.totalAsset()).isEqualTo("50000000");
        assertThat(response.unrealizedPnl()).isEqualTo("0");
        assertThat(response.unrealizedPnlRate()).isNull();
        assertThat(response.exchangeRate()).isNull();
    }

    @Test
    void 국내와_미국_보유를_각각_원화로_평가해_합산한다() {
        givenAccount(1L, "50000000", "48240000", 1);
        Holding krHolding = Holding.firstBuy(1L, 101L,
                new BigDecimal("6"), new BigDecimal("228000"), BigDecimal.ONE);
        Holding usHolding = Holding.firstBuy(1L, 202L,
                new BigDecimal("10"), new BigDecimal("88.34"), new BigDecimal("1383.60"));
        when(holdingRepository.findByAccountIdAndQuantityGreaterThan(1L, BigDecimal.ZERO))
                .thenReturn(List.of(krHolding, usHolding));
        when(quoteSnapshotRepository.findByStockIdIn(any()))
                .thenReturn(List.of(quote(101L, "241500", "KRW"), quote(202L, "90.00", "USD")));
        when(exchangeRateRepository.findTopByBaseCurrencyAndQuoteCurrencyOrderByRateAtDesc("USD", "KRW"))
                .thenReturn(Optional.of(rate("1401", "1400")));

        AccountSummaryResponse response = service.getSummary(1L);

        // stockValue = 1,449,000(KR) + 1,260,000(US) = 2,709,000
        assertThat(response.stockValue()).isEqualTo("2709000");
        // totalAsset = 48,240,000 + 2,709,000 = 50,949,000
        assertThat(response.totalAsset()).isEqualTo("50949000");
        // pnl = 2,709,000 - (1,368,000 + 1,222,272) = 118,728
        assertThat(response.unrealizedPnl()).isEqualTo("118728");
        // pnlRate = 118,728 / 2,590,272 = 0.0458
        assertThat(response.unrealizedPnlRate()).isEqualTo("0.0458");
        assertThat(response.exchangeRate()).isEqualTo("1400");
    }

    @Test
    void 미국_보유가_있는데_환율이_없으면_매입환율로_환산하고_엔드포인트를_유지한다() {
        givenAccount(1L, "50000000", "10000000", 1);
        Holding usHolding = Holding.firstBuy(1L, 202L,
                new BigDecimal("10"), new BigDecimal("88.34"), new BigDecimal("1383.60"));
        when(holdingRepository.findByAccountIdAndQuantityGreaterThan(1L, BigDecimal.ZERO))
                .thenReturn(List.of(usHolding));
        when(quoteSnapshotRepository.findByStockIdIn(any()))
                .thenReturn(List.of(quote(202L, "90.00", "USD")));
        when(exchangeRateRepository.findTopByBaseCurrencyAndQuoteCurrencyOrderByRateAtDesc("USD", "KRW"))
                .thenReturn(Optional.empty());

        AccountSummaryResponse response = service.getSummary(1L);

        // 매입환율 폴백: 900.00 x 1383.60 = 1,245,240
        assertThat(response.stockValue()).isEqualTo("1245240");
        assertThat(response.totalAsset()).isEqualTo("11245240");
        assertThat(response.exchangeRate()).isNull();
    }

    private void givenAccount(Long accountId, String initialCash, String cashBalance, int roundNo) {
        when(accountRepository.findByUserIdAndStatus(1L, AccountStatus.ACTIVE))
                .thenReturn(Optional.of(account));
        when(account.getAccountId()).thenReturn(accountId);
        when(account.getInitialCash()).thenReturn(new BigDecimal(initialCash));
        when(account.getCashBalance()).thenReturn(new BigDecimal(cashBalance));
        when(account.getRoundNo()).thenReturn(roundNo);
    }

    private QuoteSnapshot quote(Long stockId, String lastPrice, String currency) {
        return new QuoteSnapshot(stockId, new BigDecimal(lastPrice), currency, OffsetDateTime.now());
    }

    private ExchangeRate rate(String rate, String midRate) {
        return new ExchangeRate("USD", "KRW", new BigDecimal(rate), new BigDecimal(midRate), OffsetDateTime.now());
    }
}
