package com.baedang.market.repository;

import com.baedang.market.entity.ExchangeRate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
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

    /**
     * 지정 시점 이후 환율 이력을 오래된 순서로 조회합니다.
     */
    List<ExchangeRate> findByBaseCurrencyAndQuoteCurrencyAndRateAtGreaterThanEqualOrderByRateAtAsc(
            String baseCurrency, String quoteCurrency, OffsetDateTime from);

    /**
     * 동일한 통화쌍과 rateAt이 이미 존재하면 INSERT하지 않습니다.
     * @return 1 이면 INSERT, 0 이면 중복으로 무시
     */
    @Modifying
    @Query(value = """
            INSERT INTO exchange_rate (
                base_currency,
                quote_currency,
                rate,
                mid_rate,
                rate_at,
                collected_at
            )
            VALUES (
                :baseCurrency,
                :quoteCurrency,
                :rate,
                :midRate,
                :rateAt,
                :collectedAt
            )
            ON CONFLICT (base_currency, quote_currency, rate_at)
            DO NOTHING
            """, nativeQuery = true)
    int insertIgnoreDuplicate(
            @Param("baseCurrency") String baseCurrency,
            @Param("quoteCurrency") String quoteCurrency,
            @Param("rate") BigDecimal rate,
            @Param("midRate") BigDecimal midRate,
            @Param("rateAt") OffsetDateTime rateAt,
            @Param("collectedAt") OffsetDateTime collectedAt
    );
}
