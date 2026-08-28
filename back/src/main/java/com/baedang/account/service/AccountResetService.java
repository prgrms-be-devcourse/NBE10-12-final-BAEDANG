package com.baedang.account.service;

import com.baedang.account.dto.AccountResetResponse;
import com.baedang.global.error.BusinessException;
import com.baedang.global.error.ErrorCode;
import com.baedang.trading.entity.LedgerEntry;
import com.baedang.trading.repository.HoldingRepository;
import com.baedang.trading.repository.LedgerEntryRepository;
import com.baedang.user.entity.Account;
import com.baedang.user.entity.AccountStatus;
import com.baedang.user.repository.AccountRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/** 기존 회차를 보존하고 다음 회차 계좌를 개설하는 포트폴리오 초기화 유스케이스입니다. */
@Service
public class AccountResetService {

    private static final BigDecimal NO_LOCKED_QUANTITY = BigDecimal.ZERO;

    private final AccountRepository accountRepository;
    private final HoldingRepository holdingRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final BigDecimal initialCash;
    private final Clock clock;

    public AccountResetService(
            AccountRepository accountRepository,
            HoldingRepository holdingRepository,
            LedgerEntryRepository ledgerEntryRepository,
            @Value("${trading.initial-cash}") BigDecimal initialCash,
            Clock clock
    ) {
        this.accountRepository = accountRepository;
        this.holdingRepository = holdingRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.initialCash = initialCash;
        this.clock = clock;
    }

    /** 계좌 종료·신규 개설·초기 지급 원장을 모두 성공시키거나 모두 롤백합니다. */
    @Transactional
    public AccountResetResponse reset(Long userId, Long requestedAccountId) {
        Account requestedAccount = accountRepository
                .findByAccountIdAndUserIdForUpdate(requestedAccountId, userId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.ACCOUNT_NOT_FOUND,
                        "userId=" + userId + ", accountId=" + requestedAccountId));

        if (requestedAccount.getStatus() == AccountStatus.CLOSED) {
            return replayOrReject(requestedAccount);
        }

        validateNoPendingOrders(requestedAccount);

        OffsetDateTime resetAt = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        requestedAccount.close(resetAt);
        // 부분 유니크 인덱스(uq_account_active)가 있으므로 CLOSED UPDATE를 ACTIVE INSERT보다 먼저 보냅니다.
        accountRepository.flush();

        Account newAccount = accountRepository.saveAndFlush(Account.open(
                userId,
                requestedAccount.getRoundNo() + 1,
                initialCash,
                resetAt));

        ledgerEntryRepository.save(LedgerEntry.initialDeposit(
                newAccount.getAccountId(),
                initialCash,
                "모의투자금 지급 · " + newAccount.getRoundNo() + "회차",
                resetAt));

        return AccountResetResponse.fromReset(newAccount);
    }

    /** 동일한 종료 계좌 ID의 네트워크 재시도라면 다음 회차를 더 만들지 않고 기존 결과를 반환합니다. */
    private AccountResetResponse replayOrReject(Account requestedAccount) {
        Account activeAccount = accountRepository
                .findByUserIdAndStatusForUpdate(requestedAccount.getUserId(), AccountStatus.ACTIVE)
                .orElseThrow(() -> new BusinessException(ErrorCode.ACCOUNT_RESET_CONFLICT));

        if (activeAccount.getRoundNo() != requestedAccount.getRoundNo() + 1) {
            throw new BusinessException(
                    ErrorCode.ACCOUNT_RESET_CONFLICT,
                    "requestedRound=" + requestedAccount.getRoundNo()
                            + ", activeRound=" + activeAccount.getRoundNo());
        }
        return AccountResetResponse.fromReset(activeAccount);
    }

    private void validateNoPendingOrders(Account account) {
        boolean hasLockedCash = account.getLockedCash().signum() > 0;
        boolean hasLockedQuantity = holdingRepository.existsByAccountIdAndLockedQuantityGreaterThan(
                account.getAccountId(), NO_LOCKED_QUANTITY);
        if (hasLockedCash || hasLockedQuantity) {
            throw new BusinessException(ErrorCode.ACCOUNT_HAS_PENDING_ORDERS);
        }
    }
}
