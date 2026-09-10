-- V7__seed_user_marker.sql
-- 개발/데모용 합성(시드) 회원 마커 (#152 Phase 2 리더보드·검증 게이트)
--
-- 리더보드·퍼센타일·유형 분포는 모집단이 있어야 성립하는데 서비스 가동 초기라 실유저가
-- 없다 → 합성 계좌를 대량 적재해 기능을 만들고 검증한다(설계문서 §8). 단 합성 계좌가
-- 실서비스 리더보드나 불변식 검증을 오염시키면 안 되므로, 실유저와 명확히 분리하는
-- 마커를 회원에 둔다.
--
-- !! 프로덕션 리더보드/리포트 쿼리는 is_seed = false 만 집계한다. dev/데모에서만 설정
--    토글로 시드를 포함한다. 마커가 빠지면 합성 데이터가 실순위에 섞여 사행성·기만이 된다.
ALTER TABLE users
    ADD COLUMN is_seed BOOLEAN NOT NULL DEFAULT false;

COMMENT ON COLUMN users.is_seed IS
    '개발/데모용 합성 회원 여부. 프로덕션 리더보드·리포트는 false 만 집계(실유저와 분리).';

-- 실유저만 훑는 리더보드 집계가 시드 급증에도 흔들리지 않도록 부분 인덱스를 둔다.
CREATE INDEX ix_users_real ON users (user_id) WHERE is_seed = false;
