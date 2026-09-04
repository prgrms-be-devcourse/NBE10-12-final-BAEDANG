package com.baedang.trading.service;

import com.baedang.global.error.BusinessException;
import com.baedang.global.error.ErrorCode;
import com.baedang.market.entity.QuoteSnapshot;
import com.baedang.market.port.ExecutionExchangeRateProvider;
import com.baedang.market.port.MarketCalendarPort;
import com.baedang.market.port.MarketSessionProvider;
import com.baedang.market.port.MarketSessionStatus;
import com.baedang.market.repository.QuoteSnapshotRepository;
import com.baedang.stock.entity.MarketCountry;
import com.baedang.stock.entity.Stock;
import com.baedang.stock.repository.StockRepository;
import com.baedang.trading.dto.OrderQuoteResponse;
import com.baedang.trading.dto.OrderResponse;
import com.baedang.trading.dto.PlaceOrderRequest;
import com.baedang.trading.entity.EntryType;
import com.baedang.trading.entity.Holding;
import com.baedang.trading.entity.LedgerEntry;
import com.baedang.trading.entity.OrderStatus;
import com.baedang.trading.entity.OrderSide;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verifyNoInteractions;

@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.sql.init.mode=never",
        "trading.execution-context-max-age-seconds=1",
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
    // 개발용 대역(Fake) 구현체가 없어졌으므로, 이 테스트가 관심 없는 MarketCalendarPort
    // 의존을 목(mock)으로 채워 넣어야 컨텍스트가 뜬다(다른 서비스가 직접 주입받는다).
    @MockitoBean MarketCalendarPort marketCalendarPort;

    @Autowired MarketOrderService marketOrderService;
    @Autowired OrderQuoteService orderQuoteService;
    @Autowired UserRepository userRepository;
    @Autowired AccountRepository accountRepository;
    @Autowired StockRepository stockRepository;
    @Autowired QuoteSnapshotRepository quoteSnapshotRepository;
    @Autowired HoldingRepository holdingRepository;
    @Autowired TradeOrderRepository tradeOrderRepository;
    @Autowired LedgerEntryRepository ledgerEntryRepository;
    @Autowired com.baedang.trading.repository.TradeExecutionRepository tradeExecutionRepository;
    @Autowired OrderAmountCalculator amountCalculator;
    @Autowired LedgerService ledgerService;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired PlatformTransactionManager transactionManager;

    @BeforeEach
    void setUpProviders() {
        when(marketSessionProvider.currentSession(any(), any()))
                .thenReturn(new MarketSessionStatus(true, Instant.MAX));
        when(marketSessionProvider.isOpen(any(), any())).thenReturn(true);
        when(exchangeRateProvider.currentUsdKrwRate()).thenReturn(new BigDecimal("1383.60"));
    }

    @Test
    void 시장가_매수는_주문_잔액_보유수량_원장을_한번에_확정한다() {
        Fixture fixture = createKrFixture(new BigDecimal("50000"), new BigDecimal("10000"));
        PlaceOrderRequest request = request(fixture, "BUY", "2");

        OrderResponse response = marketOrderService.place(fixture.userId(), request);

        Account account = activeAccount(fixture.userId());
        Holding holding = holdingRepository
                .findByAccountIdAndStockId(account.getAccountId(), fixture.stockId()).orElseThrow();
        TradeOrder order = tradeOrderRepository.findById(response.orderId()).orElseThrow();

        assertThat(order.getStatus()).isEqualTo(OrderStatus.FILLED);
        var execution = tradeExecutionRepository.findByOrderIdAndExecutionKey(order.getOrderId(), order.getClientOrderId()).orElseThrow();
        assertThat(execution.getQuantity()).isEqualByComparingTo("2");
        assertThat(execution.getGrossAmountKrw()).isEqualByComparingTo("20000");
        assertThat(execution.getBookLevelId()).isNull();
        assertThat(ledgerEntryRepository.findFirstByOrderIdOrderByEntryIdAsc(order.getOrderId())
                .orElseThrow().getExecutionId()).isEqualTo(execution.getExecutionId());
        assertThat(account.getCashBalance()).isEqualByComparingTo("29998");
        assertThat(account.getLockedCash()).isEqualByComparingTo("0");
        assertThat(holding.getQuantity()).isEqualByComparingTo("2");
        assertThat(holding.getUsdPurchaseAmount()).isZero();
        assertThat(holding.getKrwPurchaseAmount()).isEqualByComparingTo("20000");
        assertThat(response.account().cashBalanceAfter()).isEqualTo("29998");
        assertThat(ledgerEntryRepository.findFirstByOrderIdOrderByEntryIdAsc(
                order.getOrderId()).orElseThrow().getAmount())
                .isEqualByComparingTo("-20002");
    }

    @Test
    void 초기화된_계좌의_새_주문은_새_회차로_이월하지_않고_거절한다() {
        Fixture fixture = createKrFixture(new BigDecimal("50000"), new BigDecimal("10000"));
        Account oldAccount = accountRepository.findById(fixture.accountId()).orElseThrow();
        oldAccount.close(OffsetDateTime.now(ZoneOffset.UTC));
        accountRepository.saveAndFlush(oldAccount);
        Account nextAccount = accountRepository.save(Account.open(
                fixture.userId(), 2, new BigDecimal("50000"), OffsetDateTime.now(ZoneOffset.UTC)));
        PlaceOrderRequest request = request(fixture, "BUY", "1");

        assertThatThrownBy(() -> marketOrderService.place(fixture.userId(), request))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.ACCOUNT_ROUND_CHANGED);
                    assertThat(exception.getData()).containsEntry("retryPolicy", "NOT_RETRYABLE");
                });

        assertThat(tradeOrderRepository.countByAccountId(fixture.accountId())).isZero();
        assertThat(tradeOrderRepository.countByAccountId(nextAccount.getAccountId())).isZero();
        assertThat(accountRepository.findById(nextAccount.getAccountId()).orElseThrow().getCashBalance())
                .isEqualByComparingTo("50000");
        verifyNoInteractions(marketSessionProvider, exchangeRateProvider);
    }

    @Test
    void 초기화_전에_체결된_주문의_멱등_재요청은_기존_결과를_반환한다() {
        Fixture fixture = createKrFixture(new BigDecimal("50000"), new BigDecimal("10000"));
        PlaceOrderRequest request = request(fixture, "BUY", "1");
        OrderResponse first = marketOrderService.place(fixture.userId(), request);
        Account oldAccount = accountRepository.findById(fixture.accountId()).orElseThrow();
        oldAccount.close(OffsetDateTime.now(ZoneOffset.UTC));
        accountRepository.saveAndFlush(oldAccount);
        accountRepository.saveAndFlush(Account.open(
                fixture.userId(), 2, new BigDecimal("50000"), OffsetDateTime.now(ZoneOffset.UTC)));
        clearInvocations(marketSessionProvider, exchangeRateProvider);

        OrderResponse retried = marketOrderService.place(fixture.userId(), request);

        assertThat(retried.orderId()).isEqualTo(first.orderId());
        assertThat(retried.status()).isEqualTo(first.status());
        assertThat(retried.netAmount()).isEqualTo(first.netAmount());
        assertThat(retried.account().cashBalanceAfter())
                .isEqualTo(first.account().cashBalanceAfter());
        verifyNoInteractions(marketSessionProvider, exchangeRateProvider);
    }

    @Test
    void 탈퇴한_회원의_기존_주문_멱등_재요청은_기존_결과를_반환한다() {
        Fixture fixture = createKrFixture(new BigDecimal("50000"), new BigDecimal("10000"));
        PlaceOrderRequest request = request(fixture, "BUY", "1");
        OrderResponse first = marketOrderService.place(fixture.userId(), request);

        User user = userRepository.findById(fixture.userId()).orElseThrow();
        user.withdraw();
        userRepository.saveAndFlush(user);
        Account account = accountRepository.findById(fixture.accountId()).orElseThrow();
        account.close(OffsetDateTime.now(ZoneOffset.UTC));
        accountRepository.saveAndFlush(account);

        OrderResponse retried = marketOrderService.place(fixture.userId(), request);

        assertThat(retried.orderId()).isEqualTo(first.orderId());
        assertThat(retried.status()).isEqualTo(first.status());
        assertThat(tradeOrderRepository.countByAccountId(fixture.accountId())).isEqualTo(1);
    }

    @Test
    void 같은_심볼이_국내와_미국에_있어도_요청한_시장의_종목으로_체결한다() {
        String symbol = "DUP" + UUID.randomUUID().toString().substring(0, 5).toUpperCase();
        createFixture(
                new BigDecimal("100000"), new BigDecimal("10000"),
                MarketCountry.KR, "KOSPI", "KRW", symbol);
        Fixture usFixture = createFixture(
                new BigDecimal("1000000"), new BigDecimal("10"),
                MarketCountry.US, "NASDAQ", "USD", symbol);

        OrderResponse response = marketOrderService.place(
                usFixture.userId(),
                new PlaceOrderRequest(
                        usFixture.accountId(), UUID.randomUUID().toString(), symbol.toLowerCase(),
                        MarketCountry.US.name(), "BUY", "1"));

        TradeOrder order = tradeOrderRepository.findById(response.orderId()).orElseThrow();
        assertThat(order.getStockId()).isEqualTo(usFixture.stockId());
        assertThat(response.exchangeRate()).isEqualTo("1383.6");
    }

    @Test
    void 시장가_매도는_수량을_줄이되_평단가는_유지한다() {
        Fixture fixture = createKrFixture(new BigDecimal("10000"), new BigDecimal("10000"));
        Account account = activeAccount(fixture.userId());
        holdingRepository.save(Holding.firstBuy(
                account.getAccountId(), fixture.stockId(), new BigDecimal("5"),
                BigDecimal.ZERO, new BigDecimal("40000"),
                OffsetDateTime.now(ZoneOffset.UTC)));

        OrderResponse response = marketOrderService.place(
                fixture.userId(), request(fixture, "SELL", "2"));

        Account updated = activeAccount(fixture.userId());
        Holding holding = holdingRepository
                .findByAccountIdAndStockId(updated.getAccountId(), fixture.stockId()).orElseThrow();
        assertThat(updated.getCashBalance()).isEqualByComparingTo("29958");
        assertThat(holding.getQuantity()).isEqualByComparingTo("3");
        assertThat(holding.getAvgBuyPrice()).isEqualByComparingTo("8000");
        assertThat(holding.getUsdPurchaseAmount()).isZero();
        assertThat(holding.getKrwPurchaseAmount()).isEqualByComparingTo("24000.0000");
        assertThat(response.netAmount()).isEqualTo("19958");
        assertThat(response.account().cashBalanceAfter()).isEqualTo("29958");
        LedgerEntry ledger = ledgerEntryRepository
                .findFirstByOrderIdOrderByEntryIdAsc(response.orderId()).orElseThrow();
        assertThat(ledger.getEntryType()).isEqualTo(EntryType.SELL);
        assertThat(ledger.getAmount()).isEqualByComparingTo("19958");
        assertThat(ledger.getBalanceAfter()).isEqualByComparingTo(updated.getCashBalance());
        assertThat(ledger.getExchangeRate()).isEqualByComparingTo("1");
    }

    @Test
    void 업무_검증_실패는_REJECTED로_기록하고_잔액과_원장을_변경하지_않는다() {
        Fixture fixture = createKrFixture(new BigDecimal("10000"), new BigDecimal("10000"));
        PlaceOrderRequest request = request(fixture, "BUY", "2");

        assertThatThrownBy(() -> marketOrderService.place(fixture.userId(), request))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INSUFFICIENT_CASH);

        Account account = activeAccount(fixture.userId());
        TradeOrder rejected = tradeOrderRepository
                .findByAccountIdAndClientOrderId(account.getAccountId(), UUID.fromString(request.clientOrderId()))
                .orElseThrow();
        assertThat(rejected.getStatus()).isEqualTo(OrderStatus.REJECTED);
        assertThat(rejected.getRejectReason()).isEqualTo("INSUFFICIENT_CASH");
        assertThat(account.getCashBalance()).isEqualByComparingTo("10000");
        assertThat(ledgerEntryRepository
                .findFirstByOrderIdOrderByEntryIdAsc(rejected.getOrderId())).isEmpty();
        assertThat(rejected.getReferencePrice()).isEqualByComparingTo("10000");
        assertThat(rejected.getQuoteAt()).isNotNull();
        assertThat(rejected.getExchangeRate()).isEqualByComparingTo("1");
    }

    @Test
    void 같은_미국종목을_두번_매수하면_평균환율을_달러취득원가로_가중한다() {
        Fixture fixture = createUsFixture(new BigDecimal("10000000"), new BigDecimal("100"));
        when(exchangeRateProvider.currentUsdKrwRate())
                .thenReturn(new BigDecimal("1300"), new BigDecimal("1400"));

        marketOrderService.place(fixture.userId(), request(fixture, "BUY", "10"));
        QuoteSnapshot quote = quoteSnapshotRepository.findById(fixture.stockId()).orElseThrow();
        OffsetDateTime collectedAt = OffsetDateTime.now(ZoneOffset.UTC);
        quote.updatePrice(new BigDecimal("200"), quote.getCurrency(), collectedAt, collectedAt);
        quoteSnapshotRepository.save(quote);

        marketOrderService.place(fixture.userId(), request(fixture, "BUY", "10"));

        Account account = activeAccount(fixture.userId());
        Holding holding = holdingRepository
                .findByAccountIdAndStockId(account.getAccountId(), fixture.stockId()).orElseThrow();
        assertThat(holding.getQuantity()).isEqualByComparingTo("20");
        assertThat(holding.getAvgBuyPrice()).isEqualByComparingTo("150.0000");
        assertThat(holding.getAvgExchangeRate()).isEqualByComparingTo("1366.666667");
        assertThat(holding.getUsdPurchaseAmount()).isEqualByComparingTo("3000");
        assertThat(holding.getKrwPurchaseAmount()).isEqualByComparingTo("4100000");
        assertThat(account.getCashBalance()).isEqualByComparingTo("5899590");
        assertThat(tradeOrderRepository.countByAccountId(account.getAccountId())).isEqualTo(2);
        assertThat(ledgerEntryRepository.countByAccountId(account.getAccountId())).isEqualTo(2);
    }

    @Test
    void 미국종목을_열번_분할매수하면_반올림전_매수금액과_원장을_각각_정확히_보존한다() {
        Fixture fixture = createUsFixture(new BigDecimal("10000000"), new BigDecimal("10.01"));
        when(exchangeRateProvider.currentUsdKrwRate()).thenReturn(
                new BigDecimal("1300"), new BigDecimal("1301"), new BigDecimal("1302"),
                new BigDecimal("1303"), new BigDecimal("1304"), new BigDecimal("1305"),
                new BigDecimal("1306"), new BigDecimal("1307"), new BigDecimal("1308"),
                new BigDecimal("1309"));
        BigDecimal expectedUsdPurchaseAmount = BigDecimal.ZERO;
        BigDecimal expectedKrwPurchaseAmount = BigDecimal.ZERO;
        BigDecimal expectedGrossAmountKrw = BigDecimal.ZERO;
        BigDecimal expectedFee = BigDecimal.ZERO;

        for (int i = 1; i <= 10; i++) {
            BigDecimal price = new BigDecimal("10").add(new BigDecimal(i).movePointLeft(2));
            BigDecimal rate = new BigDecimal(1299 + i);
            QuoteSnapshot quote = quoteSnapshotRepository.findById(fixture.stockId()).orElseThrow();
            OffsetDateTime collectedAt = OffsetDateTime.now(ZoneOffset.UTC);
            quote.updatePrice(price, quote.getCurrency(), collectedAt, collectedAt);
            quoteSnapshotRepository.saveAndFlush(quote);

            marketOrderService.place(fixture.userId(), request(fixture, "BUY", "1"));

            BigDecimal unroundedGrossKrw = price.multiply(rate);
            BigDecimal grossKrw = unroundedGrossKrw.setScale(
                    0, java.math.RoundingMode.HALF_UP);
            expectedUsdPurchaseAmount = expectedUsdPurchaseAmount.add(price);
            expectedKrwPurchaseAmount = expectedKrwPurchaseAmount.add(unroundedGrossKrw);
            expectedGrossAmountKrw = expectedGrossAmountKrw.add(grossKrw);
            expectedFee = expectedFee.add(
                    grossKrw.multiply(new BigDecimal("0.0001"))
                            .setScale(0, java.math.RoundingMode.HALF_UP));
        }

        Holding holding = holdingRepository
                .findByAccountIdAndStockId(fixture.accountId(), fixture.stockId()).orElseThrow();
        BigDecimal ledgerDebit = jdbcTemplate.queryForObject("""
                SELECT -SUM(amount)
                FROM ledger_entry
                WHERE account_id = ? AND entry_type = 'BUY'
                """, BigDecimal.class, fixture.accountId());

        assertThat(holding.getQuantity()).isEqualByComparingTo("10");
        assertThat(holding.getUsdPurchaseAmount()).isEqualByComparingTo(expectedUsdPurchaseAmount);
        assertThat(holding.getKrwPurchaseAmount()).isEqualByComparingTo(expectedKrwPurchaseAmount);
        assertThat(holding.getAvgBuyPrice()).isEqualByComparingTo(
                expectedUsdPurchaseAmount.divide(new BigDecimal("10"), 4, java.math.RoundingMode.HALF_UP));
        assertThat(holding.getAvgExchangeRate()).isEqualByComparingTo(
                expectedKrwPurchaseAmount.divide(
                        expectedUsdPurchaseAmount, 6, java.math.RoundingMode.HALF_UP));
        assertThat(ledgerDebit)
                .isEqualByComparingTo(expectedGrossAmountKrw.add(expectedFee));
    }

    @Test
    void 운영_스키마의_보유수량_제약조건이_실제_DB에_적용된다() {
        Fixture fixture = createKrFixture(new BigDecimal("50000"), new BigDecimal("10000"));

        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO holding (
                    account_id, stock_id, quantity, locked_quantity,
                    avg_buy_price, avg_exchange_rate,
                    usd_purchase_amount, krw_purchase_amount
                ) VALUES (?, ?, 1, 2, 10000, 1, 0, 10000)
                """, fixture.accountId(), fixture.stockId()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void 운영_스키마는_수량이_0인_보유종목에_매수금액이_남는것을_거절한다() {
        Fixture fixture = createKrFixture(new BigDecimal("50000"), new BigDecimal("10000"));

        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO holding (
                    account_id, stock_id, quantity, locked_quantity,
                    avg_buy_price, avg_exchange_rate,
                    usd_purchase_amount, krw_purchase_amount
                ) VALUES (?, ?, 0, 0, 10000, 1, 0, 10000)
                """, fixture.accountId(), fixture.stockId()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void 동일한_clientOrderId_재시도는_한번만_체결한다() {
        Fixture fixture = createKrFixture(new BigDecimal("50000"), new BigDecimal("10000"));
        PlaceOrderRequest request = request(fixture, "BUY", "1");

        OrderResponse first = marketOrderService.place(fixture.userId(), request);
        clearInvocations(marketSessionProvider, exchangeRateProvider);
        OrderResponse retried = marketOrderService.place(fixture.userId(), request);

        Account account = activeAccount(fixture.userId());
        assertThat(retried).isEqualTo(first);
        assertThat(retried.exchangeRate()).isEqualTo("1");
        assertThat(tradeOrderRepository.countByAccountId(account.getAccountId())).isEqualTo(1);
        assertThat(ledgerEntryRepository.countByAccountId(account.getAccountId())).isEqualTo(1);
        assertThat(account.getCashBalance()).isEqualByComparingTo("39999");
        verifyNoInteractions(marketSessionProvider, exchangeRateProvider);
    }

    @Test
    void 멱등_재시도는_후속_주문과_무관하게_최초_체결_직후_잔액을_반환한다() {
        Fixture fixture = createKrFixture(new BigDecimal("50000"), new BigDecimal("10000"));
        PlaceOrderRequest firstRequest = request(fixture, "BUY", "1");

        OrderResponse first = marketOrderService.place(fixture.userId(), firstRequest);
        marketOrderService.place(fixture.userId(),
                new PlaceOrderRequest(fixture.accountId(), UUID.randomUUID().toString(), fixture.symbol(),
                        fixture.marketCountry().name(), "BUY", "1"));
        OrderResponse retried = marketOrderService.place(fixture.userId(), firstRequest);

        assertThat(activeAccount(fixture.userId()).getCashBalance()).isEqualByComparingTo("29998");
        assertThat(retried.orderId()).isEqualTo(first.orderId());
        assertThat(retried.account().cashBalanceAfter()).isEqualTo(first.account().cashBalanceAfter());
        assertThat(retried.account().cashBalanceAfter()).isEqualTo("39999");
    }

    @Test
    void 같은_주문의_상쇄원장이_추가되어도_최초_체결원장으로_멱등_응답한다() {
        Fixture fixture = createKrFixture(new BigDecimal("50000"), new BigDecimal("10000"));
        PlaceOrderRequest request = request(fixture, "BUY", "1");
        OrderResponse first = marketOrderService.place(fixture.userId(), request);

        ledgerEntryRepository.save(LedgerEntry.sell(
                fixture.accountId(), first.orderId(), BigDecimal.ONE,
                new BigDecimal("40000"), BigDecimal.ONE, "상쇄 테스트",
                OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(1)));

        OrderResponse retried = marketOrderService.place(fixture.userId(), request);

        assertThat(retried.orderId()).isEqualTo(first.orderId());
        assertThat(retried.account().cashBalanceAfter()).isEqualTo("39999");
        assertThat(ledgerEntryRepository
                .findFirstByOrderIdOrderByEntryIdAsc(first.orderId()).orElseThrow().getAmount())
                .isEqualByComparingTo("-10001");
    }

    @Test
    void 체결_주문에_원장이_없으면_멱등_응답을_만들지_않고_내부오류로_처리한다() {
        Fixture fixture = createKrFixture(new BigDecimal("50000"), new BigDecimal("10000"));
        UUID clientOrderId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        tradeOrderRepository.save(TradeOrder.filledMarketOrder(
                fixture.accountId(), fixture.stockId(), clientOrderId, OrderSide.BUY,
                BigDecimal.ONE, new BigDecimal("10000"), now, BigDecimal.ONE,
                new BigDecimal("10000"), BigDecimal.ONE, BigDecimal.ZERO,
                new BigDecimal("10001"), now));

        PlaceOrderRequest request = new PlaceOrderRequest(
                fixture.accountId(), clientOrderId.toString(), fixture.symbol(), fixture.marketCountry().name(), "BUY", "1");

        assertThatThrownBy(() -> marketOrderService.place(fixture.userId(), request))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.INTERNAL_ERROR));
        verifyNoInteractions(marketSessionProvider, exchangeRateProvider);
    }

    @Test
    void 국내_주문은_환율을_조회하지_않는다() {
        Fixture fixture = createKrFixture(new BigDecimal("50000"), new BigDecimal("10000"));

        marketOrderService.place(fixture.userId(), request(fixture, "BUY", "1"));

        verifyNoInteractions(exchangeRateProvider);
    }

    @Test
    void 종목과_시세의_통화가_다르면_주문을_저장하지_않고_같은_ID_재시도를_허용한다() {
        Fixture fixture = createKrFixture(new BigDecimal("50000"), new BigDecimal("10000"));
        OffsetDateTime collectedAt = OffsetDateTime.now(ZoneOffset.UTC);
        quoteSnapshotRepository.save(new QuoteSnapshot(
                fixture.stockId(), new BigDecimal("10000"), "USD", collectedAt, collectedAt));
        PlaceOrderRequest request = request(fixture, "BUY", "1");

        assertThatThrownBy(() -> marketOrderService.place(fixture.userId(), request))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.QUOTE_CURRENCY_MISMATCH);
                    assertThat(exception.getData())
                            .containsEntry("retryPolicy", "SAME_CLIENT_ORDER_ID");
                });

        assertThat(tradeOrderRepository.findByAccountIdAndClientOrderId(
                fixture.accountId(), UUID.fromString(request.clientOrderId()))).isEmpty();
    }

    @Test
    void 견적의_외부_시장정보는_읽기_트랜잭션_종료_후_조회한다() {
        Fixture fixture = createUsFixture(new BigDecimal("500000"), new BigDecimal("100"));
        when(exchangeRateProvider.currentUsdKrwRate()).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return new BigDecimal("1383.60");
        });
        when(marketSessionProvider.isOpen(any(), any())).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return true;
        });

        OrderQuoteResponse response = orderQuoteService.getQuote(
                fixture.userId(), fixture.symbol(), fixture.marketCountry().name(), "BUY", "1");

        assertThat(response.executable()).isTrue();
        assertThat(response.exchangeRate()).isEqualTo("1383.6");
    }

    @Test
    void 주문_유스케이스는_외부_트랜잭션_안에서_실행할_수_없다() {
        TransactionTemplate outerTransaction = new TransactionTemplate(transactionManager);
        PlaceOrderRequest request = request("005930", MarketCountry.KR, "BUY", "1");

        assertThatThrownBy(() -> outerTransaction.executeWithoutResult(
                status -> marketOrderService.place(1L, request)))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void 미래_시각의_시세는_REJECTED로_기록한다() {
        Fixture fixture = createKrFixture(new BigDecimal("50000"), new BigDecimal("10000"));
        QuoteSnapshot quote = quoteSnapshotRepository.findById(fixture.stockId()).orElseThrow();
        OffsetDateTime quoteAt = OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(1);
        quote.updatePrice(new BigDecimal("10000"), quote.getCurrency(), quoteAt, quoteAt);
        quoteSnapshotRepository.save(quote);

        assertRejected(fixture, request(fixture, "BUY", "1"), ErrorCode.FUTURE_QUOTE);
    }

    @Test
    void 장종료는_REJECTED로_기록한다() {
        Fixture marketClosed = createKrFixture(new BigDecimal("50000"), new BigDecimal("10000"));
        when(marketSessionProvider.currentSession(any(), any()))
                .thenReturn(MarketSessionStatus.closed());
        assertRejected(marketClosed, request(marketClosed, "BUY", "1"), ErrorCode.MARKET_CLOSED);
    }

    @Test
    void 정적_거절은_외부조회와_주문저장_없이_같은_clientOrderId로_재시도할_수_있다() {
        Fixture suspended = createKrFixture(new BigDecimal("50000"), new BigDecimal("10000"));
        Stock suspendedStock = stockRepository.findById(suspended.stockId()).orElseThrow();
        suspendedStock.updateFlags(true, false, false);
        stockRepository.save(suspendedStock);
        PlaceOrderRequest request = request(suspended, "BUY", "1");
        clearInvocations(marketSessionProvider, exchangeRateProvider);

        assertThatThrownBy(() -> marketOrderService.place(suspended.userId(), request))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.STOCK_SUSPENDED));
        assertThat(tradeOrderRepository.findByAccountIdAndClientOrderId(
                suspended.accountId(), UUID.fromString(request.clientOrderId()))).isEmpty();
        verifyNoInteractions(marketSessionProvider, exchangeRateProvider);

        suspendedStock.updateFlags(false, false, false);
        stockRepository.save(suspendedStock);
        OrderResponse retried = marketOrderService.place(suspended.userId(), request);

        assertThat(retried.status()).isEqualTo("FILLED");
    }

    @Test
    void 정리매매와_유니버스제외도_외부조회_전에_거절한다() {
        Fixture liquidation = createKrFixture(new BigDecimal("50000"), new BigDecimal("10000"));
        Stock liquidationStock = stockRepository.findById(liquidation.stockId()).orElseThrow();
        liquidationStock.updateFlags(false, true, false);
        stockRepository.save(liquidationStock);
        PlaceOrderRequest liquidationRequest = request(liquidation, "BUY", "1");
        assertThatThrownBy(() -> marketOrderService.place(liquidation.userId(), liquidationRequest))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.STOCK_LIQUIDATION));

        Fixture outsideUniverse = createKrFixture(new BigDecimal("50000"), new BigDecimal("10000"));
        Stock outsideStock = stockRepository.findById(outsideUniverse.stockId()).orElseThrow();
        outsideStock.clearRanking();
        stockRepository.save(outsideStock);
        PlaceOrderRequest outsideRequest = request(outsideUniverse, "BUY", "1");
        assertThatThrownBy(() -> marketOrderService.place(outsideUniverse.userId(), outsideRequest))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.NOT_IN_UNIVERSE));

        assertThat(tradeOrderRepository.findByAccountIdAndClientOrderId(
                liquidation.accountId(), UUID.fromString(liquidationRequest.clientOrderId()))).isEmpty();
        assertThat(tradeOrderRepository.findByAccountIdAndClientOrderId(
                outsideUniverse.accountId(), UUID.fromString(outsideRequest.clientOrderId()))).isEmpty();
    }

    @Test
    void 오래된_시세와_보유수량_부족은_REJECTED로_기록한다() {
        Fixture stale = createKrFixture(new BigDecimal("50000"), new BigDecimal("10000"));
        QuoteSnapshot staleQuote = quoteSnapshotRepository.findById(stale.stockId()).orElseThrow();
        OffsetDateTime quoteAt = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1);
        staleQuote.updatePrice(new BigDecimal("10000"), staleQuote.getCurrency(), quoteAt, quoteAt);
        quoteSnapshotRepository.save(staleQuote);
        assertRejected(stale, request(stale, "BUY", "1"), ErrorCode.STALE_QUOTE);

        Fixture insufficient = createKrFixture(new BigDecimal("50000"), new BigDecimal("10000"));
        Account account = activeAccount(insufficient.userId());
        holdingRepository.save(Holding.firstBuy(
                account.getAccountId(), insufficient.stockId(), BigDecimal.ONE,
                BigDecimal.ZERO, new BigDecimal("10000"),
                OffsetDateTime.now(ZoneOffset.UTC)));
        assertRejected(insufficient, request(insufficient, "SELL", "2"),
                ErrorCode.INSUFFICIENT_QUANTITY);
    }

    @Test
    void 미국_매도_정산액이_정확히_0이면_REJECTED로_기록한다() {
        Fixture fixture = createUsFixture(new BigDecimal("50000"), new BigDecimal("0.01"));
        Account account = activeAccount(fixture.userId());
        holdingRepository.save(Holding.firstBuy(
                account.getAccountId(), fixture.stockId(), BigDecimal.ONE,
                new BigDecimal("0.01"), new BigDecimal("13.836"),
                OffsetDateTime.now(ZoneOffset.UTC)));

        assertRejected(fixture, request(fixture, "SELL", "1"),
                ErrorCode.INVALID_SETTLEMENT_AMOUNT);
    }

    @Test
    void clientOrderId는_계좌마다_독립적이다() {
        Fixture first = createKrFixture(new BigDecimal("50000"), new BigDecimal("10000"));
        Fixture second = createKrFixture(new BigDecimal("50000"), new BigDecimal("10000"));
        String clientOrderId = UUID.randomUUID().toString();

        marketOrderService.place(first.userId(),
                new PlaceOrderRequest(first.accountId(), clientOrderId, first.symbol(),
                        first.marketCountry().name(), "BUY", "1"));
        marketOrderService.place(second.userId(),
                new PlaceOrderRequest(second.accountId(), clientOrderId, second.symbol(),
                        second.marketCountry().name(), "BUY", "1"));

        TradeOrder firstOrder = tradeOrderRepository.findByAccountIdAndClientOrderId(
                first.accountId(), UUID.fromString(clientOrderId)).orElseThrow();
        TradeOrder secondOrder = tradeOrderRepository.findByAccountIdAndClientOrderId(
                second.accountId(), UUID.fromString(clientOrderId)).orElseThrow();
        assertThat(firstOrder.getOrderId()).isNotEqualTo(secondOrder.getOrderId());
    }

    @Test
    void 원장_누적액은_예수금과_일치하고_주문_정산액과_부호만_다르다() {
        Fixture fixture = createKrFixture(new BigDecimal("50000"), new BigDecimal("10000"));
        ledgerEntryRepository.save(LedgerEntry.initialDeposit(
                fixture.accountId(), new BigDecimal("50000"), "모의투자금 지급",
                OffsetDateTime.now(ZoneOffset.UTC)));

        OrderResponse response = marketOrderService.place(
                fixture.userId(), request(fixture, "BUY", "2"));

        Account account = activeAccount(fixture.userId());
        TradeOrder order = tradeOrderRepository.findById(response.orderId()).orElseThrow();
        LedgerEntry orderLedger = ledgerEntryRepository
                .findFirstByOrderIdOrderByEntryIdAsc(order.getOrderId()).orElseThrow();
        BigDecimal ledgerSum = jdbcTemplate.queryForObject(
                "SELECT SUM(amount) FROM ledger_entry WHERE account_id = ?",
                BigDecimal.class, fixture.accountId());
        assertThat(ledgerSum).isEqualByComparingTo(account.getCashBalance());
        assertThat(orderLedger.getAmount().abs()).isEqualByComparingTo(order.getNetAmount());
        assertThat(orderLedger.getBalanceAfter()).isEqualByComparingTo(account.getCashBalance());
    }

    @Test
    void 같은_clientOrderId에_다른_주문내용을_보내면_충돌로_거절한다() {
        Fixture fixture = createKrFixture(new BigDecimal("50000"), new BigDecimal("10000"));
        PlaceOrderRequest first = request(fixture, "BUY", "1");
        PlaceOrderRequest changed = new PlaceOrderRequest(
                fixture.accountId(), first.clientOrderId(), fixture.symbol(), fixture.marketCountry().name(), "BUY", "2");

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
    void 같은_clientOrderId에_다른_심볼을_보내면_외부조회없이_충돌로_거절한다() {
        Fixture fixture = createKrFixture(new BigDecimal("50000"), new BigDecimal("10000"));
        PlaceOrderRequest first = request(fixture, "BUY", "1");
        PlaceOrderRequest changed = new PlaceOrderRequest(
                fixture.accountId(), first.clientOrderId(), "OTHER", fixture.marketCountry().name(), "BUY", "1");
        marketOrderService.place(fixture.userId(), first);
        clearInvocations(marketSessionProvider, exchangeRateProvider);

        assertThatThrownBy(() -> marketOrderService.place(fixture.userId(), changed))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.DUPLICATE_ORDER);

        verifyNoInteractions(marketSessionProvider, exchangeRateProvider);
        Account account = activeAccount(fixture.userId());
        assertThat(tradeOrderRepository.countByAccountId(account.getAccountId())).isEqualTo(1);
        assertThat(ledgerEntryRepository.countByAccountId(account.getAccountId())).isEqualTo(1);
    }

    @Test
    void 동시_매수는_계좌_락으로_이중_차감을_방지한다() throws Exception {
        Fixture fixture = createKrFixture(new BigDecimal("15000"), new BigDecimal("10000"));
        PlaceOrderRequest first = request(fixture, "BUY", "1");
        PlaceOrderRequest second = request(fixture, "BUY", "1");
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

    @Test
    void 락_대기중_같은_clientOrderId가_체결되면_만료검사보다_저장결과를_먼저_반환한다() throws Exception {
        Fixture fixture = createKrFixture(new BigDecimal("50000"), new BigDecimal("10000"));
        PlaceOrderRequest request = request(fixture, "BUY", "1");
        UUID clientOrderId = UUID.fromString(request.clientOrderId());
        CountDownLatch accountLocked = new CountDownLatch(1);
        CountDownLatch contextPrepared = new CountDownLatch(1);
        when(marketSessionProvider.currentSession(any(), any())).thenAnswer(invocation -> {
            contextPrepared.countDown();
            return new MarketSessionStatus(true, Instant.MAX);
        });

        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<Long> firstOrderId = executor.submit(() -> new TransactionTemplate(transactionManager)
                    .execute(status -> {
                        Account account = accountRepository.findByUserIdAndStatusForUpdate(
                                fixture.userId(), AccountStatus.ACTIVE).orElseThrow();
                        accountLocked.countDown();
                        await(contextPrepared);
                        pauseForContextExpiry();

                        OffsetDateTime orderedAt = OffsetDateTime.now(ZoneOffset.UTC);
                        TradeOrder order = tradeOrderRepository.save(TradeOrder.filledMarketOrder(
                                fixture.accountId(), fixture.stockId(), clientOrderId, OrderSide.BUY,
                                BigDecimal.ONE, new BigDecimal("10000"), orderedAt, BigDecimal.ONE,
                                new BigDecimal("10000"), BigDecimal.ONE, BigDecimal.ZERO,
                                new BigDecimal("10001"), orderedAt));
                        account.debitMarketBuy(new BigDecimal("10001"));
                        holdingRepository.save(Holding.firstBuy(
                                fixture.accountId(), fixture.stockId(), BigDecimal.ONE,
                                BigDecimal.ZERO, new BigDecimal("10000"), orderedAt));
                        ledgerEntryRepository.save(LedgerEntry.buy(
                                fixture.accountId(), order.getOrderId(), new BigDecimal("10001"),
                                account.getCashBalance(), BigDecimal.ONE, "동시 멱등 테스트", orderedAt));
                        return order.getOrderId();
                    }));

            assertThat(accountLocked.await(5, TimeUnit.SECONDS)).isTrue();
            Future<OrderResponse> retry = executor.submit(
                    () -> marketOrderService.place(fixture.userId(), request));

            assertThat(retry.get().orderId()).isEqualTo(firstOrderId.get());
        }

        assertThat(tradeOrderRepository.countByAccountId(fixture.accountId())).isEqualTo(1);
        assertThat(ledgerEntryRepository.countByAccountId(fixture.accountId())).isEqualTo(1);
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("시장 컨텍스트 준비를 기다리지 못했습니다");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private void pauseForContextExpiry() {
        try {
            Thread.sleep(1_200);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
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
        return createFixture(
                initialCash, price, marketCountry, market, currency,
                UUID.randomUUID().toString().substring(0, 6).toUpperCase());
    }

    private Fixture createFixture(
            BigDecimal initialCash,
            BigDecimal price,
            MarketCountry marketCountry,
            String market,
            String currency,
            String symbol
    ) {
        String suffix = UUID.randomUUID().toString();
        User user = userRepository.save(User.create(
                suffix + "@example.com", "password-hash", "user-" + suffix.substring(0, 8)));
        Account account = accountRepository.save(Account.open(
                user.getUserId(), 1, initialCash, OffsetDateTime.now(ZoneOffset.UTC)));
        Stock stock = Stock.create(symbol, marketCountry, market, "테스트 종목", null, currency, "STOCK", true);
        stock.applyRanking(1, new BigDecimal("1000000"));
        stockRepository.save(stock);
        OffsetDateTime collectedAt = OffsetDateTime.now(ZoneOffset.UTC);
        quoteSnapshotRepository.save(new QuoteSnapshot(
                stock.getStockId(), price, currency, collectedAt, collectedAt));
        return new Fixture(
                user.getUserId(), account.getAccountId(), stock.getStockId(), symbol, marketCountry);
    }

    @Test
    void 원장_저장_실패는_체결_주문_잔액_보유수량을_모두_롤백한다() {
        Fixture fixture = createKrFixture(new BigDecimal("50000"), new BigDecimal("10000"));
        jdbcTemplate.execute("ALTER TABLE ledger_entry ADD CONSTRAINT test_reject_execution_ledger CHECK (account_id <> "
                + fixture.accountId() + ")");
        try {
            assertThatThrownBy(() -> marketOrderService.place(fixture.userId(), request(fixture, "BUY", "2")))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            assertThat(accountRepository.findById(fixture.accountId()).orElseThrow().getCashBalance()).isEqualByComparingTo("50000");
            assertThat(tradeOrderRepository.countByAccountId(fixture.accountId())).isZero();
            assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM trade_execution e JOIN trade_order o ON o.order_id = e.order_id WHERE o.account_id = ?",
                    Long.class, fixture.accountId())).isZero();
            assertThat(holdingRepository.findByAccountIdAndStockId(fixture.accountId(), fixture.stockId())).isEmpty();
            assertThat(ledgerEntryRepository.countByAccountId(fixture.accountId())).isZero();
        } finally {
            jdbcTemplate.execute("ALTER TABLE ledger_entry DROP CONSTRAINT test_reject_execution_ledger");
        }
    }

    @Test
    void 같은_체결과_정상원장을_두번_기록할_수_없다() {
        Fixture fixture = createKrFixture(new BigDecimal("50000"), new BigDecimal("10000"));
        PlaceOrderRequest request = request(fixture, "BUY", "2");
        OrderResponse first = marketOrderService.place(fixture.userId(), request);
        TradeOrder order = tradeOrderRepository.findById(first.orderId()).orElseThrow();
        var amount = amountCalculator.calculate(MarketCountry.KR, OrderSide.BUY, new BigDecimal("10000"), new BigDecimal("2"), BigDecimal.ONE);
        assertThatThrownBy(() -> tradeExecutionRepository.save(com.baedang.trading.entity.TradeExecution.market(order, amount)))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        var execution = tradeExecutionRepository.findByOrderIdAndExecutionKey(order.getOrderId(), order.getClientOrderId()).orElseThrow();
        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                ledgerService.recordBuy(order, execution, new BigDecimal("29998"), stockRepository.findById(fixture.stockId()).orElseThrow())))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(tradeExecutionRepository.countByOrderId(order.getOrderId())).isEqualTo(1);
        assertThat(marketOrderService.place(fixture.userId(), request)).isEqualTo(first);
        assertThat(ledgerEntryRepository.countByAccountId(fixture.accountId())).isEqualTo(1);
    }

    @Test
    void 원장의_계좌와_주문과_체결_연결을_DB에서도_검증한다() {
        Fixture owner = createKrFixture(new BigDecimal("50000"), new BigDecimal("10000"));
        Fixture other = createKrFixture(new BigDecimal("50000"), new BigDecimal("10000"));
        OffsetDateTime at = Instant.parse("2026-09-03T01:00:00Z").atOffset(ZoneOffset.UTC);
        // 원장이 아직 없는 체결을 만들어 UNIQUE 실패와 FK 실패를 구분합니다.
        TradeOrder order = tradeOrderRepository.saveAndFlush(TradeOrder.filledMarketOrder(
                owner.accountId(), owner.stockId(), UUID.randomUUID(), OrderSide.BUY,
                BigDecimal.ONE, new BigDecimal("10000"), at, BigDecimal.ONE,
                new BigDecimal("10000"), BigDecimal.ONE, BigDecimal.ZERO, new BigDecimal("10001"), at));
        var amounts = amountCalculator.calculate(MarketCountry.KR, OrderSide.BUY,
                new BigDecimal("10000"), BigDecimal.ONE, BigDecimal.ONE);
        var execution = tradeExecutionRepository.save(com.baedang.trading.entity.TradeExecution.market(order, amounts));
        OrderResponse otherOrder = marketOrderService.place(other.userId(), request(other, "BUY", "1"));
        String insert = """
                INSERT INTO ledger_entry (account_id, order_id, execution_id, entry_type, amount, balance_after,
                                          exchange_rate, occurred_at)
                VALUES (?, ?, ?, 'BUY', -10001, 39999, 1, ?)
                """;
        assertThatThrownBy(() -> jdbcTemplate.update(insert, other.accountId(), order.getOrderId(), execution.getExecutionId(), at))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class)
                .hasMessageContaining("fk_ledger_order_account");
        assertThatThrownBy(() -> jdbcTemplate.update(insert, other.accountId(), otherOrder.orderId(), execution.getExecutionId(), at))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class)
                .hasMessageContaining("fk_ledger_execution_order");
        assertThat(jdbcTemplate.update(insert, owner.accountId(), order.getOrderId(), execution.getExecutionId(), at)).isEqualTo(1);
        assertThat(ledgerEntryRepository.countByAccountId(owner.accountId())).isEqualTo(1);
    }

    @Test
    void 기존_체결행이_없는_시장가도_최초원장의_잔액으로_재생한다() {
        Fixture fixture = createKrFixture(new BigDecimal("50000"), new BigDecimal("10000"));
        PlaceOrderRequest request = request(fixture, "BUY", "2");
        OffsetDateTime at = Instant.parse("2026-09-01T00:00:00Z").atOffset(ZoneOffset.UTC);
        TradeOrder legacy = tradeOrderRepository.saveAndFlush(TradeOrder.filledMarketOrder(
                fixture.accountId(), fixture.stockId(), UUID.fromString(request.clientOrderId()), OrderSide.BUY,
                new BigDecimal("2"), new BigDecimal("10000"), at, BigDecimal.ONE,
                new BigDecimal("20000"), new BigDecimal("2"), BigDecimal.ZERO, new BigDecimal("20002"), at));
        ledgerEntryRepository.save(LedgerEntry.buy(fixture.accountId(), legacy.getOrderId(),
                new BigDecimal("20002"), new BigDecimal("29998"), BigDecimal.ONE, "legacy", at));
        clearInvocations(marketSessionProvider, exchangeRateProvider);
        OrderResponse response = marketOrderService.place(fixture.userId(), request);
        assertThat(response.account().cashBalanceAfter()).isEqualTo("29998");
        assertThat(tradeExecutionRepository.countByOrderId(legacy.getOrderId())).isZero();
        verifyNoInteractions(marketSessionProvider, exchangeRateProvider);
    }

    @ParameterizedTest
    @ValueSource(strings = {"1", "1383.60", "1383.123456"})
    void 소수점_6자리_이하_환율과_반올림전_거래대금을_DB에_보존한다(String rateText) {
        BigDecimal rate = new BigDecimal(rateText);
        when(exchangeRateProvider.currentUsdKrwRate()).thenReturn(rate);
        Fixture fixture = createFixture(new BigDecimal("500000"), new BigDecimal("88.33"), MarketCountry.US, "NASDAQ", "USD");
        PlaceOrderRequest request = request(fixture, "BUY", "1");
        OrderResponse first = marketOrderService.place(fixture.userId(), request);
        var execution = tradeExecutionRepository.findByOrderIdAndExecutionKey(first.orderId(), UUID.fromString(request.clientOrderId())).orElseThrow();
        assertThat(tradeOrderRepository.findById(first.orderId()).orElseThrow().getExchangeRate()).isEqualByComparingTo(rate);
        assertThat(execution.getExchangeRate()).isEqualByComparingTo(rate);
        assertThat(execution.grossAmountUsd(MarketCountry.US)).isEqualByComparingTo("88.33");
        assertThat(execution.unroundedGrossAmountKrw()).isEqualByComparingTo(new BigDecimal("88.33").multiply(rate));
        assertThat(ledgerEntryRepository.findFirstByOrderIdOrderByEntryIdAsc(first.orderId()).orElseThrow().getExchangeRate())
                .isEqualByComparingTo(rate);
        assertThat(marketOrderService.place(fixture.userId(), request)).isEqualTo(first);
    }

    @Test
    void 매수매도_원장도_상위_트랜잭션이_필수다() {
        assertThatThrownBy(() -> ledgerService.recordBuy(null, null, null, null)).isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> ledgerService.recordSell(null, null, null, null)).isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void 지정가_모델의_부분체결_원장_연결과_취소를_DB에_보존한다() {
        Fixture fixture = createKrFixture(new BigDecimal("1000"), new BigDecimal("100"));
        OffsetDateTime at = Instant.parse("2026-09-03T01:00:00Z").atOffset(ZoneOffset.UTC);
        var rate = new com.baedang.trading.model.ExecutionRateEvidence(BigDecimal.ONE, at, at, at.plusMinutes(1));
        Long bookLevelId = 1L;
        // 모델/스키마 검증용 fixture. 주문·잔액·보유수량·원장을 함께 구성합니다.
        Long orderId = new TransactionTemplate(transactionManager).execute(status -> {
            accountRepository.findByAccountIdAndUserIdForUpdate(fixture.accountId(), fixture.userId()).orElseThrow();
            TradeOrder order = tradeOrderRepository.save(TradeOrder.pendingLimitOrder(fixture.accountId(), fixture.stockId(),
                    UUID.randomUUID(), OrderSide.BUY, new BigDecimal("3"), new BigDecimal("100"), new BigDecimal("300"),
                    at, at.plusHours(6)));
            var amounts = new com.baedang.trading.model.ExecutionAmounts(BigDecimal.ZERO, new BigDecimal("90"),
                    BigDecimal.ZERO, new BigDecimal("90"), BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("90"));
            var execution = tradeExecutionRepository.save(com.baedang.trading.entity.TradeExecution.limit(order, MarketCountry.KR,
                    UUID.randomUUID(), 1, BigDecimal.ONE, new BigDecimal("90"), rate, amounts, at, at.plusSeconds(1), bookLevelId));
            order.applyExecution(execution, new BigDecimal("200"));
            jdbcTemplate.update("UPDATE account SET cash_balance = 910, locked_cash = 200 WHERE account_id = ?", fixture.accountId());
            holdingRepository.save(Holding.firstBuy(fixture.accountId(), fixture.stockId(), BigDecimal.ONE,
                    BigDecimal.ZERO, new BigDecimal("90"), at.plusSeconds(1)));
            ledgerService.recordBuy(order, execution, new BigDecimal("910"), stockRepository.findById(fixture.stockId()).orElseThrow());
            return order.getOrderId();
        });
        TradeOrder partial = tradeOrderRepository.findById(orderId).orElseThrow();
        assertThat(partial.getStatus()).isEqualTo(OrderStatus.PARTIALLY_FILLED);
        assertThat(partial.getExecutionCount()).isEqualTo(1);
        assertThat(partial.getLastExecutedAt()).isEqualTo(at.plusSeconds(1));
        assertThatThrownBy(() -> jdbcTemplate.update("UPDATE trade_order SET reserved_cash = 0 WHERE order_id = ?", orderId))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class)
                .hasMessageContaining("ck_order_limit_terms");
        assertThat(tradeExecutionRepository.findByOrderIdOrderBySequenceNoAsc(orderId,
                org.springframework.data.domain.PageRequest.of(0, 10)).getContent().getFirst().getBookLevelId()).isEqualTo(bookLevelId);
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            accountRepository.findByAccountIdAndUserIdForUpdate(fixture.accountId(), fixture.userId()).orElseThrow();
            TradeOrder order = tradeOrderRepository.findById(orderId).orElseThrow();
            var release = order.cancel(at.plusSeconds(2));
            assertThat(release.releasedCash()).isEqualByComparingTo("200");
            jdbcTemplate.update("UPDATE account SET locked_cash = locked_cash - ? WHERE account_id = ?",
                    release.releasedCash(), fixture.accountId());
        });
        TradeOrder canceled = tradeOrderRepository.findById(orderId).orElseThrow();
        assertThat(canceled.getStatus()).isEqualTo(OrderStatus.CANCELED);
        assertThat(canceled.getFilledQuantity()).isEqualByComparingTo("1");
        assertThat(canceled.activeRemainingQuantity()).isZero();
        assertThat(canceled.getReservedCash()).isZero();
        assertThat(ledgerEntryRepository.countByAccountId(fixture.accountId())).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"CANCELED", "EXPIRED"})
    void 전부_미체결인_주문은_동결과_해제만_수행하고_원장을_남기지_않는다(String target) {
        Fixture fixture = createKrFixture(new BigDecimal("1000"), new BigDecimal("100"));
        OffsetDateTime at = Instant.parse("2026-09-03T01:00:00Z").atOffset(ZoneOffset.UTC);
        var rate = new com.baedang.trading.model.ExecutionRateEvidence(BigDecimal.ONE, at, at, at.plusMinutes(1));
        Long orderId = new TransactionTemplate(transactionManager).execute(status -> {
            accountRepository.findByAccountIdAndUserIdForUpdate(fixture.accountId(), fixture.userId()).orElseThrow();
            TradeOrder order = tradeOrderRepository.save(TradeOrder.pendingLimitOrder(fixture.accountId(), fixture.stockId(),
                    UUID.randomUUID(), OrderSide.BUY, new BigDecimal("3"), new BigDecimal("100"),
                    new BigDecimal("300"), at, at.plusHours(6)));
            jdbcTemplate.update("UPDATE account SET locked_cash = 300 WHERE account_id = ?", fixture.accountId());
            return order.getOrderId();
        });
        assertThat(tradeOrderRepository.findById(orderId).orElseThrow().getReservedCash()).isEqualByComparingTo("300");
        assertThat(accountRepository.findById(fixture.accountId()).orElseThrow().getCashBalance()).isEqualByComparingTo("1000");
        assertThat(ledgerEntryRepository.countByAccountId(fixture.accountId())).isZero();

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            accountRepository.findByAccountIdAndUserIdForUpdate(fixture.accountId(), fixture.userId()).orElseThrow();
            TradeOrder order = tradeOrderRepository.findById(orderId).orElseThrow();
            var release = target.equals("CANCELED") ? order.cancel(at.plusSeconds(1)) : order.expire(at.plusHours(6));
            assertThat(release.releasedCash()).isEqualByComparingTo("300");
            jdbcTemplate.update("UPDATE account SET locked_cash = locked_cash - ? WHERE account_id = ?",
                    release.releasedCash(), fixture.accountId());
        });
        TradeOrder closed = tradeOrderRepository.findById(orderId).orElseThrow();
        Account account = accountRepository.findById(fixture.accountId()).orElseThrow();
        assertThat(closed.getStatus()).isEqualTo(OrderStatus.valueOf(target));
        assertThat(closed.getReservedCash()).isZero();
        assertThat(account.getLockedCash()).isZero();
        assertThat(account.getCashBalance()).isEqualByComparingTo("1000");
        assertThat(tradeExecutionRepository.countByOrderId(orderId)).isZero();
        assertThat(ledgerEntryRepository.countByAccountId(fixture.accountId())).isZero();
    }

    private Account activeAccount(Long userId) {
        return accountRepository.findByUserIdAndStatus(userId, AccountStatus.ACTIVE).orElseThrow();
    }

    private TradeOrder assertRejected(Fixture fixture, PlaceOrderRequest request, ErrorCode expected) {
        assertThatThrownBy(() -> marketOrderService.place(fixture.userId(), request))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(expected);
        TradeOrder rejected = tradeOrderRepository
                .findByAccountIdAndClientOrderId(
                        fixture.accountId(), UUID.fromString(request.clientOrderId()))
                .orElseThrow();
        assertThat(rejected.getStatus()).isEqualTo(OrderStatus.REJECTED);
        assertThat(rejected.getRejectReason()).isEqualTo(expected.name());
        assertThat(ledgerEntryRepository
                .findFirstByOrderIdOrderByEntryIdAsc(rejected.getOrderId())).isEmpty();
        return rejected;
    }

    private PlaceOrderRequest request(Fixture fixture, String side, String quantity) {
        return new PlaceOrderRequest(
                fixture.accountId(), UUID.randomUUID().toString(), fixture.symbol(),
                fixture.marketCountry().name(), side, quantity);
    }

    private PlaceOrderRequest request(
            String symbol,
            MarketCountry marketCountry,
            String side,
            String quantity
    ) {
        return new PlaceOrderRequest(
                1L, UUID.randomUUID().toString(), symbol, marketCountry.name(), side, quantity);
    }

    private record Fixture(
            Long userId,
            Long accountId,
            Long stockId,
            String symbol,
            MarketCountry marketCountry
    ) {
    }
}
