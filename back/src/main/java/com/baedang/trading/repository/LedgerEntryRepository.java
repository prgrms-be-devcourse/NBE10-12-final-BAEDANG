package com.baedang.trading.repository;

import com.baedang.trading.entity.LedgerEntry;
import org.springframework.data.repository.Repository;

import java.util.Optional;

/** append-only 원장이므로 저장과 조회만 노출합니다. */
public interface LedgerEntryRepository extends Repository<LedgerEntry, Long> {

    LedgerEntry save(LedgerEntry ledgerEntry);

    Optional<LedgerEntry> findFirstByOrderIdOrderByEntryIdAsc(Long orderId);

    long countByAccountId(Long accountId);
}
