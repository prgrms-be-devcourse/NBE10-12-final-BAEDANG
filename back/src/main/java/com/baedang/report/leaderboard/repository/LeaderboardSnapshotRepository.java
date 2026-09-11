package com.baedang.report.leaderboard.repository;

import com.baedang.report.leaderboard.dto.TypeAggregate;
import com.baedang.report.leaderboard.entity.LeaderboardSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface LeaderboardSnapshotRepository extends JpaRepository<LeaderboardSnapshot, Long> {

    /** 상위 N 발췌. */
    List<LeaderboardSnapshot> findByAsOfAndRankLessThanEqualOrderByRankAsc(OffsetDateTime asOf, int maxRank);

    /** 내 순위 주변 발췌. */
    List<LeaderboardSnapshot> findByAsOfAndRankBetweenOrderByRankAsc(OffsetDateTime asOf, int lowRank, int highRank);

    /** 내 계좌의 순위 행. */
    Optional<LeaderboardSnapshot> findByAsOfAndAccountId(OffsetDateTime asOf, Long accountId);

    /** 유형별 평균 수익률·인원(최신 as_of). 미분류(type_code null)는 제외. 평균 높은 순. */
    @Query("""
            select new com.baedang.report.leaderboard.dto.TypeAggregate(
                s.typeCode, count(s), avg(s.returnRate))
            from LeaderboardSnapshot s
            where s.asOf = :asOf and s.typeCode is not null
            group by s.typeCode
            order by avg(s.returnRate) desc
            """)
    List<TypeAggregate> aggregateByType(@Param("asOf") OffsetDateTime asOf);

    /** 같은 유형 인원 수(유형 내 퍼센타일 분모). */
    long countByAsOfAndTypeCode(OffsetDateTime asOf, String typeCode);

    /** 같은 유형에서 나보다 상위인 인원 수(+1 = 유형 내 순위). 정렬키 = return_rate DESC, account_id ASC. */
    @Query("""
            select count(s) from LeaderboardSnapshot s
            where s.asOf = :asOf and s.typeCode = :typeCode
              and (s.returnRate > :returnRate
                   or (s.returnRate = :returnRate and s.accountId < :accountId))
            """)
    long countBetterInType(@Param("asOf") OffsetDateTime asOf, @Param("typeCode") String typeCode,
                           @Param("returnRate") BigDecimal returnRate, @Param("accountId") Long accountId);
}
