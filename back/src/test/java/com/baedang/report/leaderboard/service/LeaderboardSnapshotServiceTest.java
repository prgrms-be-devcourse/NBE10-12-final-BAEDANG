package com.baedang.report.leaderboard.service;

import com.baedang.account.service.AccountValuationService;
import com.baedang.account.support.AccountValuation;
import com.baedang.account.support.HoldingValuation;
import com.baedang.report.leaderboard.entity.LeaderboardRun;
import com.baedang.report.leaderboard.entity.LeaderboardSnapshot;
import com.baedang.report.leaderboard.repository.LeaderboardRunRepository;
import com.baedang.report.leaderboard.repository.LeaderboardSnapshotRepository;
import com.baedang.report.leaderboard.service.LeaderboardSnapshotService.SnapshotResult;
import com.baedang.report.service.InvestmentTypeService;
import com.baedang.report.support.InvestmentProfile;
import com.baedang.user.entity.Account;
import com.baedang.user.entity.AccountStatus;
import com.baedang.user.repository.AccountRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LeaderboardSnapshotServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-11T00:00:00Z");
    private static final long INITIAL = 50_000_000L;

    @Mock AccountRepository accountRepository;
    @Mock AccountValuationService accountValuationService;
    @Mock InvestmentTypeService investmentTypeService;
    @Mock LeaderboardSnapshotRepository snapshotRepository;
    @Mock LeaderboardRunRepository runRepository;

    private LeaderboardSnapshotService service(boolean includeSeed) {
        return new LeaderboardSnapshotService(
                accountRepository, accountValuationService, investmentTypeService, snapshotRepository, runRepository,
                4, includeSeed, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    /** cash=initial 인 계좌 목 — 수익률은 보유 평가합으로만 결정된다. */
    private Account account(long accountId, long userId) {
        Account account = org.mockito.Mockito.mock(Account.class);
        when(account.getAccountId()).thenReturn(accountId);
        when(account.getUserId()).thenReturn(userId);
        when(account.getRoundNo()).thenReturn(1);
        when(account.getInitialCash()).thenReturn(BigDecimal.valueOf(INITIAL));
        when(account.getCashBalance()).thenReturn(BigDecimal.valueOf(INITIAL));
        return account;
    }

    private void givenValuation(Account account, long stockValueWon) {
        HoldingValuation v = new HoldingValuation(
                1L, "KRW", BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE,
                BigDecimal.valueOf(stockValueWon), BigDecimal.valueOf(stockValueWon));
        when(accountValuationService.valuate(eq(account), any()))
                .thenReturn(new AccountValuation(account, List.of(), Map.of(), List.of(v), BigDecimal.valueOf(1350)));
    }

    @Test
    void 수익률_내림차순_동률은_accountId_오름차순으로_랭킹한다() {
        Account best = account(3L, 30L);   // 평가 +10M → 수익률 20%
        Account tieLow = account(1L, 10L);  // 평가 +5M  → 수익률 10%
        Account tieHigh = account(2L, 20L); // 평가 +5M  → 수익률 10% (best와 동률)
        when(accountRepository.findLeaderboardEligible(eq(AccountStatus.ACTIVE), any(), eq(true)))
                .thenReturn(List.of(tieLow, best, tieHigh));
        when(accountValuationService.currentUsdKrwRate()).thenReturn(BigDecimal.valueOf(1350));
        givenValuation(best, 10_000_000);
        givenValuation(tieLow, 5_000_000);
        givenValuation(tieHigh, 5_000_000);
        when(investmentTypeService.classify(any(), any(), any())).thenReturn(InvestmentProfile.unclassified(1));

        SnapshotResult result = service(true).refresh();

        assertThat(result.participants()).isEqualTo(3);
        ArgumentCaptor<List<LeaderboardSnapshot>> captor = ArgumentCaptor.forClass(List.class);
        verify(snapshotRepository).saveAll(captor.capture());
        List<LeaderboardSnapshot> saved = captor.getValue();

        assertThat(saved).extracting(LeaderboardSnapshot::getAccountId).containsExactly(3L, 1L, 2L);
        assertThat(saved).extracting(LeaderboardSnapshot::getRank).containsExactly(1, 2, 3);
        assertThat(saved).allMatch(s -> s.getParticipants() == 3);
        assertThat(saved).allMatch(s -> s.getAsOf().equals(OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC)));
        // 수익률 값 확인: 20% / 10% / 10%
        assertThat(saved.get(0).getReturnRate()).isEqualByComparingTo("0.2");
        assertThat(saved.get(1).getReturnRate()).isEqualByComparingTo("0.1");
        // 실행 기록도 남긴다(조회가 최신 실행을 기준으로 하게).
        verify(runRepository).save(org.mockito.ArgumentMatchers.argThat(r -> r.getParticipants() == 3));
    }

    @Test
    void 자격_계좌가_없으면_빈_보드이고_저장하지_않는다() {
        when(accountRepository.findLeaderboardEligible(eq(AccountStatus.ACTIVE), any(), eq(false)))
                .thenReturn(List.of());

        SnapshotResult result = service(false).refresh();

        assertThat(result.participants()).isZero();
        verify(snapshotRepository, never()).saveAll(any());
        // 참가자 0 이어도 실행 기록은 남긴다(조회가 빈 보드를 판별하도록).
        verify(runRepository).save(org.mockito.ArgumentMatchers.argThat(r -> r.getParticipants() == 0));
    }

    @Test
    void 시드_포함_여부는_코호트_쿼리로_전달된다() {
        when(accountRepository.findLeaderboardEligible(eq(AccountStatus.ACTIVE), any(), eq(false)))
                .thenReturn(List.of());

        service(false).refresh();

        verify(accountRepository).findLeaderboardEligible(eq(AccountStatus.ACTIVE), any(), eq(false));
    }
}
