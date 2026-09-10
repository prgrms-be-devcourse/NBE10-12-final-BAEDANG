-- V5__stock_name_jamo.sql
-- 종목 검색 향상 (#148): 1자 검색 · 한글 독립 초성 검색
--
-- 기술 스택을 늘리지 않고(pg_trgm·검색엔진 없이) DB 전처리로 해결한다.
-- stock 이 8,500 행이라 LIKE '%...%' 순차 스캔으로 충분하다.
--
-- !! 자모 분해 규칙이 두 가지다. 이 비대칭이 이 파일의 핵심이다.
--
--    (1) 컬럼 쪽 (partial_tail => false) — 모든 음절을 3칸 고정폭. 종성 없으면 '^' 패딩.
--          서울 → ㅅㅓ^ㅇㅜㄹ        삼성전자 → ㅅㅏㅁㅅㅓㅇㅈㅓㄴㅈㅏ^
--
--    (2) 검색어 쪽 (partial_tail => true) — 마지막 글자만 입력된 만큼. 패딩 없음.
--          김ㅊ → ㄱㅣㅁㅊ          김처 → ㄱㅣㅁㅊㅓ          서 → ㅅㅓ
--
--    패딩이 글자 경계를 지켜서 '성'(ㅅㅓㅇ)이 '서울'(ㅅㅓ^ㅇㅜㄹ)에 걸리지 않는다.
--    마지막 글자 부분 분해가 미완성 입력('김ㅊ')을 살린다. 둘 중 하나만 있으면
--    나머지 요구사항이 깨지므로 같이 봐야 한다.
--
-- !! 슬롯 구조(초성=자음, 중성=모음, 종성=자음|패딩) 덕분에 LIKE '%...%' 가
--    3칸 경계에 자동 정렬된다. 모음은 초성 칸에 올 수 없기 때문이다.
--    그래서 '전자' 로 '삼성전자' 를 찾는 기존 부분일치 동작을 그대로 유지한다.
--
-- !! 초성 검색은 name_chosung 을 따로 둔다. name_jamo 에 'ㅅ__ㅅ' 같은 LIKE
--    와일드카드를 쓰면 '%' 앵커가 3칸 경계를 안 지켜서 오탐이 난다
--    (예: '한국' = ㅎㅏㄴㄱㅜㄱ 이 검색어 'ㄴㄱ' 에 걸린다).
--
-- !! 출력은 반드시 호환 자모(U+3131~U+3163)로 낸다. 조합용 초성 ᄋ(U+110B)·
--    종성 ᆼ(U+11BC)은 키보드로 입력되는 ㅇ(U+3147)과 코드포인트가 달라서,
--    섞이면 매칭이 조용히 실패한다. 특히 ㅇ 에서 티가 안 난다.

-- ────────────────────────────────────────────────────────────────────────────
--  1. 자모 분해 / 초성 추출 함수
--
--   생성 컬럼에 쓰려면 IMMUTABLE 이어야 한다. 유니코드 산술뿐이라 조건 충족.
--   한글 음절이 아닌 문자(영문·숫자·독립 자모)는 그대로 통과시킨다.
-- ────────────────────────────────────────────────────────────────────────────
CREATE OR REPLACE FUNCTION hangul_jamo(txt text, partial_tail boolean)
    RETURNS text
    LANGUAGE plpgsql
    IMMUTABLE STRICT PARALLEL SAFE
AS $$
DECLARE
    CHO  constant text[] := ARRAY['ㄱ','ㄲ','ㄴ','ㄷ','ㄸ','ㄹ','ㅁ','ㅂ','ㅃ','ㅅ',
                                  'ㅆ','ㅇ','ㅈ','ㅉ','ㅊ','ㅋ','ㅌ','ㅍ','ㅎ'];
    JUNG constant text[] := ARRAY['ㅏ','ㅐ','ㅑ','ㅒ','ㅓ','ㅔ','ㅕ','ㅖ','ㅗ','ㅘ','ㅙ',
                                  'ㅚ','ㅛ','ㅜ','ㅝ','ㅞ','ㅟ','ㅠ','ㅡ','ㅢ','ㅣ'];
    JONG constant text[] := ARRAY['','ㄱ','ㄲ','ㄳ','ㄴ','ㄵ','ㄶ','ㄷ','ㄹ','ㄺ','ㄻ','ㄼ','ㄽ','ㄾ',
                                  'ㄿ','ㅀ','ㅁ','ㅂ','ㅄ','ㅅ','ㅆ','ㅇ','ㅈ','ㅊ','ㅋ','ㅌ','ㅍ','ㅎ'];
    -- !! LIKE 메타문자를 패드로 쓰면 안 된다. '_' 는 단일문자 와일드카드라서
    --    검색어 쪽 패드가 "아무 글자 한 개"로 해석되고, 글자 경계 보존이 통째로
    --    무력화된다 ('서우' 가 '성우' 에 걸린다). '^' 는 LIKE 메타문자가 아니다.
    PAD  constant text := '^';
    acc  text := '';
    ch   text;
    code int;
    idx  int;
    jong_ch text;
    n    int;
    i    int;
BEGIN
    -- !! 같은 '삼' 이라도 NFD(조합용 자모 ᄉ+ᅡ+ᆷ)로 들어오면 아래 완성형 범위에
    --    걸리지 않아 그대로 통과한다. 그 값이 name_jamo 에 저장되면 그 종목은
    --    영원히 검색되지 않는다 — 예외도 로그도 없다. 입구에서 한 번 통일한다.
    txt := normalize(txt, NFC);
    n   := length(txt);

    FOR i IN 1..n LOOP
        ch   := substring(txt from i for 1);
        code := ascii(ch);

        IF code BETWEEN 44032 AND 55203 THEN            -- 가(AC00) ~ 힣(D7A3)
            idx  := code - 44032;
            jong_ch := JONG[(idx % 28) + 1];
            acc  := acc || CHO[idx / 588 + 1] || JUNG[(idx / 28) % 21 + 1];

            IF jong_ch <> '' THEN
                acc := acc || jong_ch;
            ELSIF NOT (partial_tail AND i = n) THEN
                acc := acc || PAD;
            END IF;
        ELSE
            acc := acc || ch;
        END IF;
    END LOOP;

    RETURN acc;
END
$$;

COMMENT ON FUNCTION hangul_jamo(text, boolean) IS
    '한글 음절을 호환 자모로 분해. partial_tail=false 는 저장용(전체 3칸 패딩), '
        'true 는 검색어용(마지막 글자만 부분 분해). 자세한 근거는 V5 마이그레이션 주석 참고.';

CREATE OR REPLACE FUNCTION hangul_chosung(txt text)
    RETURNS text
    LANGUAGE plpgsql
    IMMUTABLE STRICT PARALLEL SAFE
AS $$
DECLARE
    CHO constant text[] := ARRAY['ㄱ','ㄲ','ㄴ','ㄷ','ㄸ','ㄹ','ㅁ','ㅂ','ㅃ','ㅅ',
                                 'ㅆ','ㅇ','ㅈ','ㅉ','ㅊ','ㅋ','ㅌ','ㅍ','ㅎ'];
    acc  text := '';
    ch   text;
    code int;
    i    int;
BEGIN
    txt := normalize(txt, NFC);   -- hangul_jamo 와 같은 이유

    FOR i IN 1..length(txt) LOOP
        ch   := substring(txt from i for 1);
        code := ascii(ch);

        IF code BETWEEN 44032 AND 55203 THEN
            acc := acc || CHO[(code - 44032) / 588 + 1];
        ELSE
            acc := acc || ch;
        END IF;
    END LOOP;

    RETURN acc;
END
$$;

-- ────────────────────────────────────────────────────────────────────────────
--  2. stock 생성 컬럼 (STORED)
--
--   !! 분해 전 전처리(공백 제거 + 소문자)가 검색 서비스의
--      DomainNormalizer.searchKey() 와 글자 하나까지 같아야 한다.
--      searchKey 가 \s+ 를 지우므로 여기도 replace(' ') 가 아니라 regexp_replace 다.
--      어긋나면 'KODEX 200' 처럼 공백 든 종목명이 영구히 안 잡힌다.
-- ────────────────────────────────────────────────────────────────────────────
ALTER TABLE stock
    ADD COLUMN name_jamo text
        GENERATED ALWAYS AS (hangul_jamo(lower(regexp_replace(name, '\s+', '', 'g')), false)) STORED,
    ADD COLUMN name_chosung text
        GENERATED ALWAYS AS (hangul_chosung(lower(regexp_replace(name, '\s+', '', 'g')))) STORED;

COMMENT ON COLUMN stock.name_jamo IS '검색용 자모 분해 (3칸 고정폭, 종성 없으면 ^ 패딩)';
COMMENT ON COLUMN stock.name_chosung IS '검색용 초성 추출 (독립 초성 검색 전용)';

-- 인덱스는 두지 않는다. LIKE '%...%' 는 btree 를 타지 못하고 8,500 행이면
-- 순차 스캔으로 충분하다. 검색 지연이 실측으로 문제되면 pg_trgm + GIN 을 검토한다.
