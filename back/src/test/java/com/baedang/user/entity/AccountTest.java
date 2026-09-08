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

        assertThat(account.getUserId()).isEqualTo(1L);
        assertThat(account.getOpenedAt()).isEqualTo(OPENED_AT);
        assertThat(account.getRoundNo()).isEqualTo(2);
        assertThat(account.getStatus()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(account.getInitialCash()).isEqualByComparingTo("50000000");
        assertThat(account.getCashBalance()).isEqualByComparingTo("50000000");
        assertThat(account.getLockedCash()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(account.getVersion()).isNull();
    }

    @Test
    void 기본_생성자로_생성된_인스턴스는_null_상태이다() {
        Account account = new Account();
        assertThat(account.getAccountId()).isNull();
        assertThat(account.getUserId()).isNull();
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

    @Test
    void 계좌개설시_필수값_누락을_검증한다() {
        assertThatThrownBy(() -> Account.open(null, 1, BigDecimal.ONE, OPENED_AT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Account.open(1L, 1, null, OPENED_AT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Account.open(1L, 1, BigDecimal.ONE, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 계좌종료시_시각누락을_검증한다() {
        Account account = Account.open(1L, 1, BigDecimal.ONE, OPENED_AT);
        assertThatThrownBy(() -> account.close(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 시장가_매수차감과_매도반영을_수행한다() {
        Account account = Account.open(1L, 1, new BigDecimal("100000"), OPENED_AT);

        account.debitMarketBuy(new BigDecimal("30000"));
        assertThat(account.getCashBalance()).isEqualByComparingTo("70000");
        assertThat(account.availableCash()).isEqualByComparingTo("70000");

        account.creditMarketSell(new BigDecimal("50000"));
        assertThat(account.getCashBalance()).isEqualByComparingTo("120000");
        assertThat(account.availableCash()).isEqualByComparingTo("120000");
    }

    @Test
    void 시장가_매수차감_예외를_검증한다() {
        Account account = Account.open(1L, 1, new BigDecimal("100000"), OPENED_AT);

        assertThatThrownBy(() -> account.debitMarketBuy(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> account.debitMarketBuy(BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> account.debitMarketBuy(new BigDecimal("100001")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 시장가_매도반영_예외를_검증한다() {
        Account account = Account.open(1L, 1, new BigDecimal("100000"), OPENED_AT);

        assertThatThrownBy(() -> account.creditMarketSell(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> account.creditMarketSell(BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 예수금을_동결하고_해제한다() {
        Account account = Account.open(1L, 1, new BigDecimal("100000"), OPENED_AT);

        account.reserveCash(new BigDecimal("40000"));
        assertThat(account.getCashBalance()).isEqualByComparingTo("100000");
        assertThat(account.getLockedCash()).isEqualByComparingTo("40000");
        assertThat(account.availableCash()).isEqualByComparingTo("60000");

        account.releaseCash(new BigDecimal("15000"));
        assertThat(account.getLockedCash()).isEqualByComparingTo("25000");
        assertThat(account.availableCash()).isEqualByComparingTo("75000");
    }

    @Test
    void 예수금_동결_예외조건을_검증한다() {
        Account account = Account.open(1L, 1, new BigDecimal("100000"), OPENED_AT);

        assertThatThrownBy(() -> account.reserveCash(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> account.reserveCash(BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> account.reserveCash(new BigDecimal("10.5")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> account.reserveCash(new BigDecimal("100001")))
                .isInstanceOf(IllegalStateException.class);

        account.close(OPENED_AT.plusHours(1));
        assertThatThrownBy(() -> account.reserveCash(new BigDecimal("10000")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 예수금_해제_예외조건을_검증한다() {
        Account account = Account.open(1L, 1, new BigDecimal("100000"), OPENED_AT);
        account.reserveCash(new BigDecimal("30000"));

        assertThatThrownBy(() -> account.releaseCash(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> account.releaseCash(BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> account.releaseCash(new BigDecimal("10.5")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> account.releaseCash(new BigDecimal("30001")))
                .isInstanceOf(IllegalStateException.class);
    }
}
