package com.baedang.account.service;

import com.baedang.account.dto.LedgerResponse;
import com.baedang.account.support.LedgerCursor;
import com.baedang.global.error.BusinessException;
import com.baedang.global.error.ErrorCode;
import com.baedang.stock.entity.MarketCountry;
import com.baedang.stock.entity.Stock;
import com.baedang.stock.repository.StockRepository;
import com.baedang.trading.entity.EntryType;
import com.baedang.trading.entity.LedgerEntry;
import com.baedang.trading.entity.TradeOrder;
import com.baedang.trading.repository.LedgerEntryRepository;
import com.baedang.trading.repository.TradeOrderRepository;
import com.baedang.user.entity.Account;
import com.baedang.user.entity.AccountStatus;
import com.baedang.user.repository.AccountRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LedgerQueryServiceTest {

    @Mock
    AccountRepository accountRepository;
    @Mock
    LedgerEntryRepository ledgerEntryRepository;
    @Mock
    TradeOrderRepository tradeOrderRepository;
    @Mock
    StockRepository stockRepository;
    @Mock
    Account account;

    /**
     * occurredAt 을 단언하지 않는 테스트용 고정 시각 — now() 를 쓰지 않아 결정적으로 유지한다.
     */
    private static final OffsetDateTime AT = OffsetDateTime.parse("2026-08-10T00:00:00Z");

    private LedgerQueryService service() {
        return new LedgerQueryService(
                accountRepository, ledgerEntryRepository, tradeOrderRepository, stockRepository);
    }

    @Test
    void ACTIVE_계좌가_없으면_ACCOUNT_NOT_FOUND_를_던진다() {
        when(accountRepository.findByUserIdAndStatus(1L, AccountStatus.ACTIVE))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().getLedger(1L, null, null, null))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.ACCOUNT_NOT_FOUND));

        verifyNoInteractions(ledgerEntryRepository, tradeOrderRepository, stockRepository);
    }

    @Test
    void 최신순_항목에_종목명을_조인하고_occurredAt_을_UTC_로_정규화한다() {
        givenActiveAccount();
        LedgerEntry buy = withId(LedgerEntry.buy(1L, 1024L, new BigDecimal("2415242"),
                new BigDecimal("47584758"), BigDecimal.ONE, "삼성전자 10주 @ 241,500 (수수료 포함)",
                OffsetDateTime.of(2026, 8, 11, 12, 37, 2, 0, ZoneOffset.ofHours(9))), 3041L);
        LedgerEntry deposit = withId(LedgerEntry.initialDeposit(1L, new BigDecimal("50000000"),
                "모의투자금 지급", OffsetDateTime.of(2026, 8, 10, 9, 0, 0, 0, ZoneOffset.ofHours(9))), 3040L);
        TradeOrder buyOrder = order(1024L, 101L);
        when(ledgerEntryRepository.findPage(eq(1L), isNull(), isNull(), any(Pageable.class)))
                .thenReturn(List.of(buy, deposit));
        when(tradeOrderRepository.findByOrderIdIn(any())).thenReturn(List.of(buyOrder));
        when(stockRepository.findByStockIdIn(any()))
                .thenReturn(List.of(stock(101L, "005930", "삼성전자")));

        LedgerResponse response = service().getLedger(1L, null, null, null);

        assertThat(response.hasNext()).isFalse();
        // 마지막 페이지에도 nextCursor 는 마지막 항목(3040)을 가리킨다 (api-spec 계약).
        assertThat(response.nextCursor()).isEqualTo(LedgerCursor.encode(3040L));
        assertThat(response.items()).hasSize(2);

        LedgerResponse.Item first = response.items().get(0);
        assertThat(first.entryId()).isEqualTo(3041L);
        assertThat(first.entryType()).isEqualTo("BUY");
        assertThat(first.amount()).isEqualTo("-2415242");
        assertThat(first.balanceAfter()).isEqualTo("47584758");
        assertThat(first.exchangeRate()).isEqualTo("1");
        assertThat(first.orderId()).isEqualTo(1024L);
        assertThat(first.symbol()).isEqualTo("005930");
        assertThat(first.name()).isEqualTo("삼성전자");
        // 12:37:02 +09:00 → 03:37:02Z 로 정규화
        assertThat(first.occurredAt()).isEqualTo(OffsetDateTime.parse("2026-08-11T03:37:02Z"));

        LedgerResponse.Item second = response.items().get(1);
        assertThat(second.entryType()).isEqualTo("INITIAL_DEPOSIT");
        assertThat(second.orderId()).isNull();
        assertThat(second.symbol()).isNull();
        assertThat(second.name()).isNull();
    }

    @Test
    void 다음_페이지가_있으면_hasNext_와_nextCursor_를_채우고_size_는_한_건만_남긴다() {
        givenActiveAccount();
        LedgerEntry newer = withId(LedgerEntry.initialDeposit(1L, new BigDecimal("50000000"),
                "지급", AT), 3040L);
        LedgerEntry older = withId(LedgerEntry.initialDeposit(1L, new BigDecimal("50000000"),
                "지급", AT), 3039L);
        // size=1 요청 → 서비스는 size+1(2)건을 조회하고, 1건만 남긴 뒤 hasNext=true.
        when(ledgerEntryRepository.findPage(eq(1L), isNull(), isNull(), any(Pageable.class)))
                .thenReturn(List.of(newer, older));

        LedgerResponse response = service().getLedger(1L, null, 1, null);

        assertThat(response.items()).hasSize(1);
        assertThat(response.hasNext()).isTrue();
        assertThat(response.nextCursor()).isEqualTo(LedgerCursor.encode(3040L));

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(ledgerEntryRepository).findPage(eq(1L), isNull(), isNull(), pageable.capture());
        assertThat(pageable.getValue().getPageSize()).isEqualTo(2);
    }

    @Test
    void entryType_필터와_커서를_디코드해_그대로_전달한다() {
        givenActiveAccount();
        when(ledgerEntryRepository.findPage(any(), any(), any(), any())).thenReturn(List.of());

        LedgerResponse response = service().getLedger(1L, LedgerCursor.encode(3040L), 20, "buy");

        verify(ledgerEntryRepository).findPage(eq(1L), eq(EntryType.BUY), eq(3040L), any(Pageable.class));
        // 결과가 비면 nextCursor 는 null, hasNext 는 false.
        assertThat(response.items()).isEmpty();
        assertThat(response.nextCursor()).isNull();
        assertThat(response.hasNext()).isFalse();
    }

    @Test
    void size_가_최대치를_넘으면_50_으로_clamp_한다() {
        givenActiveAccount();
        when(ledgerEntryRepository.findPage(any(), any(), any(), any())).thenReturn(List.of());

        service().getLedger(1L, null, 100, null);

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(ledgerEntryRepository).findPage(any(), any(), any(), pageable.capture());
        assertThat(pageable.getValue().getPageSize()).isEqualTo(51); // 50 + 1
    }

    @Test
    void 지원하지_않는_entryType_은_INVALID_INPUT_이다() {
        // 계좌 조회는 통과하되 findPage 이전(parseEntryType)에서 끊기므로 accountId 스텁은 두지 않는다.
        when(accountRepository.findByUserIdAndStatus(1L, AccountStatus.ACTIVE))
                .thenReturn(Optional.of(account));

        assertThatThrownBy(() -> service().getLedger(1L, null, null, "DIVIDEND"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT));

        verifyNoInteractions(ledgerEntryRepository);
    }

    private void givenActiveAccount() {
        when(accountRepository.findByUserIdAndStatus(1L, AccountStatus.ACTIVE))
                .thenReturn(Optional.of(account));
        when(account.getAccountId()).thenReturn(1L);
    }

    private LedgerEntry withId(LedgerEntry entry, long entryId) {
        ReflectionTestUtils.setField(entry, "entryId", entryId);
        return entry;
    }

    private TradeOrder order(Long orderId, Long stockId) {
        TradeOrder order = mock(TradeOrder.class);
        when(order.getOrderId()).thenReturn(orderId);
        when(order.getStockId()).thenReturn(stockId);
        return order;
    }

    private Stock stock(Long stockId, String symbol, String name) {
        Stock stock = Stock.create(symbol, MarketCountry.KR, "KOSPI", name, null, "KRW", "STOCK", true);
        ReflectionTestUtils.setField(stock, "stockId", stockId);
        return stock;
    }
}
