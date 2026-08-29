package com.baedang.market.repository;

import com.baedang.market.entity.ExchangeRate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.Optional;

public interface ExchangeRateRepository extends JpaRepository<ExchangeRate, Long> {

    /** 통화쌍의 최신 환율 한 건. 매시 정각 적재되므로 rateAt 내림차순 첫 행이 최신입니다. */
    Optional<ExchangeRate> findTopByBaseCurrencyAndQuoteCurrencyOrderByRateAtDesc(
            String baseCurrency, String quoteCurrency);

    /**
     * 주어진 시점 이전(포함) 최신 환율 한 건. 등락률 계산 기준점(예: 전일 자정)을
     * 구할 때 쓴다 — rateAt이 그 시점보다 크지 않은 행 중 가장 최신 행.
     */
    Optional<ExchangeRate> findTopByBaseCurrencyAndQuoteCurrencyAndRateAtLessThanEqualOrderByRateAtDesc(
            String baseCurrency, String quoteCurrency, OffsetDateTime rateAt);
}
