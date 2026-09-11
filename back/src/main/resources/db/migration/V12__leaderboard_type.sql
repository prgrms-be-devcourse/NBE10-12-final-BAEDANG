-- V12__leaderboard_type.sql
-- 리더보드 스냅샷에 투자 성향(MBTI) 유형 차원 추가 (#153 Phase 3).
--
-- 유형별 평균 수익 비교·유형 내 순위를 같은 as_of 코호트 위에서 계산하려면, 배치가 계좌별
-- 유형을 스냅샷에 함께 적재해야 한다(조회 시점 재계산·리포트와의 불일치 방지). 유형은 개인
-- 리포트와 같은 원가 4주 평균 경로로 판정한다(단일 지점). 보유 2종목 미만 등 미분류는 NULL.
ALTER TABLE leaderboard_snapshot
    ADD COLUMN type_code VARCHAR(4);

COMMENT ON COLUMN leaderboard_snapshot.type_code IS
    '투자 MBTI 유형코드(4자, 예 DKSB). 미분류(보유<2 등)는 NULL — 유형 비교·순위에서 제외.';
