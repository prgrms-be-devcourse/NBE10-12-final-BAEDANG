package com.baedang.market.repository;

import com.baedang.market.entity.QuoteSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface QuoteSnapshotRepository extends JpaRepository<QuoteSnapshot, Long> {

    /** 보유 종목들의 현재가를 한 번에 조회합니다. */
    List<QuoteSnapshot> findByStockIdIn(Collection<Long> stockIds);
}
