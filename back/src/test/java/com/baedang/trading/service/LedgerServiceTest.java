package com.baedang.trading.service;

import com.baedang.trading.entity.EntryType;
import com.baedang.trading.entity.LedgerEntry;
import com.baedang.trading.entity.TradeExecution;
import com.baedang.trading.entity.TradeOrder;
import com.baedang.trading.model.OrderAmount;
import com.baedang.trading.entity.OrderSide;
import com.baedang.stock.entity.Stock;
import com.baedang.stock.entity.MarketCountry;
import com.baedang.trading.repository.LedgerEntryRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class LedgerServiceTest {

    private static final OffsetDateTime OPENED_AT = OffsetDateTime.parse("2026-09-02T00:00:00Z");
    private final LedgerEntryRepository repository = mock(LedgerEntryRepository.class);
    private final LedgerService service = new LedgerService(repository);

    @ParameterizedTest
    @CsvSource({"1,모의투자금 지급", "2,모의투자금 지급 · 2회차", "12,모의투자금 지급 · 12회차"})
    void 초기_지급액과_시각을_보존하고_회차별_메모를_기록한다(int roundNo, String memo) {
        BigDecimal amount = new BigDecimal("12345678.1234");
        service.recordInitialDeposit(7L, amount, roundNo, OPENED_AT);

        ArgumentCaptor<LedgerEntry> captor = ArgumentCaptor.forClass(LedgerEntry.class);
        verify(repository).save(captor.capture());
        LedgerEntry entry = captor.getValue();
        assertThat(entry.getAccountId()).isEqualTo(7L);
        assertThat(entry.getOrderId()).isNull();
        assertThat(entry.getEntryType()).isEqualTo(EntryType.INITIAL_DEPOSIT);
        assertThat(entry.getAmount()).isEqualTo(amount);
        assertThat(entry.getBalanceAfter()).isEqualTo(amount);
        assertThat(entry.getExchangeRate()).isEqualByComparingTo(BigDecimal.ONE);
        assertThat(entry.getOccurredAt()).isEqualTo(OPENED_AT);
        assertThat(entry.getMemo()).isEqualTo(memo);
    }

    @Test
    void 잘못된_내부_호출은_저장하기_전에_거절한다() {
        assertInvalid(null, BigDecimal.ONE, 1, OPENED_AT);
        assertInvalid(0L, BigDecimal.ONE, 1, OPENED_AT);
        assertInvalid(-1L, BigDecimal.ONE, 1, OPENED_AT);
        assertInvalid(1L, null, 1, OPENED_AT);
        assertInvalid(1L, BigDecimal.ZERO, 1, OPENED_AT);
        assertInvalid(1L, BigDecimal.ONE.negate(), 1, OPENED_AT);
        assertInvalid(1L, BigDecimal.ONE, 0, OPENED_AT);
        assertInvalid(1L, BigDecimal.ONE, 1, null);
        verifyNoInteractions(repository);
    }

    @ParameterizedTest
    @EnumSource(OrderSide.class)
    void 매수매도_원장은_체결값과_잔액을_그대로_쓰고_부호만_적용한다(OrderSide side) {
        TradeOrder order = order(side);
        TradeExecution execution = execution(order);
        Stock stock = stock();
        if (side == OrderSide.BUY) service.recordBuy(order, execution, new BigDecimal("999"), stock);
        else service.recordSell(order, execution, new BigDecimal("999"), stock);
        ArgumentCaptor<LedgerEntry> captor = ArgumentCaptor.forClass(LedgerEntry.class);
        verify(repository).save(captor.capture());
        LedgerEntry entry = captor.getValue();
        assertThat(entry.getExecutionId()).isEqualTo(5L);
        assertThat(entry.getOrderId()).isEqualTo(4L);
        assertThat(entry.getAccountId()).isEqualTo(7L);
        assertThat(entry.getAmount()).isEqualByComparingTo(side == OrderSide.BUY ? "-300" : "300");
        assertThat(entry.getEntryType()).isEqualTo(side == OrderSide.BUY ? EntryType.BUY : EntryType.SELL);
        assertThat(entry.getBalanceAfter()).isEqualByComparingTo("999");
        assertThat(entry.getExchangeRate()).isEqualByComparingTo("1");
        assertThat(entry.getOccurredAt()).isEqualTo(OPENED_AT);
        assertThat(entry.getMemo()).isEqualTo("테스트 3주 @ 100 (수수료·세금 포함)");
    }

    @Test
    void 저장전_체결이나_다른방향과_음수잔액은_원장으로_기록하지_않는다() {
        TradeOrder order = order(OrderSide.BUY);
        TradeExecution execution = execution(order);
        assertThatThrownBy(() -> service.recordSell(order, execution, BigDecimal.ONE, stock())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.recordBuy(order, execution, BigDecimal.ONE.negate(), stock())).isInstanceOf(IllegalArgumentException.class);
        ReflectionTestUtils.setField(execution, "executionId", null);
        assertThatThrownBy(() -> service.recordBuy(order, execution, BigDecimal.ONE, stock())).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(repository);
    }

    @Test
    void 다른_주문이나_종목으로는_체결_원장을_만들_수_없다() {
        TradeOrder order = order(OrderSide.BUY);
        TradeExecution execution = execution(order);
        TradeOrder other = order(OrderSide.BUY);
        ReflectionTestUtils.setField(other, "orderId", 6L);
        assertThatThrownBy(() -> service.recordBuy(other, execution, BigDecimal.ONE, stock()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LedgerEntry.execution(other, execution, BigDecimal.ONE, "wrong order"))
                .isInstanceOf(IllegalArgumentException.class);
        Stock otherStock = stock();
        ReflectionTestUtils.setField(otherStock, "stockId", 8L);
        assertThatThrownBy(() -> service.recordBuy(order, execution, BigDecimal.ONE, otherStock))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(repository);
    }

    private TradeOrder order(OrderSide side) {
        TradeOrder order = TradeOrder.filledMarketOrder(7L, 2L, UUID.randomUUID(), side,
                new BigDecimal("3"), new BigDecimal("100"), OPENED_AT, BigDecimal.ONE,
                new BigDecimal("300"), BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("300"), OPENED_AT);
        ReflectionTestUtils.setField(order, "orderId", 4L);
        return order;
    }

    private TradeExecution execution(TradeOrder order) {
        TradeExecution execution = TradeExecution.market(order, new OrderAmount(new BigDecimal("100"), BigDecimal.ONE,
                BigDecimal.ZERO, new BigDecimal("300"), new BigDecimal("300"), BigDecimal.ZERO,
                BigDecimal.ZERO, new BigDecimal("300"), BigDecimal.ZERO));
        ReflectionTestUtils.setField(execution, "executionId", 5L);
        return execution;
    }

    private Stock stock() {
        Stock stock = Stock.create("TEST", MarketCountry.KR, "KOSPI", "테스트", null, "KRW", "STOCK", true);
        ReflectionTestUtils.setField(stock, "stockId", 2L);
        return stock;
    }

    private void assertInvalid(Long accountId, BigDecimal amount, int roundNo, OffsetDateTime at) {
        assertThatThrownBy(() -> service.recordInitialDeposit(accountId, amount, roundNo, at))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
