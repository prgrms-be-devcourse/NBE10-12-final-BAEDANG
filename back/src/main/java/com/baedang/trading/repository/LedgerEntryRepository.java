package com.baedang.trading.repository;

import com.baedang.trading.entity.EntryType;
import com.baedang.trading.entity.LedgerEntry;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/** append-only 원장이므로 저장과 조회만 노출합니다. */
public interface LedgerEntryRepository extends Repository<LedgerEntry, Long> {

    LedgerEntry save(LedgerEntry ledgerEntry);

    Optional<LedgerEntry> findFirstByOrderIdOrderByEntryIdAsc(Long orderId);

    long countByAccountId(Long accountId);

    /**
     * 체결 내역 커서 페이지. 최신({@code entry_id} 내림차순)부터,
     * {@code entryType} 이 {@code null} 이면 전체, {@code cursorId} 가 {@code null} 이면 처음부터.
     *
     * <p>{@code hasNext} 판정을 위해 호출부는 {@code size + 1} 개를 요청하고,
     * {@code size + 1} 번째 행의 존재로 다음 페이지 유무를 판단합니다.
     */
    @Query("""
            SELECT l FROM LedgerEntry l
            WHERE l.accountId = :accountId
              AND (:entryType IS NULL OR l.entryType = :entryType)
              AND (:cursorId IS NULL OR l.entryId < :cursorId)
            ORDER BY l.entryId DESC
            """)
    List<LedgerEntry> findPage(@Param("accountId") Long accountId,
                               @Param("entryType") EntryType entryType,
                               @Param("cursorId") Long cursorId,
                               Pageable pageable);
}
