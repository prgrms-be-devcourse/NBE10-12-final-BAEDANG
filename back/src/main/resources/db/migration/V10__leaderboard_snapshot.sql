-- V10__leaderboard_snapshot.sql
-- 리더보드 배치 스냅샷 (#152 Phase 2 · 설계문서 §6.4)
--
-- 전 유저 실시간 정렬은 비싸고 커넥션 풀(#145)을 압박하므로, 하루 1회 오전 저트래픽 배치가
-- 자격 계좌(opened_at + 4주 채운 ACTIVE)의 평가·수익률·순위를 스냅샷으로 적재한다. 화면은
-- 이 스냅샷을 읽어 아침 고정 순위 + as-of 시각을 보여준다.
--
-- 계좌레벨 일별 equity 는 daily_account_snapshot 이 담당(차트용). 이 테이블은 순위/수익률/
-- 퍼센타일/as-of 라는 다른 관심사라 별도로 둔다.
--
-- 정렬 = 수익금액이지만 코호트 내 초기자본이 동일 상수라 순위 = 수익률% 내림차순과 같다.
-- 절대 금액·타인 잔고는 API 로 노출하지 않는다(사행성 배제, 설계문서 §3.1). equity 는 서버측에만
-- 저장하고(as-of 재현·"왜 이 순위인지" 검증용) 응답 DTO 에는 담지 않는다.
CREATE TABLE leaderboard_snapshot (
    snapshot_id  BIGINT         GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    as_of        TIMESTAMPTZ    NOT NULL,   -- 배치 1회 실행 시각(한 실행의 모든 행이 공유). 신선도 표시용.
    account_id   BIGINT         NOT NULL REFERENCES account(account_id),
    user_id      BIGINT         NOT NULL,   -- 닉네임 조인·내 순위 조회
    round_no     INT            NOT NULL,
    equity       NUMERIC(19,4)  NOT NULL,   -- 총자산(cash + 보유 평가) — 단일 평가 서비스 산출. 서버측 전용.
    -- (equity − initial_cash) / initial_cash (4자리). 넉넉한 자릿수 — 이상치 한 건이 배치 전체를
    -- 롤백시키지 않도록(saveAll 은 한 트랜잭션). 실데이터 수익률은 훨씬 작다.
    return_rate  NUMERIC(12,6)  NOT NULL,
    rank         INT            NOT NULL,   -- 코호트 내 순위(return_rate DESC, account_id ASC)
    participants INT            NOT NULL,   -- 그 as_of 코호트 전체 수(퍼센타일 계산용)
    CONSTRAINT uq_leaderboard_asof_account UNIQUE (as_of, account_id)
);

-- 최신 as_of 의 상위 N·내 주변 발췌를 순위로 훑는다.
CREATE INDEX ix_leaderboard_asof_rank ON leaderboard_snapshot (as_of, rank);

COMMENT ON TABLE leaderboard_snapshot IS
    '리더보드 배치 스냅샷(하루 1회). 자격=opened_at+4주 채운 ACTIVE 계좌. 최신 as_of 만 읽는다.';
