-- V11__investment_report.sql
-- 투자 성향 리포트·리더보드 Phase 2 (#152) 스키마.
--
-- 합성 시딩 마커 + 리더보드 배치 스냅샷 + 배치 실행 기록을 한 마이그레이션으로 묶는다.
-- (동시 PR들이 버전 번호로 충돌 중이라 충돌면을 줄이려 단일 파일로 합쳤다.)

-- ── 합성(시드) 회원 마커 ─────────────────────────────────────────────────────
-- 리더보드·퍼센타일·유형 분포는 모집단이 있어야 성립하는데 가동 초기라 실유저가 없다 →
-- 합성 계좌를 대량 적재해 기능을 만들고 검증한다(설계문서 §8). 실서비스 리더보드·불변식
-- 검증을 오염시키지 않도록 실유저와 분리하는 마커를 둔다.
-- !! 프로덕션 리더보드/리포트 쿼리는 is_seed = false(또는 코호트에서 시드 제외)만 집계한다.
ALTER TABLE users
    ADD COLUMN is_seed BOOLEAN NOT NULL DEFAULT false;

COMMENT ON COLUMN users.is_seed IS
    '개발/데모용 합성 회원 여부. 프로덕션 리더보드·리포트는 false 만 집계(실유저와 분리).';

-- 실유저만 훑는 리더보드 집계가 시드 급증에도 흔들리지 않도록 부분 인덱스를 둔다.
CREATE INDEX ix_users_real ON users (user_id) WHERE is_seed = false;

-- ── 리더보드 배치 스냅샷 ─────────────────────────────────────────────────────
-- 전 유저 실시간 정렬은 비싸고 커넥션 풀(#145)을 압박하므로, 하루 1회 오전 배치가 자격
-- 계좌(opened_at + 4주 채운 ACTIVE)의 평가·수익률·순위를 스냅샷으로 적재한다(설계문서 §6.4).
--
-- 정렬 = 수익금액이지만 코호트 내 초기자본이 동일 상수라 순위 = 수익률% 내림차순과 같다.
-- 절대 금액·타인 잔고는 API 로 노출하지 않는다(사행성 배제, §3.1). equity 는 서버측에만
-- 저장하고(as-of 재현·검증용) 응답 DTO 에는 담지 않는다.
CREATE TABLE leaderboard_snapshot (
    snapshot_id  BIGINT         GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    as_of        TIMESTAMPTZ    NOT NULL,   -- 배치 1회 실행 시각(한 실행의 모든 행이 공유).
    account_id   BIGINT         NOT NULL REFERENCES account(account_id),
    user_id      BIGINT         NOT NULL,   -- 닉네임 조인·내 순위 조회
    round_no     INT            NOT NULL,
    equity       NUMERIC(19,4)  NOT NULL,   -- 총자산(cash + 보유 평가). 서버측 전용.
    -- (equity − initial_cash) / initial_cash (4자리). 이상치 한 건이 배치 전체를 롤백시키지
    -- 않도록 넉넉한 자릿수 — 실데이터 수익률은 훨씬 작다.
    return_rate  NUMERIC(12,6)  NOT NULL,
    rank         INT            NOT NULL,   -- 코호트 내 순위(return_rate DESC, account_id ASC)
    participants INT            NOT NULL,   -- 그 as_of 코호트 전체 수(퍼센타일 계산용)
    CONSTRAINT uq_leaderboard_asof_account UNIQUE (as_of, account_id)
);

-- 최신 as_of 의 상위 N·내 주변 발췌를 순위로 훑는다.
CREATE INDEX ix_leaderboard_asof_rank ON leaderboard_snapshot (as_of, rank);

COMMENT ON TABLE leaderboard_snapshot IS
    '리더보드 배치 스냅샷(하루 1회). 자격=opened_at+4주 채운 ACTIVE 계좌.';

-- ── 리더보드 배치 실행 기록 ──────────────────────────────────────────────────
-- 참가자 행과 별도로 "배치가 언제 돌았고 참가자가 몇이었나"를 기록한다. 참가자 0(전원 리셋·
-- 시드 제외 전환 등)이어도 한 행을 남겨, 조회가 최신 실행을 기준으로 빈 보드를 판별할 수
-- 있게 한다(스냅샷 행의 max(as_of) 만 보면 과거 순위가 계속 노출되는 문제 방지).
CREATE TABLE leaderboard_run (
    as_of        TIMESTAMPTZ NOT NULL PRIMARY KEY,
    participants INT         NOT NULL
);

COMMENT ON TABLE leaderboard_run IS
    '리더보드 배치 실행 기록(매 실행 1행, 참가자 0 포함). 조회는 최신 run 을 기준으로 한다.';
