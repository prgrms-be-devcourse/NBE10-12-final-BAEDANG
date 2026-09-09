package com.baedang.stock.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.baedang.global.clients.kis.KisProperties;
import com.baedang.global.error.BusinessException;
import com.baedang.global.error.ErrorCode;
import com.baedang.stock.dto.StockFinancialResponse;
import com.baedang.stock.entity.FinancialPeriodType;
import com.baedang.stock.entity.MarketCountry;
import com.baedang.stock.entity.Stock;
import com.baedang.stock.entity.StockCategory;
import com.baedang.stock.entity.StockFinancialPeriod;
import com.baedang.stock.entity.StockFinancialSync;
import com.baedang.stock.entity.StockIndustry;
import com.baedang.stock.port.StockFinancialInfoPort.BalanceSheet;
import com.baedang.stock.port.StockFinancialInfoPort.IncomeStatement;
import com.baedang.stock.port.StockFinancialInfoPort.IndustryClassification;
import com.baedang.stock.port.StockFinancialInfoPort.IndustryData;
import com.baedang.stock.port.StockFinancialInfoPort.Ratios;
import com.baedang.stock.repository.StockFinancialPeriodRepository;
import com.baedang.stock.repository.StockFinancialSyncRepository;
import com.baedang.stock.repository.StockIndustryRepository;
import com.baedang.stock.repository.StockRepository;
import com.baedang.stock.service.StockFinancialSyncService.GroupResult;
import com.baedang.stock.service.StockFinancialSyncService.GroupStatus;
import com.baedang.stock.service.StockFinancialSyncService.SyncResult;
import com.baedang.stock.service.StockFinancialSyncService.SyncTrigger;

@ExtendWith(MockitoExtension.class)
class StockFinancialQueryServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-09T00:00:00Z");
    private static final long STOCK_ID = 10L;
    private static final String SYMBOL = "005930";

    @Mock StockRepository stockRepository;
    @Mock StockFinancialSyncService syncService;
    @Mock StockIndustryRepository industryRepository;
    @Mock StockFinancialPeriodRepository periodRepository;
    @Mock StockFinancialSyncRepository syncRepository;
    @Mock Stock stock;

    private StockFinancialQueryService service;

    @BeforeEach
    void setUp() {
        service = service(true);
        lenient().when(stock.getStockId()).thenReturn(STOCK_ID);
        lenient().when(stock.getSymbol()).thenReturn(SYMBOL);
        lenient().when(stock.getMarketCountry()).thenReturn(MarketCountry.KR);
        lenient().when(stock.getStockCategory()).thenReturn(StockCategory.INDIVIDUAL);
    }

    @Test
    void missing_valid_stock_code_throws_STOCK_NOT_FOUND() {
        when(stockRepository.findBySymbolIgnoreCaseAndMarketCountry("999999", MarketCountry.KR))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getFinancials("999999", "KR"))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.STOCK_NOT_FOUND));
    }

    @Test
    void invalid_market_country_string_throws_INVALID_INPUT() {
        assertThatThrownBy(() -> service.getFinancials(SYMBOL, "INVALID"))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.INVALID_INPUT));
    }

    @Test
    void malformed_KR_stock_code_throws_FINANCIALS_NOT_SUPPORTED_before_lookup() {
        assertThatThrownBy(() -> service.getFinancials("12345A", "KR"))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.FINANCIALS_NOT_SUPPORTED));

        verify(stockRepository, never()).findBySymbolIgnoreCaseAndMarketCountry(any(), any());
    }

    @Test
    void US_market_throws_FINANCIALS_NOT_SUPPORTED_before_lookup() {
        assertThatThrownBy(() -> service.getFinancials("AAPL", "US"))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.FINANCIALS_NOT_SUPPORTED));

        verify(stockRepository, never()).findBySymbolIgnoreCaseAndMarketCountry(any(), any());
    }

    static Stream<Arguments> unsupportedCategories() {
        return Stream.of(
                Arguments.of(MarketCountry.KR, StockCategory.ETF, "069500"),
                Arguments.of(MarketCountry.KR, StockCategory.ETN, "500001"));
    }

    @ParameterizedTest
    @MethodSource("unsupportedCategories")
    void unsupported_category_throws_FINANCIALS_NOT_SUPPORTED_without_sync(
            MarketCountry country, StockCategory category, String symbol) {
        when(stockRepository.findBySymbolIgnoreCaseAndMarketCountry(symbol, country))
                .thenReturn(Optional.of(stock));
        lenient().when(stock.getMarketCountry()).thenReturn(country);
        lenient().when(stock.getStockCategory()).thenReturn(category);
        lenient().when(stock.getSymbol()).thenReturn(symbol);

        assertThatThrownBy(() -> service.getFinancials(symbol, country.name()))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.FINANCIALS_NOT_SUPPORTED));

        verify(syncService, never()).ensureFresh(any(), any());
    }

    @Test
    void fresh_cache_returns_FRESH_and_sync_service_does_not_refresh() {
        when(stockRepository.findBySymbolIgnoreCaseAndMarketCountry(SYMBOL, MarketCountry.KR))
                .thenReturn(Optional.of(stock));
        when(syncService.ensureFresh(stock, SyncTrigger.ON_DEMAND))
                .thenReturn(new SyncResult(
                        GroupResult.fresh(),
                        GroupResult.fresh(),
                        GroupResult.fresh()));
        stubDatabaseData(NOW.minus(Duration.ofDays(1)));

        StockFinancialResponse response = service.getFinancials(SYMBOL, "KR");

        assertThat(response.symbol()).isEqualTo(SYMBOL);
        assertThat(response.marketCountry()).isEqualTo("KR");
        assertThat(response.dataStatus()).isEqualTo("FRESH");
        assertThat(response.annual()).hasSize(1);
        assertThat(response.quarterly()).hasSize(1);
        assertThat(response.industry().standard().code()).isEqualTo("0326");
    }

    @Test
    void stale_or_missing_cache_triggers_ensureFresh_and_returns_updated_data() {
        when(stockRepository.findBySymbolIgnoreCaseAndMarketCountry(SYMBOL, MarketCountry.KR))
                .thenReturn(Optional.of(stock));
        when(syncService.ensureFresh(stock, SyncTrigger.ON_DEMAND))
                .thenReturn(new SyncResult(
                        GroupResult.updated(false),
                        GroupResult.updated(false),
                        GroupResult.updated(false)));
        stubDatabaseData(NOW);

        StockFinancialResponse response = service.getFinancials(SYMBOL, "KR");

        assertThat(response.dataStatus()).isEqualTo("FRESH");
        verify(syncService).ensureFresh(stock, SyncTrigger.ON_DEMAND);
    }

    @Test
    void failed_sync_with_existing_cache_returns_STALE() {
        when(stockRepository.findBySymbolIgnoreCaseAndMarketCountry(SYMBOL, MarketCountry.KR))
                .thenReturn(Optional.of(stock));
        // previous sync existed before calling ensureFresh
        when(syncRepository.findById(STOCK_ID)).thenReturn(Optional.of(sync(
                NOW.minus(Duration.ofDays(35)),
                NOW.minus(Duration.ofDays(10)),
                NOW.minus(Duration.ofDays(10)))));
        when(syncService.ensureFresh(stock, SyncTrigger.ON_DEMAND))
                .thenReturn(new SyncResult(
                        GroupResult.updated(false),
                        GroupResult.failed(ErrorCode.KIS_RATE_LIMITED),
                        GroupResult.updated(false)));
        stubDatabaseData(NOW.minus(Duration.ofDays(10)));

        StockFinancialResponse response = service.getFinancials(SYMBOL, "KR");

        assertThat(response.dataStatus()).isEqualTo("STALE");
        assertThat(response.annual()).hasSize(1);
    }

    @Test
    void failed_sync_without_existing_cache_throws_original_failure_code() {
        when(stockRepository.findBySymbolIgnoreCaseAndMarketCountry(SYMBOL, MarketCountry.KR))
                .thenReturn(Optional.of(stock));
        when(syncRepository.findById(STOCK_ID)).thenReturn(Optional.empty());
        when(syncService.ensureFresh(stock, SyncTrigger.ON_DEMAND))
                .thenReturn(new SyncResult(
                        GroupResult.updated(false),
                        GroupResult.failed(ErrorCode.KIS_RATE_LIMITED),
                        GroupResult.updated(false)));

        assertThatThrownBy(() -> service.getFinancials(SYMBOL, "KR"))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.KIS_RATE_LIMITED));
    }

    @Test
    void disabled_kis_with_all_cached_within_ttl_returns_FRESH() {
        service = service(false);
        when(stockRepository.findBySymbolIgnoreCaseAndMarketCountry(SYMBOL, MarketCountry.KR))
                .thenReturn(Optional.of(stock));
        stubDatabaseData(NOW.minus(Duration.ofDays(1)));

        StockFinancialResponse response = service.getFinancials(SYMBOL, "KR");

        assertThat(response.dataStatus()).isEqualTo("FRESH");
        verify(syncService, never()).ensureFresh(any(), any());
    }

    @Test
    void disabled_kis_with_cache_beyond_ttl_returns_STALE() {
        service = service(false);
        when(stockRepository.findBySymbolIgnoreCaseAndMarketCountry(SYMBOL, MarketCountry.KR))
                .thenReturn(Optional.of(stock));
        stubDatabaseData(NOW.minus(Duration.ofDays(15)));

        StockFinancialResponse response = service.getFinancials(SYMBOL, "KR");

        assertThat(response.dataStatus()).isEqualTo("STALE");
        verify(syncService, never()).ensureFresh(any(), any());
    }

    @Test
    void disabled_kis_with_missing_group_cache_throws_KIS_API_UNAVAILABLE() {
        service = service(false);
        when(stockRepository.findBySymbolIgnoreCaseAndMarketCountry(SYMBOL, MarketCountry.KR))
                .thenReturn(Optional.of(stock));
        // quarterly is null
        when(syncRepository.findById(STOCK_ID)).thenReturn(Optional.of(sync(NOW, NOW, null)));

        assertThatThrownBy(() -> service.getFinancials(SYMBOL, "KR"))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.KIS_API_UNAVAILABLE));
    }

    @Test
    void normal_empty_sync_returns_200_with_empty_arrays_and_FRESH() {
        when(stockRepository.findBySymbolIgnoreCaseAndMarketCountry(SYMBOL, MarketCountry.KR))
                .thenReturn(Optional.of(stock));
        when(syncService.ensureFresh(stock, SyncTrigger.ON_DEMAND))
                .thenReturn(new SyncResult(
                        GroupResult.updated(true),
                        GroupResult.updated(true),
                        GroupResult.updated(true)));

        when(syncRepository.findById(STOCK_ID)).thenReturn(Optional.of(sync(NOW, NOW, NOW)));
        when(industryRepository.findById(STOCK_ID)).thenReturn(Optional.empty());
        when(periodRepository.findByStockIdAndPeriodTypeOrderByStatementYearMonthDesc(
                STOCK_ID, FinancialPeriodType.ANNUAL)).thenReturn(List.of());
        when(periodRepository.findByStockIdAndPeriodTypeOrderByStatementYearMonthDesc(
                STOCK_ID, FinancialPeriodType.QUARTERLY)).thenReturn(List.of());

        StockFinancialResponse response = service.getFinancials(SYMBOL, "KR");

        assertThat(response.dataStatus()).isEqualTo("FRESH");
        assertThat(response.annual()).isEmpty();
        assertThat(response.quarterly()).isEmpty();
        assertThat(response.industry()).isNull();
    }

    @Test
    void operating_profit_margin_calculated_with_scale_6_and_null_when_sales_zero_or_null() {
        when(stockRepository.findBySymbolIgnoreCaseAndMarketCountry(SYMBOL, MarketCountry.KR))
                .thenReturn(Optional.of(stock));
        when(syncService.ensureFresh(stock, SyncTrigger.ON_DEMAND))
                .thenReturn(new SyncResult(
                        GroupResult.fresh(),
                        GroupResult.fresh(),
                        GroupResult.fresh()));

        StockFinancialPeriod p1 = period(FinancialPeriodType.ANNUAL, "202512", new BigDecimal("1000"), new BigDecimal("123"));
        StockFinancialPeriod p2 = period(FinancialPeriodType.ANNUAL, "202412", BigDecimal.ZERO, new BigDecimal("100"));
        StockFinancialPeriod p3 = period(FinancialPeriodType.ANNUAL, "202312", null, new BigDecimal("100"));

        when(syncRepository.findById(STOCK_ID)).thenReturn(Optional.of(sync(NOW, NOW, NOW)));
        when(industryRepository.findById(STOCK_ID)).thenReturn(Optional.empty());
        when(periodRepository.findByStockIdAndPeriodTypeOrderByStatementYearMonthDesc(
                STOCK_ID, FinancialPeriodType.ANNUAL)).thenReturn(List.of(p1, p2, p3));
        when(periodRepository.findByStockIdAndPeriodTypeOrderByStatementYearMonthDesc(
                STOCK_ID, FinancialPeriodType.QUARTERLY)).thenReturn(List.of());

        StockFinancialResponse response = service.getFinancials(SYMBOL, "KR");

        assertThat(response.annual().get(0).ratios().operatingProfitMargin()).isEqualTo("12.3");
        assertThat(response.annual().get(1).ratios().operatingProfitMargin()).isNull();
        assertThat(response.annual().get(2).ratios().operatingProfitMargin()).isNull();
    }

    private StockFinancialQueryService service(boolean kisEnabled) {
        return new StockFinancialQueryService(
                stockRepository,
                syncService,
                industryRepository,
                periodRepository,
                syncRepository,
                properties(kisEnabled),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private void stubDatabaseData(Instant syncedAt) {
        when(syncRepository.findById(STOCK_ID)).thenReturn(Optional.of(sync(syncedAt, syncedAt, syncedAt)));
        StockIndustry industry = StockIndustry.create(STOCK_ID, industryData(), syncedAt);
        when(industryRepository.findById(STOCK_ID)).thenReturn(Optional.of(industry));

        StockFinancialPeriod annualPeriod = period(FinancialPeriodType.ANNUAL, "202512",
                new BigDecimal("500000"), new BigDecimal("50000"));
        StockFinancialPeriod quarterlyPeriod = period(FinancialPeriodType.QUARTERLY, "202509",
                new BigDecimal("120000"), new BigDecimal("15000"));

        when(periodRepository.findByStockIdAndPeriodTypeOrderByStatementYearMonthDesc(
                STOCK_ID, FinancialPeriodType.ANNUAL)).thenReturn(List.of(annualPeriod));
        when(periodRepository.findByStockIdAndPeriodTypeOrderByStatementYearMonthDesc(
                STOCK_ID, FinancialPeriodType.QUARTERLY)).thenReturn(List.of(quarterlyPeriod));
    }

    private static KisProperties properties(boolean enabled) {
        return new KisProperties(
                enabled,
                URI.create("https://example.test"),
                "key",
                "secret",
                18,
                Duration.ofSeconds(3),
                Duration.ofSeconds(5),
                Duration.ofDays(7),
                Duration.ofDays(30),
                false);
    }

    private static StockFinancialSync sync(Instant industryAt, Instant annualAt, Instant quarterlyAt) {
        StockFinancialSync sync = StockFinancialSync.create(STOCK_ID);
        if (industryAt != null) sync.markIndustrySynced(industryAt);
        if (annualAt != null) sync.markFinancialSynced(FinancialPeriodType.ANNUAL, annualAt);
        if (quarterlyAt != null) sync.markFinancialSynced(FinancialPeriodType.QUARTERLY, quarterlyAt);
        return sync;
    }

    private static IndustryData industryData() {
        IndustryClassification standard = new IndustryClassification("0326", "전자부품 제조");
        IndustryClassification large = new IndustryClassification("03", "제조업");
        IndustryClassification medium = new IndustryClassification("0326", "전자부품 제조");
        IndustryClassification small = new IndustryClassification("03261", "반도체 제조");
        return new IndustryData(standard, large, medium, small);
    }

    private static StockFinancialPeriod period(
            FinancialPeriodType type, String yearMonth, BigDecimal sales, BigDecimal op) {
        StockFinancialPeriod p = StockFinancialPeriod.create(STOCK_ID, type, yearMonth);
        p.applyIncomeStatement(new IncomeStatement(sales, op, new BigDecimal("40000")));
        p.applyBalanceSheet(new BalanceSheet(
                new BigDecimal("100000"), new BigDecimal("200000"), new BigDecimal("300000"),
                new BigDecimal("50000"), new BigDecimal("50000"), new BigDecimal("100000"),
                new BigDecimal("10000"), new BigDecimal("20000"), new BigDecimal("170000"),
                new BigDecimal("200000")));
        p.applyFinancialRatios(new Ratios(
                new BigDecimal("10.5"), new BigDecimal("12.3"), new BigDecimal("8.2"),
                new BigDecimal("15.1"), new BigDecimal("5200"), new BigDecimal("65000"),
                new BigDecimal("42000"), new BigDecimal("850.5"), new BigDecimal("50.0"),
                new BigDecimal("8.0")));
        return p;
    }
}
