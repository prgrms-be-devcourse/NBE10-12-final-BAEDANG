package com.baedang.stock.repository;

import com.baedang.stock.entity.MarketCountry;
import com.baedang.stock.entity.Stock;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface StockRepository extends JpaRepository<Stock, Long> {
    @Query("""
            select count(s) > 0 from Stock s
            where s.stockId = :stockId
              and s.listingStatus = com.baedang.stock.entity.ListingStatus.ACTIVE
              and (s.isRanked = true or exists (
                select o.orderId from TradeOrder o where o.stockId = s.stockId
                  and o.orderType = com.baedang.trading.entity.OrderType.LIMIT
                  and o.status in (com.baedang.trading.entity.OrderStatus.PENDING,
                                   com.baedang.trading.entity.OrderStatus.PARTIALLY_FILLED)
                  and o.expiresAt > :now and o.quantity > o.filledQuantity))
            """)
    boolean isQuoteTarget(@Param("stockId") Long stockId, @Param("now") OffsetDateTime now);

    /** 랭킹 또는 모든 사용자 중 활성 지정가 주문이 있는 종목만 매 페이지 재확인합니다. */
    @Query("""
            select s from Stock s
            where s.marketCountry = :country and s.stockId > :after
              and s.listingStatus = com.baedang.stock.entity.ListingStatus.ACTIVE
              and (s.isRanked = true or exists (
                select o.orderId from TradeOrder o
                where o.stockId = s.stockId
                  and o.orderType = com.baedang.trading.entity.OrderType.LIMIT
                  and o.status in (com.baedang.trading.entity.OrderStatus.PENDING,
                                   com.baedang.trading.entity.OrderStatus.PARTIALLY_FILLED)
                  and o.expiresAt > :now and o.quantity > o.filledQuantity))
            order by s.stockId
            """)
    List<Stock> findQuoteTargets(@Param("country") MarketCountry country,
            @Param("after") Long after,
            @Param("now") OffsetDateTime now, Pageable page);

    Optional<Stock> findBySymbolIgnoreCaseAndMarketCountry(String symbol, MarketCountry marketCountry);

    /**
     * 가상 호가 publisher가 종목별로 버전 교체를 직렬화할 때 씁니다 (설계서 §5.2 1단계).
     * 조회 전용이 아니라 {@code FOR UPDATE}이므로 반드시 트랜잭션 안에서 호출하세요.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Stock s where s.stockId = :stockId")
    Optional<Stock> findByIdForUpdate(@Param("stockId") Long stockId);

    /**
     * 보유 종목들의 심볼·이름·통화를 한 번에 조회합니다 (마이페이지 보유 목록).
     */
    List<Stock> findByStockIdIn(Collection<Long> stockIds);


    /**
     * 종목명·영문명·심볼 부분일치 검색 (#148).
     *
     * <p>검색어는 {@code DomainNormalizer.searchKey()} 를 거친 값이어야 합니다 —
     * {@code stock.name_jamo} 생성 컬럼도 같은 전처리(공백 제거 + 소문자) 후 분해되므로
     * 전처리가 어긋나면 매칭이 조용히 실패합니다. 자모 분해는 {@code hangul_jamo} 가
     * 유일한 구현이고, 검색어 쪽도 SQL 에서 같은 함수를 호출합니다 (Java에 중복 구현 금지).
     */
    @Query(value = """
            select s.*
            from stock s
            where s.name_jamo like '%' || hangul_jamo(:keyword, true) || '%'
               or lower(regexp_replace(coalesce(s.english_name, ''), '\\s+', '', 'g')) like '%' || :keyword || '%'
               or lower(s.symbol) like '%' || :keyword || '%'
            """, nativeQuery = true)
    List<Stock> searchByJamo(@Param("keyword") String keyword);

    /**
     * 검색어를 자모로 분해합니다. 결과 정렬용 순위 판정이 검색과 <b>같은 자모 공간</b>에서
     * 이뤄져야 하는데, {@code hangul_jamo} 는 SQL 에만 존재하기 때문입니다 (Java 중복 구현 금지).
     *
     * <p>{@code partialTail} 이 규칙을 가릅니다 — 컬럼과 같은 3칸 고정폭(종성 없으면 {@code ^}
     * 패딩)이 필요한 완전일치 판정은 {@code false}, 미완성 입력('삼ㅅ')을 살려야 하는
     * 접두 판정은 {@code true} 입니다.
     */
    @Query(value = "select hangul_jamo(:keyword, :partialTail)", nativeQuery = true)
    String hangulJamo(@Param("keyword") String keyword, @Param("partialTail") boolean partialTail);

    /**
     * 독립 초성 검색 (예: {@code ㅅㅅㅈㅈ} → 삼성전자).
     *
     * <p>순수 초성 검색어는 영문명·심볼에 걸릴 일이 없으므로 종목명 초성만 봅니다.
     */
    @Query(value = """
            select s.*
            from stock s
            where s.name_chosung like '%' || :keyword || '%'
            """, nativeQuery = true)
    List<Stock> searchByChosung(@Param("keyword") String keyword);

    // 첫 페이지
    @Query("""
            select s
            from Stock s
            where s.marketCountry = :marketCountry
              and s.isRanked = true
              and s.tradingAmount is not null
            order by s.tradingAmount desc, s.stockId desc
            """)
    List<Stock> findRankedByMarketCountry(
            @Param("marketCountry") MarketCountry marketCountry,
            Pageable pageable
    );

    // 다음 페이지
    @Query("""
            select s
            from Stock s
            where s.marketCountry = :marketCountry
              and s.isRanked = true
              and s.tradingAmount is not null
              and (
                    s.tradingAmount < :tradingAmount
                    or (
                        s.tradingAmount = :tradingAmount
                        and s.stockId < :stockId
                    )
              )
            order by s.tradingAmount desc, s.stockId desc
            """)
    List<Stock> findRankedAfterCursor(
            @Param("marketCountry") MarketCountry marketCountry,
            @Param("tradingAmount") BigDecimal tradingAmount,
            @Param("stockId") Long stockId,
            Pageable pageable
    );

    List<Stock> findByMarketCountryAndIsRankedTrue(MarketCountry marketCountry);

    List<Stock> findByMarketCountryAndSymbolIn(MarketCountry marketCountry, Collection<String> symbols);

    Page<Stock> findAllByOrderByStockIdAsc(Pageable pageable);
}
