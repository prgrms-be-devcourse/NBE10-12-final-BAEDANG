package com.baedang.market.repository;

import com.baedang.market.entity.QuoteSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;

public interface QuoteSnapshotRepository extends JpaRepository<QuoteSnapshot, Long> {
}
