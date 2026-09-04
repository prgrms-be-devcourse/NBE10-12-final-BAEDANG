package com.baedang.account.service;

import com.baedang.account.dto.AccountResetResponse;
import com.baedang.global.error.BusinessException;
import com.baedang.global.error.ErrorCode;
import com.baedang.trading.entity.LedgerEntry;
import com.baedang.trading.repository.HoldingRepository;
import com.baedang.trading.repository.LedgerEntryRepository;
import com.baedang.trading.service.LedgerService;
import com.baedang.user.entity.Account;
import com.baedang.user.entity.AccountStatus;
import com.baedang.user.repository.AccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccountResetServiceTest {

    private static final Instant RESET_INSTANT = Instant.parse("2026-08-27T04:30:00Z");
    private static final OffsetDateTime OPENED_AT = OffsetDateTime.parse("2026-08-20T00:00:00Z");
    private static final BigDecimal INITIAL_CASH = new BigDecimal("50000000");

    @Mock AccountRepository accountRepository;
    @Mock HoldingRepository holdingRepository;
    @Mock LedgerEntryRepository ledgerEntryRepository;

    private AccountResetService service;

    @BeforeEach
    void setUp() {
        service = new AccountResetService(
                accountRepository,
                holdingRepository,
                new LedgerService(ledgerEntryRepository),
                INITIAL_CASH,
                Clock.fixed(RESET_INSTANT, ZoneOffset.UTC));
    }

    @Test
    void 활성_계좌를_종료하고_다음_회차와_초기지급_원장을_생성한다() {
        Account current = Account.open(7L, 1, INITIAL_CASH, OPENED_AT);
        ReflectionTestUtils.setField(current, "accountId", 1L);
        Account saved = org.mockito.Mockito.mock(Account.class);
        when(saved.getAccountId()).thenReturn(2L);
        when(saved.getRoundNo()).thenReturn(2);
        when(saved.getInitialCash()).thenReturn(INITIAL_CASH);
        when(saved.getOpenedAt()).thenReturn(RESET_INSTANT.atOffset(ZoneOffset.UTC));
        when(accountRepository.findByAccountIdAndUserIdForUpdate(1L, 7L))
                .thenReturn(Optional.of(current));
        when(accountRepository.saveAndFlush(any(Account.class))).thenReturn(saved);

        AccountResetResponse response = service.reset(7L, 1L);

        assertThat(current.getStatus()).isEqualTo(AccountStatus.CLOSED);
        assertThat(current.getClosedAt()).isEqualTo(RESET_INSTANT.atOffset(ZoneOffset.UTC));
        assertThat(response.accountId()).isEqualTo(2L);
        assertThat(response.roundNo()).isEqualTo(2);

        ArgumentCaptor<Account> accountCaptor = ArgumentCaptor.forClass(Account.class);
        verify(accountRepository).saveAndFlush(accountCaptor.capture());
        Account opened = accountCaptor.getValue();
        assertThat(opened.getRoundNo()).isEqualTo(2);
        assertThat(opened.getOpenedAt()).isEqualTo(current.getClosedAt());
        assertThat(opened.getLockedCash()).isEqualByComparingTo(BigDecimal.ZERO);

        ArgumentCaptor<LedgerEntry> ledgerCaptor = ArgumentCaptor.forClass(LedgerEntry.class);
        verify(ledgerEntryRepository).save(ledgerCaptor.capture());
        LedgerEntry ledger = ledgerCaptor.getValue();
        assertThat(ledger.getAccountId()).isEqualTo(2L);
        assertThat(ledger.getAmount()).isEqualByComparingTo(INITIAL_CASH);
        assertThat(ledger.getBalanceAfter()).isEqualByComparingTo(INITIAL_CASH);
        assertThat(ledger.getOccurredAt()).isEqualTo(current.getClosedAt());
    }

    @Test
    void 바로_이전_종료계좌로_재시도하면_현재_회차를_그대로_반환한다() {
        Account closed = Account.open(7L, 1, INITIAL_CASH, OPENED_AT);
        closed.close(RESET_INSTANT.atOffset(ZoneOffset.UTC));
        Account active = org.mockito.Mockito.mock(Account.class);
        when(active.getAccountId()).thenReturn(2L);
        when(active.getRoundNo()).thenReturn(2);
        when(active.getInitialCash()).thenReturn(INITIAL_CASH);
        when(accountRepository.findByAccountIdAndUserIdForUpdate(1L, 7L))
                .thenReturn(Optional.of(closed));
        when(accountRepository.findByUserIdAndStatusForUpdate(7L, AccountStatus.ACTIVE))
                .thenReturn(Optional.of(active));

        AccountResetResponse response = service.reset(7L, 1L);

        assertThat(response.accountId()).isEqualTo(2L);
        assertThat(response.cashBalance()).isEqualTo("50000000");
        verify(accountRepository, never()).saveAndFlush(any());
        verifyNoInteractions(holdingRepository, ledgerEntryRepository);
    }

    @Test
    void 두_회차_이상_지난_계좌_ID는_충돌로_거절한다() {
        Account closed = Account.open(7L, 1, INITIAL_CASH, OPENED_AT);
        closed.close(RESET_INSTANT.atOffset(ZoneOffset.UTC));
        Account active = org.mockito.Mockito.mock(Account.class);
        when(active.getRoundNo()).thenReturn(3);
        when(accountRepository.findByAccountIdAndUserIdForUpdate(1L, 7L))
                .thenReturn(Optional.of(closed));
        when(accountRepository.findByUserIdAndStatusForUpdate(7L, AccountStatus.ACTIVE))
                .thenReturn(Optional.of(active));

        assertThatThrownBy(() -> service.reset(7L, 1L))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.ACCOUNT_RESET_CONFLICT));
    }

    @Test
    void 동결_수량이_있으면_초기화를_거절한다() {
        Account current = Account.open(7L, 1, INITIAL_CASH, OPENED_AT);
        ReflectionTestUtils.setField(current, "accountId", 1L);
        when(accountRepository.findByAccountIdAndUserIdForUpdate(1L, 7L))
                .thenReturn(Optional.of(current));
        when(holdingRepository.existsByAccountIdAndLockedQuantityGreaterThan(1L, BigDecimal.ZERO))
                .thenReturn(true);

        assertThatThrownBy(() -> service.reset(7L, 1L))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.ACCOUNT_HAS_PENDING_ORDERS));

        assertThat(current.getStatus()).isEqualTo(AccountStatus.ACTIVE);
        verify(accountRepository, never()).saveAndFlush(any());
        verifyNoInteractions(ledgerEntryRepository);
    }

    @Test
    void 다른_사용자의_계좌는_없는_계좌처럼_처리한다() {
        when(accountRepository.findByAccountIdAndUserIdForUpdate(1L, 7L))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.reset(7L, 1L))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.ACCOUNT_NOT_FOUND));
    }
}
