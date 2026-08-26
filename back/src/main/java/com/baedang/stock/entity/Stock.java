package com.baedang.stock.entity;

import com.baedang.global.entity.BaseEntity;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Locale;

/**
 * 종목 마스터. 매주 월요일 07:00 배치가 전 종목(약 8,500개)을 갱신합니다.
 *
 * <p>검색은 이 테이블 전체가 대상이고, <b>거래는 {@code isRanked} 인 상위 100종목만</b>
 * 가능합니다. 나머지는 조회만 됩니다.
 */
@Entity
@Table(name = "stock")
public class Stock extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "stock_id")
    private Long stockId;

    /** 국내는 6자리 숫자(005930), 미국은 티커(NVDA). */
    @Column(name = "symbol", nullable = false, length = 20)
    private String symbol;

    @Enumerated(EnumType.STRING)
    @Column(name = "market_country", nullable = false, length = 2)
    private MarketCountry marketCountry;

    /** KOSPI · KOSDAQ · NYSE · NASDAQ 등. */
    @Column(name = "market", nullable = false, length = 20)
    private String market;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    /** 토스는 미국 종목에도 한글명을 줍니다. 영문명은 표기가 일정하지 않습니다. */
    @Column(name = "english_name", length = 200)
    private String englishName;

    @Column(name = "isin_code", length = 12)
    private String isinCode;

    /** KRW / USD. 체결 단가가 이 통화 기준입니다. */
    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "security_type", nullable = false, length = 20)
    private String securityType;

    @Enumerated(EnumType.STRING)
    @Column(name = "stock_category", nullable = false, length = 20)
    private StockCategory stockCategory;

    /** null=일반주식, 1.0=일반ETF, 2.0/3.0=레버리지, -1.0/-2.0=인버스. */
    @Column(name = "leverage_factor", precision = 4, scale = 1)
    private BigDecimal leverageFactor;

    @Column(name = "is_dividend", nullable = false)
    private Boolean isDividend;

    @Column(name = "dividend_yield", precision = 6, scale = 4)
    private BigDecimal dividendYield;

    /** false 면 우선주로 분류합니다. */
    @Column(name = "is_common_share")
    private Boolean isCommonShare;

    @Column(name = "shares_outstanding", precision = 20, scale = 0)
    private BigDecimal sharesOutstanding;

    @Column(name = "list_date")
    private LocalDate listDate;

    @Column(name = "delist_date")
    private LocalDate delistDate;

    @Column(name = "is_suspended", nullable = false)
    private Boolean isSuspended;

    @Column(name = "is_liquidation", nullable = false)
    private Boolean isLiquidation;

    @Column(name = "is_warned", nullable = false)
    private Boolean isWarned;

    @Enumerated(EnumType.STRING)
    @Column(name = "listing_status", nullable = false, length = 20)
    private ListingStatus listingStatus;

    /** 거래대금 상위 100 포함 여부. <b>시세 수집·거래 가능 판정</b>에 씁니다. */
    @Column(name = "is_ranked", nullable = false)
    private Boolean isRanked;

    /**
     * 랭킹 순위(1~100). <b>화면 표시 전용입니다.</b>
     * 배치가 통째로 다시 쓰는 값이라 커서로 쓰면 갱신 직후 같은 번호가
     * 다른 종목을 가리킵니다. 페이지 이동은 {@code tradingAmount + stockId} 로 하세요.
     */
    @Column(name = "rank_no")
    private Integer rankNo;

    /** 최근 1주 누적 거래대금. 랭킹 정렬 기준이자 커서의 1차 키. */
    @Column(name = "trading_amount", precision = 24, scale = 0)
    private BigDecimal tradingAmount;

    protected Stock() {
    }

    private Stock(String symbol, MarketCountry marketCountry, String market, String name,
                  String currency, String securityType) {
        this.symbol = symbol.trim().toUpperCase(Locale.ROOT);
        this.marketCountry = marketCountry;
        this.market = market;
        this.name = name;
        this.currency = currency;
        this.securityType = securityType;
        this.stockCategory = StockCategory.INDIVIDUAL;
        this.isDividend = false;
        this.isSuspended = false;
        this.isLiquidation = false;
        this.isWarned = false;
        this.listingStatus = ListingStatus.ACTIVE;
        this.isRanked = false;
    }

    /**
     * 마스터 배치가 신규 종목을 넣을 때 씁니다.
     * 필수값만 받고, 나머지는 {@link #updateMasterInfo} 로 채웁니다.
     */
    public static Stock create(String symbol, MarketCountry marketCountry, String market,
                               String name, String currency, String securityType) {
        return new Stock(symbol, marketCountry, market, name, currency, securityType);
    }

    /** 매주 월요일 07:00 마스터 갱신. 선택 정보는 여기서 덮어씁니다. */
    public void updateMasterInfo(String englishName, String isinCode, StockCategory category,
                                 BigDecimal leverageFactor, Boolean isCommonShare,
                                 BigDecimal sharesOutstanding, LocalDate listDate) {
        this.englishName = englishName;
        this.isinCode = isinCode;
        if (category != null) this.stockCategory = category;
        this.leverageFactor = leverageFactor;
        this.isCommonShare = isCommonShare;
        this.sharesOutstanding = sharesOutstanding;
        this.listDate = listDate;
    }

    /** 매수 유의사항 배치. 주문을 막지는 않고 화면에 배너를 띄우는 용도도 있습니다. */
    public void updateFlags(boolean suspended, boolean liquidation, boolean warned) {
        this.isSuspended = suspended;
        this.isLiquidation = liquidation;
        this.isWarned = warned;
    }

    /** 월요일 랭킹 배치가 호출합니다. */
    public void applyRanking(int rankNo, BigDecimal tradingAmount) {
        this.isRanked = true;
        this.rankNo = rankNo;
        this.tradingAmount = tradingAmount;
    }

    /** 유니버스에서 빠질 때. <b>보유자가 있는 종목은 빼면 안 됩니다</b> — 평가금액이 멈춥니다. */
    public void clearRanking() {
        this.isRanked = false;
        this.rankNo = null;
    }

    /** 지금 이 종목을 거래할 수 있는 상태인가 (장 시간 판정은 별도). */
    public boolean isTradable() {
        return Boolean.TRUE.equals(isRanked)
                && !Boolean.TRUE.equals(isSuspended)
                && !Boolean.TRUE.equals(isLiquidation)
                && listingStatus == ListingStatus.ACTIVE;
    }

    public Long getStockId() { return stockId; }
    public String getSymbol() { return symbol; }
    public MarketCountry getMarketCountry() { return marketCountry; }
    public String getMarket() { return market; }
    public String getName() { return name; }
    public String getEnglishName() { return englishName; }
    public String getIsinCode() { return isinCode; }
    public String getCurrency() { return currency; }
    public String getSecurityType() { return securityType; }
    public StockCategory getStockCategory() { return stockCategory; }
    public BigDecimal getLeverageFactor() { return leverageFactor; }
    public Boolean getIsDividend() { return isDividend; }
    public BigDecimal getDividendYield() { return dividendYield; }
    public Boolean getIsCommonShare() { return isCommonShare; }
    public BigDecimal getSharesOutstanding() { return sharesOutstanding; }
    public LocalDate getListDate() { return listDate; }
    public LocalDate getDelistDate() { return delistDate; }
    public Boolean getIsSuspended() { return isSuspended; }
    public Boolean getIsLiquidation() { return isLiquidation; }
    public Boolean getIsWarned() { return isWarned; }
    public ListingStatus getListingStatus() { return listingStatus; }
    public Boolean getIsRanked() { return isRanked; }
    public Integer getRankNo() { return rankNo; }
    public BigDecimal getTradingAmount() { return tradingAmount; }
}
