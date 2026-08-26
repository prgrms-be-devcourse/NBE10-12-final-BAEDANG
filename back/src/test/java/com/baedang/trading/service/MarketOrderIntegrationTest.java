package com.baedang.trading.service;

import com.baedang.global.error.BusinessException;
import com.baedang.global.error.ErrorCode;
import com.baedang.market.entity.QuoteSnapshot;
import com.baedang.market.port.ExecutionExchangeRateProvider;
import com.baedang.market.port.MarketSessionProvider;
import com.baedang.market.repository.QuoteSnapshotRepository;
import com.baedang.stock.entity.MarketCountry;
import com.baedang.stock.entity.Stock;
import com.baedang.stock.repository.StockRepository;
import com.baedang.trading.dto.OrderResponse;
import com.baedang.trading.dto.PlaceOrderRequest;
import com.baedang.trading.entity.Holding;
import com.baedang.trading.entity.OrderStatus;
import com.baedang.trading.entity.TradeOrder;
import com.baedang.trading.repository.HoldingRepository;
import com.baedang.trading.repository.LedgerEntryRepository;
import com.baedang.trading.repository.TradeOrderRepository;
import com.baedang.user.entity.Account;
import com.baedang.user.entity.AccountStatus;
import com.baedang.user.entity.User;
import com.baedang.user.repository.AccountRepository;
import com.baedang.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.sql.init.mode=never",
        "logging.level.org.hibernate.SQL=OFF"
})
class MarketOrderIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18-alpine")
            .withCopyFileToContainer(
                    MountableFile.forHostPath(Path.of("..", "infra", "schema.sql")
                            .toAbsolutePath().normalize()),
                    "/docker-entrypoint-initdb.d/01-schema.sql");

    @MockitoBean MarketSessionProvider marketSessionProvider;
    @MockitoBean ExecutionExchangeRateProvider exchangeRateProvider;

    @Autowired MarketOrderService marketOrderService;
    @Autowired UserRepository userRepository;
    @Autowired AccountRepository accountRepository;
    @Autowired StockRepository stockRepository;
    @Autowired QuoteSnapshotRepository quoteSnapshotRepository;
    @Autowired HoldingRepository holdingRepository;
    @Autowired TradeOrderRepository tradeOrderRepository;
    @Autowired LedgerEntryRepository ledgerEntryRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUpProviders() {
        when(marketSessionProvider.isOpen(any(), any())).thenReturn(true);
        when(exchangeRateProvider.currentUsdKrwRate()).thenReturn(new BigDecimal("1383.60"));
    }

    @Test
    void 시장가_매수는_주문_잔액_보유수량_원장을_한번에_확정한다() {
        Fixture fixture = createKrFixture(new BigDecimal("50000"), new BigDecimal("10000"));
        PlaceOrderRequest request = request(fixture.symbol(), "BUY", "2");

        OrderResponse response = marketOrderService.place(fixture.userId(), request);

        Account account = activeAccount(fixture.userId());
        Holding holding = holdingRepository
                .findByAccountIdAndStockId(account.getAccountId(), fixture.stockId()).orElseThrow();
        TradeOrder order = tradeOrderRepository.findById(response.orderId()).orElseThrow();

        assertThat(order.getStatus()).isEqualTo(OrderStatus.FILLED);
        assertThat(account.getCashBalance()).isEqualByComparingTo("29998");
        assertThat(account.getLockedCash()).isEqualByComparingTo("0");
        assertThat(holding.getQuantity()).isEqualByComparingTo("2");
        assertThat(response.account().totalAsset()).isEqualTo("49998");
        assertThat(ledgerEntryRepository.findByOrderId(order.getOrderId()).orElseThrow().getAmount())
                .isEqualByComparingTo("-20002");
    }

    @Test
    void 시장가_매도는_수량을_줄이되_평단가는_유지한다() {
        Fixture fixture = createKrFixture(new BigDecimal("10000"), new BigDecimal("10000"));
        Account account = activeAccount(fixture.userId());
        holdingRepository.save(Holding.firstBuy(
                account.getAccountId(), fixture.stockId(), new BigDecimal("5"),
                new BigDecimal("8000"), BigDecimal.ONE, OffsetDateTime.now(ZoneOffset.UTC)));

        OrderResponse response = marketOrderService.place(
                fixture.userId(), request(fixture.symbol(), "SELL", "2"));

        Account updated = activeAccount(fixture.userId());
        Holding holding = holdingRepository
                .findByAccountIdAndStockId(updated.getAccountId(), fixture.stockId()).orElseThrow();
        assertThat(updated.getCashBalance()).isEqualByComparingTo("29958");
        assertThat(holding.getQuantity()).isEqualByComparingTo("3");
        assertThat(holding.getAvgBuyPrice()).isEqualByComparingTo("8000");
        assertThat(response.netAmount()).isEqualTo("19958");
        assertThat(response.account().totalAsset()).isEqualTo("59958");
    }

    @Test
    void 업무_검증_실패는_REJECTED로_기록하고_잔액과_원장을_변경하지_않는다() {
        Fixture fixture = createKrFixture(new BigDecimal("10000"), new BigDecimal("10000"));
        PlaceOrderRequest request = request(fixture.symbol(), "BUY", "2");

        assertThatThrownBy(() -> marketOrderService.place(fixture.userId(), request))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INSUFFICIENT_CASH);

        Account account = activeAccount(fixture.userId());
        TradeOrder rejected = tradeOrderRepository
                .findByClientOrderId(UUID.fromString(request.clientOrderId())).orElseThrow();
        assertThat(rejected.getStatus()).isEqualTo(OrderStatus.REJECTED);
        assertThat(rejected.getRejectReason()).isEqualTo("INSUFFICIENT_CASH");
        assertThat(account.getCashBalance()).isEqualByComparingTo("10000");
        assertThat(ledgerEntryRepository.findByOrderId(rejected.getOrderId())).isEmpty();
        assertThat(rejected.getReferencePrice()).isEqualByComparingTo("10000");
        assertThat(rejected.getQuoteAt()).isNotNull();
        assertThat(rejected.getExchangeRate()).isEqualByComparingTo("1");
    }

    @Test
    void 같은_미국종목을_두번_매수하면_평균환율을_달러취득원가로_가중한다() {
        Fixture fixture = createUsFixture(new BigDecimal("10000000"), new BigDecimal("100"));
        when(exchangeRateProvider.currentUsdKrwRate())
                .thenReturn(new BigDecimal("1300"), new BigDecimal("1400"));

        marketOrderService.place(fixture.userId(), request(fixture.symbol(), "BUY", "10"));
        QuoteSnapshot quote = quoteSnapshotRepository.findById(fixture.stockId()).orElseThrow();
        quote.updatePrice(new BigDecimal("200"), OffsetDateTime.now(ZoneOffset.UTC));
        quoteSnapshotRepository.save(quote);

        marketOrderService.place(fixture.userId(), request(fixture.symbol(), "BUY", "10"));

        Account account = activeAccount(fixture.userId());
        Holding holding = holdingRepository
                .findByAccountIdAndStockId(account.getAccountId(), fixture.stockId()).orElseThrow();
        assertThat(holding.getQuantity()).isEqualByComparingTo("20");
        assertThat(holding.getAvgBuyPrice()).isEqualByComparingTo("150.0000");
        assertThat(holding.getAvgExchangeRate()).isEqualByComparingTo("1366.666667");
        assertThat(account.getCashBalance()).isEqualByComparingTo("5899590");
        assertThat(tradeOrderRepository.countByAccountId(account.getAccountId())).isEqualTo(2);
        assertThat(ledgerEntryRepository.countByAccountId(account.getAccountId())).isEqualTo(2);
    }

    @Test
    void 운영_스키마의_보유수량_제약조건이_실제_DB에_적용된다() {
        Fixture fixture = createKrFixture(new BigDecimal("50000"), new BigDecimal("10000"));

        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO holding (
                    account_id, stock_id, quantity, locked_quantity,
                    avg_buy_price, avg_exchange_rate
                ) VALUES (?, ?, 1, 2, 10000, 1)
                """, fixture.accountId(), fixture.stockId()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void 동일한_clientOrderId_재시도는_한번만_체결한다() {
        Fixture fixture = createKrFixture(new BigDecimal("50000"), new BigDecimal("10000"));
        PlaceOrderRequest request = request(fixture.symbol(), "BUY", "1");

        OrderResponse first = marketOrderService.place(fixture.userId(), request);
        OrderResponse retried = marketOrderService.place(fixture.userId(), request);

        Account account = activeAccount(fixture.userId());
        assertThat(retried.orderId()).isEqualTo(first.orderId());
        assertThat(tradeOrderRepository.countByAccountId(account.getAccountId())).isEqualTo(1);
        assertThat(ledgerEntryRepository.countByAccountId(account.getAccountId())).isEqualTo(1);
        assertThat(account.getCashBalance()).isEqualByComparingTo("39999");
    }

    @Test
    void 같은_clientOrderId에_다른_주문내용을_보내면_충돌로_거절한다() {
        Fixture fixture = createKrFixture(new BigDecimal("50000"), new BigDecimal("10000"));
        PlaceOrderRequest first = request(fixture.symbol(), "BUY", "1");
        PlaceOrderRequest changed = new PlaceOrderRequest(
                first.clientOrderId(), fixture.symbol(), "BUY", "2");

        marketOrderService.place(fixture.userId(), first);

        assertThatThrownBy(() -> marketOrderService.place(fixture.userId(), changed))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.DUPLICATE_ORDER);

        Account account = activeAccount(fixture.userId());
        assertThat(tradeOrderRepository.countByAccountId(account.getAccountId())).isEqualTo(1);
        assertThat(ledgerEntryRepository.countByAccountId(account.getAccountId())).isEqualTo(1);
        assertThat(account.getCashBalance()).isEqualByComparingTo("39999");
    }

    @Test
    void 동시_매수는_계좌_락으로_이중_차감을_방지한다() throws Exception {
        Fixture fixture = createKrFixture(new BigDecimal("15000"), new BigDecimal("10000"));
        PlaceOrderRequest first = request(fixture.symbol(), "BUY", "1");
        PlaceOrderRequest second = request(fixture.symbol(), "BUY", "1");
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<Object> firstResult = executor.submit(() -> invokeAfter(start, fixture.userId(), first));
            Future<Object> secondResult = executor.submit(() -> invokeAfter(start, fixture.userId(), second));
            start.countDown();

            Object a = firstResult.get();
            Object b = secondResult.get();
            assertThat(java.util.List.of(a, b).stream().filter(OrderResponse.class::isInstance).count())
                    .isEqualTo(1);
            assertThat(java.util.List.of(a, b).stream()
                    .filter(BusinessException.class::isInstance)
                    .map(BusinessException.class::cast)
                    .map(BusinessException::getErrorCode))
                    .containsExactly(ErrorCode.INSUFFICIENT_CASH);
        }

        Account account = activeAccount(fixture.userId());
        assertThat(account.getCashBalance()).isEqualByComparingTo("4999");
        assertThat(account.getLockedCash()).isEqualByComparingTo("0");
        assertThat(tradeOrderRepository.countByAccountId(account.getAccountId())).isEqualTo(2);
        assertThat(ledgerEntryRepository.countByAccountId(account.getAccountId())).isEqualTo(1);
    }

    private Object invokeAfter(CountDownLatch start, Long userId, PlaceOrderRequest request) {
        try {
            start.await();
            return marketOrderService.place(userId, request);
        } catch (BusinessException e) {
            return e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private Fixture createKrFixture(BigDecimal initialCash, BigDecimal price) {
        return createFixture(initialCash, price, MarketCountry.KR, "KOSPI", "KRW");
    }

    private Fixture createUsFixture(BigDecimal initialCash, BigDecimal price) {
        return createFixture(initialCash, price, MarketCountry.US, "NASDAQ", "USD");
    }

    private Fixture createFixture(
            BigDecimal initialCash,
            BigDecimal price,
            MarketCountry marketCountry,
            String market,
            String currency
    ) {
        String suffix = UUID.randomUUID().toString();
        User user = userRepository.save(User.create(
                suffix + "@example.com", "password-hash", "user-" + suffix.substring(0, 8)));
        Account account = accountRepository.save(Account.open(user.getUserId(), 1, initialCash));
        String symbol = suffix.substring(0, 6).toUpperCase();
        Stock stock = Stock.create(symbol, marketCountry, market, "테스트 종목", currency, "STOCK");
        stock.applyRanking(1, new BigDecimal("1000000"));
        stockRepository.save(stock);
        quoteSnapshotRepository.save(new QuoteSnapshot(
                stock.getStockId(), price, "KRW", OffsetDateTime.now(ZoneOffset.UTC)));
        return new Fixture(user.getUserId(), account.getAccountId(), stock.getStockId(), symbol);
    }

    private Account activeAccount(Long userId) {
        return accountRepository.findByUserIdAndStatus(userId, AccountStatus.ACTIVE).orElseThrow();
    }

    private PlaceOrderRequest request(String symbol, String side, String quantity) {
        return new PlaceOrderRequest(UUID.randomUUID().toString(), symbol, side, quantity);
    }

    private record Fixture(Long userId, Long accountId, Long stockId, String symbol) {
    }
}
