package com.baedang.trading.service;

import com.baedang.trading.entity.LedgerEntry;
import com.baedang.trading.repository.LedgerEntryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** 원장 기록 서비스. 현재는 신규 계좌의 초기 지급 기록만 담당합니다. */
@Service
public class InitialDepositLedgerService {

    private final LedgerEntryRepository ledgerEntryRepository;

    public InitialDepositLedgerService(LedgerEntryRepository ledgerEntryRepository) {
        this.ledgerEntryRepository = ledgerEntryRepository;
    }

    /**
     * 가입·초기화로 방금 개설한 계좌의 초기 지급을 기록합니다. 예수금을 다시 증가시키지 않습니다.
     * 계좌 생성과 같은 트랜잭션에서 한 번만 호출하며, 시각·금액·회차는 생성된 계좌와 일치해야 합니다.
     * 중복 요청 판정은 가입·초기화 유스케이스가 담당하고, 기존 계좌의 누락 원장 보정에는 사용하지 않습니다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordInitialDeposit(
            Long accountId,
            BigDecimal initialCash,
            int roundNo,
            OffsetDateTime occurredAt
    ) {
        if (accountId == null || accountId <= 0) {
            throw new IllegalArgumentException("계좌 ID는 양수여야 합니다");
        }
        if (initialCash == null || initialCash.signum() <= 0) {
            throw new IllegalArgumentException("초기 지급액은 0보다 커야 합니다");
        }
        if (roundNo < 1) throw new IllegalArgumentException("계좌 회차는 1 이상이어야 합니다");
        if (occurredAt == null) throw new IllegalArgumentException("초기 지급 시각은 필수입니다");

        String memo = roundNo == 1 ? "모의투자금 지급" : "모의투자금 지급 · " + roundNo + "회차";
        ledgerEntryRepository.save(LedgerEntry.initialDeposit(accountId, initialCash, memo, occurredAt));
    }
}
