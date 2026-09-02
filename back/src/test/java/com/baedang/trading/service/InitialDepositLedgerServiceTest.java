package com.baedang.trading.service;

import com.baedang.trading.entity.EntryType;
import com.baedang.trading.entity.LedgerEntry;
import com.baedang.trading.repository.LedgerEntryRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class InitialDepositLedgerServiceTest {

    private static final OffsetDateTime OPENED_AT = OffsetDateTime.parse("2026-09-02T00:00:00Z");
    private final LedgerEntryRepository repository = mock(LedgerEntryRepository.class);
    private final InitialDepositLedgerService service = new InitialDepositLedgerService(repository);

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

    private void assertInvalid(Long accountId, BigDecimal amount, int roundNo, OffsetDateTime at) {
        assertThatThrownBy(() -> service.recordInitialDeposit(accountId, amount, roundNo, at))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
