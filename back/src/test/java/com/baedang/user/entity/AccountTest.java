package com.baedang.user.entity;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccountTest {

    private static final OffsetDateTime OPENED_AT = OffsetDateTime.parse("2026-08-27T04:00:00Z");

    @Test
    void 외부에서_받은_시각과_초기값으로_계좌를_개설한다() {
        Account account = Account.open(1L, 2, new BigDecimal("50000000"), OPENED_AT);

        assertThat(account.getOpenedAt()).isEqualTo(OPENED_AT);
        assertThat(account.getRoundNo()).isEqualTo(2);
        assertThat(account.getStatus()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(account.getInitialCash()).isEqualByComparingTo("50000000");
        assertThat(account.getCashBalance()).isEqualByComparingTo("50000000");
        assertThat(account.getLockedCash()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void 외부에서_받은_시각으로_계좌를_종료한다() {
        Account account = Account.open(1L, 1, BigDecimal.ONE, OPENED_AT);
        OffsetDateTime closedAt = OPENED_AT.plusHours(1);

        account.close(closedAt);

        assertThat(account.getStatus()).isEqualTo(AccountStatus.CLOSED);
        assertThat(account.getClosedAt()).isEqualTo(closedAt);
    }

    @Test
    void 이미_종료된_계좌는_다시_종료할_수_없다() {
        Account account = Account.open(1L, 1, BigDecimal.ONE, OPENED_AT);
        account.close(OPENED_AT.plusHours(1));

        assertThatThrownBy(() -> account.close(OPENED_AT.plusHours(2)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 개설보다_빠른_시각으로_계좌를_종료할_수_없다() {
        Account account = Account.open(1L, 1, BigDecimal.ONE, OPENED_AT);

        assertThatThrownBy(() -> account.close(OPENED_AT.minusNanos(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 회차와_초기금은_양수여야_한다() {
        assertThatThrownBy(() -> Account.open(1L, 0, BigDecimal.ONE, OPENED_AT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Account.open(1L, 1, BigDecimal.ZERO, OPENED_AT))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
