package com.baedang.report.leaderboard.service;

import com.baedang.report.leaderboard.dto.LeaderboardResponse;
import com.baedang.report.leaderboard.dto.LeaderboardResponse.Entry;
import com.baedang.report.leaderboard.dto.LeaderboardResponse.MeSection;
import com.baedang.report.leaderboard.dto.LeaderboardTypesResponse;
import com.baedang.report.leaderboard.entity.LeaderboardRun;
import com.baedang.report.leaderboard.entity.LeaderboardSnapshot;
import com.baedang.report.leaderboard.repository.LeaderboardRunRepository;
import com.baedang.report.leaderboard.repository.LeaderboardSnapshotRepository;
import com.baedang.report.leaderboard.support.NicknameMasker;
import com.baedang.report.leaderboard.support.PercentileBracket;
import com.baedang.report.support.InvestmentType;
import com.baedang.user.entity.AccountStatus;
import com.baedang.user.entity.User;
import com.baedang.user.repository.AccountRepository;
import com.baedang.user.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.baedang.global.formatter.FinancialDecimalFormatter.plain;

/**
 * 리더보드 조회 서비스. 가장 최근 배치 스냅샷({@code max(as_of)})만 읽어 상위 N + 내 순위 주변을
 * 마스킹 닉네임·수익률%로 구성한다(설계문서 §6.4·§6.5).
 */
@Service
@Transactional(readOnly = true)
public class LeaderboardQueryService {

    private final LeaderboardSnapshotRepository snapshotRepository;
    private final LeaderboardRunRepository runRepository;
    private final AccountRepository accountRepository;
    private final UserRepository userRepository;
    private final int topSize;
    private final int neighborRadius;

    public LeaderboardQueryService(
            LeaderboardSnapshotRepository snapshotRepository,
            LeaderboardRunRepository runRepository,
            AccountRepository accountRepository,
            UserRepository userRepository,
            @Value("${report.leaderboard.top-size:10}") int topSize,
            @Value("${report.leaderboard.neighbor-radius:2}") int neighborRadius
    ) {
        this.snapshotRepository = snapshotRepository;
        this.runRepository = runRepository;
        this.accountRepository = accountRepository;
        this.userRepository = userRepository;
        this.topSize = topSize;
        this.neighborRadius = neighborRadius;
    }

    public LeaderboardResponse getLeaderboard(Long userId) {
        // 스냅샷 행의 max(as_of) 가 아니라 최신 배치 실행을 기준으로 한다 — 최신 실행이 0명이면
        // 과거 순위를 노출하지 않고 빈 보드를 보인다(전원 리셋·시드 제외 전환 대응).
        LeaderboardRun run = runRepository.findTopByOrderByAsOfDesc().orElse(null);
        if (run == null) {
            return LeaderboardResponse.empty(); // 아직 배치 실행 없음
        }
        OffsetDateTime asOf = run.getAsOf();
        if (run.getParticipants() == 0) {
            return new LeaderboardResponse(asOf, 0, List.of(), null); // 최신 실행이 빈 보드(as-of 유지)
        }

        List<LeaderboardSnapshot> top =
                snapshotRepository.findByAsOfAndRankLessThanEqualOrderByRankAsc(asOf, topSize);
        int participants = run.getParticipants();

        List<LeaderboardSnapshot> neighbors = List.of();
        Optional<LeaderboardSnapshot> mine = myRow(userId, asOf);
        if (mine.isPresent()) {
            int rank = mine.get().getRank();
            neighbors = snapshotRepository.findByAsOfAndRankBetweenOrderByRankAsc(
                    asOf, rank - neighborRadius, rank + neighborRadius);
        }

        Map<Long, String> maskedNicknames = maskedNicknames(top, neighbors);
        List<Entry> topEntries = toEntries(top, maskedNicknames);

        MeSection me = null;
        if (mine.isPresent()) {
            me = meSection(mine.get(), asOf, toEntries(neighbors, maskedNicknames));
        }
        return new LeaderboardResponse(asOf, participants, topEntries, me);
    }

    private MeSection meSection(LeaderboardSnapshot m, OffsetDateTime asOf, List<Entry> neighbors) {
        String typeCode = m.getTypeCode();
        String typeLabel = null;
        Integer typeRank = null;
        Integer typeParticipants = null;
        Integer typePercent = null;
        if (typeCode != null) {
            // 유형 내 순위는 저장하지 않고 최신 as_of 스냅샷에서 윈도우로 센다(스냅샷과 자기일관).
            int participants = (int) snapshotRepository.countByAsOfAndTypeCode(asOf, typeCode);
            int rank = (int) snapshotRepository.countBetterInType(
                    asOf, typeCode, m.getReturnRate(), m.getAccountId()) + 1;
            typeLabel = InvestmentType.fromCode(typeCode).label();
            typeParticipants = participants;
            typeRank = rank;
            typePercent = PercentileBracket.of(rank, participants);
        }
        return new MeSection(
                m.getRank(), plain(m.getReturnRate()),
                PercentileBracket.of(m.getRank(), m.getParticipants()), neighbors,
                typeCode, typeLabel, typeRank, typeParticipants, typePercent);
    }

    /** 유형별 평균 수익률 비교(최신 배치 스냅샷). 미분류 제외. 빈 보드/실행 없음이면 빈 목록. */
    public LeaderboardTypesResponse getTypeComparison() {
        LeaderboardRun run = runRepository.findTopByOrderByAsOfDesc().orElse(null);
        if (run == null || run.getParticipants() == 0) {
            return LeaderboardTypesResponse.empty();
        }
        OffsetDateTime asOf = run.getAsOf();
        List<LeaderboardTypesResponse.TypeEntry> types = snapshotRepository.aggregateByType(asOf).stream()
                .map(a -> new LeaderboardTypesResponse.TypeEntry(
                        a.typeCode(),
                        InvestmentType.fromCode(a.typeCode()).label(),
                        a.count(),
                        plain(a.avgReturnRate().setScale(4, RoundingMode.HALF_UP))))
                .toList();
        return new LeaderboardTypesResponse(asOf, types);
    }

    private Optional<LeaderboardSnapshot> myRow(Long userId, OffsetDateTime asOf) {
        return accountRepository.findByUserIdAndStatus(userId, AccountStatus.ACTIVE)
                .flatMap(account -> snapshotRepository.findByAsOfAndAccountId(asOf, account.getAccountId()));
    }

    private Map<Long, String> maskedNicknames(List<LeaderboardSnapshot> top, List<LeaderboardSnapshot> neighbors) {
        Set<Long> userIds = new HashSet<>();
        top.forEach(s -> userIds.add(s.getUserId()));
        neighbors.forEach(s -> userIds.add(s.getUserId()));
        if (userIds.isEmpty()) {
            return Map.of();
        }
        return userRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(User::getUserId, u -> NicknameMasker.mask(u.getNickname())));
    }

    private List<Entry> toEntries(List<LeaderboardSnapshot> rows, Map<Long, String> maskedNicknames) {
        List<Entry> entries = new ArrayList<>(rows.size());
        for (LeaderboardSnapshot s : rows) {
            entries.add(new Entry(
                    s.getRank(),
                    maskedNicknames.getOrDefault(s.getUserId(), "*"),
                    plain(s.getReturnRate())));
        }
        return entries;
    }
}
