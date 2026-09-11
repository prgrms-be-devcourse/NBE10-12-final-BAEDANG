package com.baedang.report.leaderboard.service;

import com.baedang.report.leaderboard.dto.LeaderboardResponse;
import com.baedang.report.leaderboard.entity.LeaderboardRun;
import com.baedang.report.leaderboard.entity.LeaderboardSnapshot;
import com.baedang.report.leaderboard.repository.LeaderboardRunRepository;
import com.baedang.report.leaderboard.repository.LeaderboardSnapshotRepository;
import com.baedang.user.entity.Account;
import com.baedang.user.entity.AccountStatus;
import com.baedang.user.entity.User;
import com.baedang.user.repository.AccountRepository;
import com.baedang.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LeaderboardQueryServiceTest {

    private static final OffsetDateTime AS_OF = OffsetDateTime.parse("2026-09-11T07:30:00Z");

    @Mock LeaderboardSnapshotRepository snapshotRepository;
    @Mock LeaderboardRunRepository runRepository;
    @Mock AccountRepository accountRepository;
    @Mock UserRepository userRepository;

    private LeaderboardQueryService service() {
        return new LeaderboardQueryService(snapshotRepository, runRepository, accountRepository, userRepository, 10, 2);
    }

    private static LeaderboardSnapshot snap(long accountId, long userId, int rank, String returnRate) {
        return LeaderboardSnapshot.of(AS_OF, accountId, userId, 1,
                BigDecimal.valueOf(50_000_000), new BigDecimal(returnRate), rank, 100, null);
    }

    private static User user(long userId, String nickname) {
        User user = org.mockito.Mockito.mock(User.class);
        lenient().when(user.getUserId()).thenReturn(userId);
        lenient().when(user.getNickname()).thenReturn(nickname);
        return user;
    }

    private void givenTop() {
        // 목 유저를 먼저 만든 뒤 스터빙한다(when 안에서 when 호출 = 중첩 스터빙 금지).
        List<User> maskingUsers = List.of(user(501, "홍길동"), user(502, "김철수"), user(503, "이영희"));
        when(runRepository.findTopByOrderByAsOfDesc()).thenReturn(Optional.of(LeaderboardRun.of(AS_OF, 100)));
        when(snapshotRepository.findByAsOfAndRankLessThanEqualOrderByRankAsc(AS_OF, 10))
                .thenReturn(List.of(snap(101, 501, 1, "0.3"), snap(102, 502, 2, "0.2"), snap(103, 503, 3, "0.1")));
        when(userRepository.findAllById(any())).thenReturn(maskingUsers);
    }

    @Test
    void 상위_N을_마스킹_닉네임과_수익률로_내려준다() {
        givenTop();
        when(accountRepository.findByUserIdAndStatus(999L, AccountStatus.ACTIVE)).thenReturn(Optional.empty());

        LeaderboardResponse res = service().getLeaderboard(999L);

        assertThat(res.asOf()).isEqualTo(AS_OF);
        assertThat(res.participants()).isEqualTo(100);
        assertThat(res.top()).extracting(LeaderboardResponse.Entry::rank).containsExactly(1, 2, 3);
        assertThat(res.top()).extracting(LeaderboardResponse.Entry::nickname)
                .containsExactly("홍*동", "김*수", "이*희");
        assertThat(res.top().get(0).returnRate()).isEqualTo("0.3");
        assertThat(res.me()).isNull(); // 자격 계좌 없음
    }

    @Test
    void 내_순위는_브래킷과_주변_발췌를_포함한다() {
        givenTop();
        Account myAccount = org.mockito.Mockito.mock(Account.class);
        when(myAccount.getAccountId()).thenReturn(102L);
        when(accountRepository.findByUserIdAndStatus(502L, AccountStatus.ACTIVE))
                .thenReturn(Optional.of(myAccount));
        when(snapshotRepository.findByAsOfAndAccountId(AS_OF, 102L))
                .thenReturn(Optional.of(snap(102, 502, 2, "0.2")));
        when(snapshotRepository.findByAsOfAndRankBetweenOrderByRankAsc(AS_OF, 0, 4))
                .thenReturn(List.of(snap(101, 501, 1, "0.3"), snap(102, 502, 2, "0.2"), snap(103, 503, 3, "0.1")));

        LeaderboardResponse res = service().getLeaderboard(502L);

        assertThat(res.me()).isNotNull();
        assertThat(res.me().rank()).isEqualTo(2);
        assertThat(res.me().returnRate()).isEqualTo("0.2");
        assertThat(res.me().topPercent()).isEqualTo(5); // 2/100 → top5
        assertThat(res.me().neighbors()).extracting(LeaderboardResponse.Entry::rank).containsExactly(1, 2, 3);
        assertThat(res.me().neighbors()).extracting(LeaderboardResponse.Entry::nickname)
                .containsExactly("홍*동", "김*수", "이*희");
    }

    @Test
    void 배치_실행이_없으면_빈_보드를_내려준다() {
        when(runRepository.findTopByOrderByAsOfDesc()).thenReturn(Optional.empty());

        LeaderboardResponse res = service().getLeaderboard(1L);

        assertThat(res.asOf()).isNull();
        assertThat(res.participants()).isZero();
        assertThat(res.top()).isEmpty();
        assertThat(res.me()).isNull();
    }

    @Test
    void 최신_실행이_참가자_0이면_과거_순위_대신_빈_보드를_as_of와_함께_내린다() {
        // 전원 리셋·시드 제외 전환으로 최신 실행이 0명 → 스냅샷 행의 max(as_of) 가 아니라 최신 실행 기준.
        when(runRepository.findTopByOrderByAsOfDesc()).thenReturn(Optional.of(LeaderboardRun.of(AS_OF, 0)));

        LeaderboardResponse res = service().getLeaderboard(1L);

        assertThat(res.asOf()).isEqualTo(AS_OF); // as-of 는 유지("X시점 기준 참가자 없음")
        assertThat(res.participants()).isZero();
        assertThat(res.top()).isEmpty();
        assertThat(res.me()).isNull();
    }
}
