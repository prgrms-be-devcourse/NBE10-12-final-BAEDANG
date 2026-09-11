package com.baedang.report.leaderboard.service;

import com.baedang.account.service.AccountValuationService;
import com.baedang.account.support.AccountValuation;
import com.baedang.account.support.HoldingValuation;
import com.baedang.account.support.ReturnRateCalculator;
import com.baedang.report.leaderboard.entity.LeaderboardSnapshot;
import com.baedang.report.leaderboard.repository.LeaderboardSnapshotRepository;
import com.baedang.user.entity.Account;
import com.baedang.user.entity.AccountStatus;
import com.baedang.user.repository.AccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 리더보드 순위 스냅샷 배치(설계문서 §6.4).
 *
 * <p>자격 계좌({@code opened_at + N주}를 채운 ACTIVE)를 순회하며 <b>단일 평가 서비스</b>
 * ({@link AccountValuationService})로만 평가하고(리포트와 값이 어긋나지 않게), 수익률로 순위를
 * 매겨 한 실행분을 같은 {@code as_of}로 적재한다. 정렬은 {@code return_rate DESC, account_id ASC}
 * 로 결정적이다(동률 계좌가 재실행마다 뒤섞이지 않게 — as-of 신선도 스토리 보호).
 *
 * <p><b>시드 포함은 코호트 시점 결정:</b> {@code include-seed=false}면 실유저만 랭킹해 순위·
 * 퍼센타일이 자기일관한다(읽기서 숨기면 순위에 구멍). 프로덕션 기본은 false.
 *
 * <p><b>커넥션 풋프린트(#145):</b> 전용 단일 스레드에서 한 트랜잭션으로 계좌를 순차 평가하므로
 * 공유 풀(10)에서 커넥션 1개만, 하루 1회 저트래픽 창에서만 점유한다.
 */
@Service
public class LeaderboardSnapshotService {

    private static final Logger log = LoggerFactory.getLogger(LeaderboardSnapshotService.class);

    private final AccountRepository accountRepository;
    private final AccountValuationService accountValuationService;
    private final LeaderboardSnapshotRepository snapshotRepository;
    private final int eligibilityWeeks;
    private final boolean includeSeed;
    private final Clock clock;

    public LeaderboardSnapshotService(
            AccountRepository accountRepository,
            AccountValuationService accountValuationService,
            LeaderboardSnapshotRepository snapshotRepository,
            @Value("${report.leaderboard.eligibility-weeks:4}") int eligibilityWeeks,
            @Value("${report.leaderboard.include-seed:false}") boolean includeSeed,
            Clock clock
    ) {
        this.accountRepository = accountRepository;
        this.accountValuationService = accountValuationService;
        this.snapshotRepository = snapshotRepository;
        this.eligibilityWeeks = eligibilityWeeks;
        this.includeSeed = includeSeed;
        this.clock = clock;
    }

    public record SnapshotResult(OffsetDateTime asOf, int participants) {
    }

    /** 자격 계좌를 평가·랭킹해 새 as_of 로 스냅샷을 적재한다. 자격 계좌가 없으면 빈 결과(빈 보드). */
    @Transactional
    public SnapshotResult refresh() {
        OffsetDateTime asOf = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        OffsetDateTime openedAtOrBefore = asOf.minusWeeks(eligibilityWeeks);

        List<Account> eligible = accountRepository.findLeaderboardEligible(
                AccountStatus.ACTIVE, openedAtOrBefore, includeSeed);
        if (eligible.isEmpty()) {
            log.info("리더보드 자격 계좌 없음(자격=개설 {}주 경과) — 빈 보드", eligibilityWeeks);
            return new SnapshotResult(asOf, 0);
        }

        BigDecimal usdKrwRate = accountValuationService.currentUsdKrwRate();
        List<Ranked> ranked = new ArrayList<>(eligible.size());
        for (Account account : eligible) {
            AccountValuation valued = accountValuationService.valuate(account, usdKrwRate);
            BigDecimal stockValue = valued.valuations().stream()
                    .map(HoldingValuation::evalWon)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal equity = account.getCashBalance().add(stockValue);
            // 초기자본은 항상 양수라 non-null. 리포트와 동일 산식·반올림(단일 지점).
            BigDecimal returnRate = ReturnRateCalculator.calculate(
                    equity.subtract(account.getInitialCash()), account.getInitialCash());
            ranked.add(new Ranked(account, equity, returnRate));
        }

        // 수익률 내림차순, 동률은 account_id 오름차순(결정적 타이브레이크).
        ranked.sort(Comparator.comparing(Ranked::returnRate).reversed()
                .thenComparing(r -> r.account().getAccountId()));

        int participants = ranked.size();
        List<LeaderboardSnapshot> rows = new ArrayList<>(participants);
        int rank = 1;
        for (Ranked r : ranked) {
            Account a = r.account();
            rows.add(LeaderboardSnapshot.of(
                    asOf, a.getAccountId(), a.getUserId(), a.getRoundNo(),
                    r.equity(), r.returnRate(), rank++, participants));
        }
        snapshotRepository.saveAll(rows);
        log.info("리더보드 스냅샷 적재 — as_of={}, 참가자={}", asOf, participants);
        return new SnapshotResult(asOf, participants);
    }

    private record Ranked(Account account, BigDecimal equity, BigDecimal returnRate) {
    }
}
