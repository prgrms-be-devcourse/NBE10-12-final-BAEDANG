package com.baedang.market.repository;

import com.baedang.market.entity.ExchangeRate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ExchangeRateRepository extends JpaRepository<ExchangeRate, Long> {

    /** 통화쌍의 최신 환율 한 건. 매시 정각 적재되므로 rateAt 내림차순 첫 행이 최신입니다. */
    Optional<ExchangeRate> findTopByBaseCurrencyAndQuoteCurrencyOrderByRateAtDesc(
            String baseCurrency, String quoteCurrency);
}
