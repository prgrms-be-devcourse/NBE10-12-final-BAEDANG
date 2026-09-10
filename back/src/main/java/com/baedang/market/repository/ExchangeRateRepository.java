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

    /** 원본 validFrom 기준 최신 환율. 체결에서는 별도로 원본 유효기간을 검증합니다. */
    Optional<ExchangeRate> findTopByBaseCurrencyAndQuoteCurrencyOrderByValidFromDesc(
            String baseCurrency, String quoteCurrency);

    /**
     * 주어진 시점 이전(포함) 최신 환율 한 건. 등락률 계산 기준점(예: 전일 자정)을
     * 구할 때 쓴다 — validFrom이 그 시점보다 크지 않은 행 중 가장 최신 행.
     */
    Optional<ExchangeRate> findTopByBaseCurrencyAndQuoteCurrencyAndValidFromLessThanEqualOrderByValidFromDesc(
            String baseCurrency, String quoteCurrency, OffsetDateTime validFrom);

    /**
     * 지정 시점 이후 환율 이력을 오래된 순서로 조회합니다.
     */
    List<ExchangeRate> findByBaseCurrencyAndQuoteCurrencyAndValidFromGreaterThanEqualOrderByValidFromAsc(
            String baseCurrency, String quoteCurrency, OffsetDateTime from);

    /**
     * 같은 원본 시각은 최신 수신 응답으로 갱신합니다. 늦게 저장된 과거 응답은 덮어쓰지 않습니다.
     * @return 1 이면 저장/갱신, 0 이면 더 오래되거나 동일한 수신 응답으로 무시
     */
    @Modifying
    @Query(value = """
            INSERT INTO exchange_rate (
                base_currency,
                quote_currency,
                rate,
                mid_rate,
                valid_from,
                valid_until,
                collected_at
            )
            VALUES (
                :baseCurrency,
                :quoteCurrency,
                :rate,
                :midRate,
                :validFrom,
                :validUntil,
                :collectedAt
            )
            ON CONFLICT (base_currency, quote_currency, valid_from)
            DO UPDATE SET rate = EXCLUDED.rate,
                          mid_rate = EXCLUDED.mid_rate,
                          valid_until = EXCLUDED.valid_until,
                          collected_at = EXCLUDED.collected_at
            WHERE exchange_rate.collected_at < EXCLUDED.collected_at
            """, nativeQuery = true)
    int upsertLatestObservation(
            @Param("baseCurrency") String baseCurrency,
            @Param("quoteCurrency") String quoteCurrency,
            @Param("rate") BigDecimal rate,
            @Param("midRate") BigDecimal midRate,
            @Param("validFrom") OffsetDateTime validFrom,
            @Param("validUntil") OffsetDateTime validUntil,
            @Param("collectedAt") OffsetDateTime collectedAt
    );
}
