-- ============================================================================
--  모의 주식 트레이딩 서비스 — 1주차 MVP 스키마 (PostgreSQL 15+)
--
--  설계 원칙
--   1) 금액은 전부 NUMERIC. double / float 는 절대 쓰지 않는다.
--   2) 시각은 전부 TIMESTAMPTZ. DB 는 UTC 로 저장하고 표시할 때만 변환한다.
--   3) 거래 원장(ledger_entry)은 append-only. UPDATE / DELETE 하지 않는다.
--   4) 포트폴리오 초기화는 삭제가 아니라 '새 회차 계좌 개설'이다.
--
--  실행:  psql -U trading -d trading -f schema.sql
-- ============================================================================

BEGIN;

-- ============================================================================
--  거래 정책 (확정)
--
--   매수   net_amount = gross_amount + fee
--   매도   net_amount = gross_amount - fee - tax
--
--     fee = gross_amount x FEE_RATE     (0.01%, 매수·매도 공통, 국내·미국 공통)
--
--     tax 는 시장에 따라 계산식이 다르다.        ← 매수는 두 시장 모두 0
--       국내  k_tax = gross_amount x K_TAX_RATE                        (0.002)
--       미국  a_tax = max(gross_amount x A_TAX_RATE, A_TAX_MIN_USD x 환율)
--                                                     (0.0000206, 최소 $0.01)
--
--   [요율은 DB 에 저장하지 않는다]   ← 확정
--     .env 의 FEE_RATE / K_TAX_RATE / A_TAX_RATE / A_TAX_MIN_USD 가 유일한 정의 지점이고,
--     테이블에는 계산 "결과값"만 들어간다 — trade_order.fee / trade_order.tax.
--
--     · 요율은 주문마다 다른 값이 아니라 전역 정책이다. 주문 행마다 복사하면
--       같은 값이 수만 번 중복되고, 정책이 바뀔 때 어디가 진실인지 모호해진다.
--     · 결과값만 있어도 충분하다. 요율이 나중에 바뀌어도 과거 거래는 그대로 남는다.
--       원장이 지켜야 하는 것은 "그때 얼마를 냈는가"이지 "몇 %였는가"가 아니다.
--     · 굳이 적용 요율을 되짚어야 하면 tax / gross_amount 로 역산할 수 있다.
--
--     !! 팀원 전원이 같은 .env 값을 써야 한다. 한 사람만 다르면 같은 주문인데
--        금액이 달라지고, 원인을 찾기 어려운 종류의 버그가 된다.
--
--     !! k_tax 와 a_tax 를 별도 컬럼으로 두지 않는다.
--        한 주문은 국내 아니면 미국이라 둘 중 하나만 값이 생긴다.
--        컬럼을 나누면 항상 한쪽이 0 인 빈 칸이 절반이고, 합계를 낼 때마다
--        두 컬럼을 더해야 한다. 어느 세금인지는 stock.market_country 로 알 수 있다.
--
--   [미국 SEC Fee 를 이렇게 계산하는 이유]
--     · 미국에는 증권거래세가 없다. 대신 SEC 가 Section 31 수수료를 매도에만 부과한다.
--       요율은 $20.60 per million = 0.0000206 (FY2026 기준, SEC 가 연 1회 조정).
--     · 최소 $0.01 이 있다. 그래서 소액 매도는 정률이 아니라 최소액이 적용된다.
--         손익분기  0.01 / 0.0000206 = $485.44
--         → 485달러 미만 매도는 전부 $0.01 이 붙는다.
--     · 두 계산은 수학적으로 같다 (환율은 공통 인수이므로 어느 쪽으로 계산해도 된다).
--         원화로:  max(gross_krw x 0.0000206, 0.01 x rate)
--         달러로:  rate x max(gross_usd x 0.0000206, 0.01)
--       달러로 계산하고 마지막에 환산하는 쪽이 "최소 $0.01" 이라는 규칙에 더 가깝다.
--     · 실제로는 FINRA TAF(주당 $0.000166, 최대 $8.30)도 붙지만 넣지 않는다.
--       주 수 기반이라 계산 축이 하나 더 늘어나는데, 금액이 SEC Fee 수준이라
--       교육 효과 대비 복잡도가 크다.
--
--   · 원화 환산 금액은 원 단위 반올림(HALF_UP) 후 정수로 저장한다.
--     gross_amount 를 먼저 반올림하고, 그 값으로 fee·tax 를 계산한 뒤 다시 반올림.
--     원장에 들어가는 금액을 전부 정수로 유지해야 검증식이 정확히 맞는다.
--   · 요율은 하드코딩하지 말고 설정값(.env)으로 뺀다.
--
--   [예시 1] 삼성전자 10주 @ 241,500  (국내)
--     매수  gross 2,415,000 + fee   242              = 2,415,242 차감
--     매도  gross 2,415,000 - fee   242 - tax 4,830  = 2,409,928 입금
--
--   [예시 2] 엔비디아 2주 @ $182.30, 환율 1,398.50  (미국 · 최소액이 적용되는 경우)
--     gross  $364.60 x 1,398.50 = 509,893
--     fee    509,893 x 0.0001            =     51
--     tax    정률 509,893 x 0.0000206    =  10.50
--            최소 $0.01 x 1,398.50       =  13.98   ← 이쪽이 크므로 14
--     매도   509,893 - 51 - 14 = 509,828 입금
--
--   [예시 3] 엔비디아 20주 @ $182.30  (미국 · 정률이 적용되는 경우)
--     gross  5,098,931 / fee 510 / tax 105  →  5,098,316 입금
--
--   !! 같은 금액이라도 국내 매도세(4,830원)가 미국(50원)의 약 97배다.
--      모의투자라 국내 세율을 실제(0.15%)보다 높게 잡은 탓도 있지만,
--      원래 두 시장의 거래 비용 구조가 이만큼 다르다.
--      이 차이를 화면에 그대로 보여주는 것이 이 서비스의 교육 포인트다.
-- ============================================================================


-- ============================================================================
--  1주차 인증 범위
--
--   회원가입·로그인 화면은 만들되 인증 로직은 1주차에 구현하지 않는다.
--   시드 사용자 1명(user_id = 1)으로 개발하고, 2주차에 로그인을 붙인다.
--     .env  AUTH_ENABLED=false / DEV_FIXED_USER_ID=1
--
--   그래도 users · account 테이블은 지금 만든다.
--   시드로 사용자 1명과 계좌 1개를 넣어두면 나머지 기능이 전부 돌아간다.
-- ============================================================================


-- ────────────────────────────────────────────────────────────────────────────
--  1. 회원
-- ────────────────────────────────────────────────────────────────────────────
CREATE TABLE users (
    user_id       BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    email         VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    nickname      VARCHAR(50)  NOT NULL,
    status        VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE'
                  CHECK (status IN ('ACTIVE','DORMANT','WITHDRAWN')),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);
COMMENT ON TABLE users IS '서비스 회원';


-- ────────────────────────────────────────────────────────────────────────────
--  2. 모의 투자 계좌
--     포트폴리오 초기화 = 기존 계좌 CLOSED + round_no 를 올린 새 계좌 개설.
--     원장·체결내역이 account_id 에 묶여 있으므로 회차가 자동으로 분리된다.
-- ────────────────────────────────────────────────────────────────────────────
CREATE TABLE account (
    account_id    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id       BIGINT       NOT NULL REFERENCES users(user_id),
    round_no      INT          NOT NULL DEFAULT 1,          -- 회차 (초기화 시 +1)
    status        VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE'
                  CHECK (status IN ('ACTIVE','CLOSED')),
    initial_cash  NUMERIC(19,4) NOT NULL,                   -- 지급액 (5,000만원)
    cash_balance  NUMERIC(19,4) NOT NULL,                   -- 전체 예수금
    -- 미체결 주문에 묶인 금액. 주문 접수 시 +, 체결·취소 시 −.
    -- 주문가능금액 = cash_balance - locked_cash 로 "계산"한다 (저장하지 않는다).
    locked_cash   NUMERIC(19,4) NOT NULL DEFAULT 0,
    opened_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    closed_at     TIMESTAMPTZ,
    version       BIGINT       NOT NULL DEFAULT 0,          -- JPA 낙관적 락
    CONSTRAINT uq_account_round UNIQUE (user_id, round_no),
    CONSTRAINT ck_cash_not_negative CHECK (cash_balance >= 0),
    -- 동결액이 잔고를 넘을 수 없다. 애플리케이션 버그가 있어도 DB 가 막는다.
    CONSTRAINT ck_locked_range CHECK (locked_cash >= 0 AND locked_cash <= cash_balance)
);
-- 한 회원당 활성 계좌는 반드시 하나만
CREATE UNIQUE INDEX uq_account_active
    ON account (user_id) WHERE status = 'ACTIVE';

COMMENT ON COLUMN account.round_no IS '포트폴리오 초기화 회차. 이전 회차 기록은 보존된다';
COMMENT ON COLUMN account.locked_cash IS '미체결 주문 동결액. 주문가능금액은 저장하지 않고 cash_balance - locked_cash 로 계산한다';

-- ── 왜 "주문가능금액"이 아니라 "동결액"을 저장하는가 ────────────────────────
--   ① 주문가능금액은 파생값이다. 저장하면 cash_balance 와 어긋날 수 있고,
--      한쪽만 갱신되는 버그는 발견이 매우 어렵다.
--      동결액을 저장하면 주문가능금액은 항상 계산되므로 어긋날 수가 없다.
--   ② "얼마가 묶여 있는가"는 그 자체로 화면에 보여줄 정보다.
--        예수금 50,000,000 / 주문 대기 2,415,242 / 주문가능 47,584,758
--   ③ 검증식이 생긴다.
--        locked_cash = SUM(trade_order.net_amount WHERE status='PENDING')
--      고아 PENDING 이 생기면 이 식이 깨지므로 즉시 잡아낼 수 있다.
--
--   !! 동결액에는 수수료(매도는 세금까지) 를 포함한 net_amount 를 쓴다.
--      gross_amount 만 동결하면 체결 시점에 수수료만큼 부족해진다.


-- ────────────────────────────────────────────────────────────────────────────
--  3. 종목 마스터
--     내부 stock_id 를 정규 식별자로 쓰고, 외부 심볼은 매핑 테이블로 분리한다.
-- ────────────────────────────────────────────────────────────────────────────
CREATE TABLE stock (
    stock_id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    symbol             VARCHAR(20)  NOT NULL,               -- 005930 / AAPL
    market_country     VARCHAR(2)   NOT NULL CHECK (market_country IN ('KR','US')),
    market             VARCHAR(20)  NOT NULL,               -- KOSPI/KOSDAQ/NASDAQ/NYSE
    name               VARCHAR(200) NOT NULL,
    english_name       VARCHAR(200),
    isin_code          VARCHAR(12),
    currency           VARCHAR(3)   NOT NULL,               -- KRW / USD

    -- ── 종목 분류 ───────────────────────────────────────────────────────────
    -- 제외하지 않고 전부 노출한다. 대신 유형별로 프론트가 다른 안내를 보여준다.
    -- (모르는 상품을 숨기면 실전에서 처음 만나게 되므로, 안전한 환경에서 설명한다)

    security_type      VARCHAR(20)  NOT NULL,               -- 토스 원본 (STOCK/ETF/ETN...)

    -- 상품 유형 (배타적, 택 1) — 프론트 분기의 1차 키
    stock_category     VARCHAR(20)  NOT NULL DEFAULT 'INDIVIDUAL'
                       CHECK (stock_category IN ('INDIVIDUAL','PREFERRED','ETF','ETN')),

    -- ETF/ETN 속성 — 프론트 분기의 2차 키
    --   null = 일반주식 / 1.0 = 일반 ETF / 2.0·3.0 = 레버리지 / -1.0·-2.0 = 인버스
    leverage_factor    NUMERIC(4,1),

    -- 배당 속성 (태그) — 개별주에도 ETF 에도 붙을 수 있으므로 유형과 분리
    is_dividend        BOOLEAN      NOT NULL DEFAULT FALSE,
    dividend_yield     NUMERIC(6,4),                        -- 판정 근거값 (연 배당수익률)

    is_common_share    BOOLEAN,                             -- 토스 원본. false = 우선주

    shares_outstanding NUMERIC(20,0),
    list_date          DATE,
    delist_date        DATE,

    -- 주문 가능 여부 판정용 (토스 warnings / koreanMarketDetail 에서 갱신)
    is_suspended       BOOLEAN      NOT NULL DEFAULT FALSE, -- 거래정지
    is_liquidation     BOOLEAN      NOT NULL DEFAULT FALSE, -- 정리매매
    is_warned          BOOLEAN      NOT NULL DEFAULT FALSE, -- 투자경고/위험
    listing_status     VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',

    is_ranked          BOOLEAN      NOT NULL DEFAULT FALSE, -- 거래대금 상위 100 포함 여부
    rank_no            INT,                                 -- 랭킹 순위 (미포함 시 NULL)
    -- 최근 1주 누적 거래대금(원화 환산). 랭킹 정렬 기준이자 커서의 1차 키.
    --   화면에 그대로 표시하므로 "왜 이 순서인지"를 사용자가 이해할 수 있다.
    --   NUMERIC(24,0) 인 이유: 삼성전자 1주 거래대금만 조 단위라 BIGINT 도 되지만,
    --   시장 전체 합계로 확장할 여지를 남긴다.
    trading_amount     NUMERIC(24,0),

    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- 종목 심볼은 대문자로 저장하되, 직접 적재되는 데이터도 대소문자 중복을 만들 수 없게 한다.
CREATE UNIQUE INDEX uq_stock_symbol ON stock (UPPER(symbol), market_country);

-- 랭킹 화면: 국내/해외 탭 + 거래대금 내림차순 + 커서 페이지네이션
--   커서가 (trading_amount, stock_id) 튜플이므로 인덱스도 같은 순서·같은 방향이어야
--   추가 정렬 없이 인덱스만 훑는다.
--   is_ranked는 상위 100종목뿐 아니라 보유로 인해 시세 수집 대상인 종목도
--   포함할 수 있고, 이 경우 trading_amount가 NULL일 수 있다.
--   랭킹 쿼리의 trading_amount IS NOT NULL 조건과 맞춰 NULL 행은 인덱스에서도 제외한다.
CREATE INDEX ix_stock_rank
    ON stock (market_country, trading_amount DESC, stock_id DESC)
    WHERE is_ranked
      AND trading_amount IS NOT NULL;

-- 검색: 종목명 부분일치 (pg_trgm 확장 필요)
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE INDEX ix_stock_name_trgm ON stock USING gin (name gin_trgm_ops);
CREATE INDEX ix_stock_symbol_prefix ON stock (upper(symbol) varchar_pattern_ops);

COMMENT ON COLUMN stock.stock_category IS '상품 유형(배타적). 프론트가 유형별 안내 문구를 고르는 1차 키';
COMMENT ON COLUMN stock.leverage_factor IS 'null=일반주식, 1.0=일반ETF, 2.0/3.0=레버리지, -1.0/-2.0=인버스. 프론트 분기 2차 키';
COMMENT ON COLUMN stock.is_dividend IS '배당 속성(태그). 유형과 독립이라 개별주에도 ETF 에도 붙을 수 있다';
COMMENT ON COLUMN stock.rank_no IS '거래대금 랭킹 순위(1~100). 화면 표시 전용 — 커서로 쓰지 말 것';
COMMENT ON COLUMN stock.trading_amount IS '최근 1주 누적 거래대금. 랭킹 정렬 기준이자 커서의 1차 키';
COMMENT ON COLUMN stock.is_ranked IS '수집 대상 여부. 랭킹 100위 이내이거나 보유자가 있는 종목';

-- ── 종목 마스터 · 유니버스 정책 (확정) ──────────────────────────────────────
--
--   [매주 월요일 07:00 — 전체 종목 마스터 갱신]
--     ① GET /api/v1/stocks/all  × 마켓 7개  → 전 종목 심볼 (7콜, 약 7초)
--          market: KOSPI · KOSDAQ · NYSE · NASDAQ · AMEX · KR_ETC · US_ETC
--          필터  : status=ACTIVE (상장폐지 제외)
--                  commonShare=true (우선주 제외 — 원하면)
--                  securityType (STOCK/ETF/ETN/REIT…)
--          페이지네이션 없이 한 번에 반환. NASDAQ 약 2,800건 gzip 30KB.
--          Rate Limits Group: STOCK_ALL (초당 1회)
--
--     ② GET /api/v1/stocks  배치 200개씩   → 상세 (8,500종목이면 43콜, 약 9초)
--          종목명·통화·ISIN·securityType·isCommonShare·leverageFactor·
--          상장주식수·상장일 + koreanMarketDetail(거래정지·정리매매)
--
--   [매주 월요일 08:00 — 국내 거래대금 상위 100 선정]   토스 1콜
--     ③ GET /api/v1/rankings?market=KR
--          MARKET_TRADING_AMOUNT · duration=1w · excludeInvestmentCaution=true
--          count 최대 100 이므로 1콜로 완결된다.
--          → is_ranked · rank_no · trading_amount 갱신
--          응답이 빈 배열이면 갈아엎지 말고 지난주 유니버스를 유지할 것.
--     ④ 신규 편입 종목의 prev_close(랭킹의 basePrice) · 일봉 백필
--
--   [매주 월요일 21:00 — 미국 거래대금 상위 100 선정]   토스 1콜
--     ③④ 와 동일한 처리를 market=US 로 수행한다.
--     · 미국장 시작(22:30) 1시간 30분 전이라 새 유니버스로 첫 수집을 시작할 수 있다.
--     · 국내와 시각을 나누는 이유: 각 시장의 "장 시작 직전"에 맞춰야
--       그 주의 거래대금 순위가 가장 최신이기 때문이다.
--
--   [왜 07:00 → 08:00 → 21:00 으로 나누는가]
--     · ①② 가 실패하면 심볼이 없어서 ③ 도 무의미하다. 순서 의존이 있다.
--     · 1시간 간격을 두면 07:00 실패를 알아채고 08:00 전에 손쓸 여유가 생긴다.
--     · 08:00 까지 끝나면 국내장 시작(09:00) 1시간 전에 모든 준비가 완료된다.
--
--   [수집·거래 대상]
--     국내 100 + 해외 100 = 총 200종목.
--     랭킹에서 빠져도 "보유자가 있는 종목"은 수집 대상에서 제외하지 않는다
--     (빠지면 마이페이지 평가금액이 그 시점에 멈춘다).
--
--   [그 외 전 종목]
--     검색·상세 조회는 가능하되 전일 종가만 표시하고 거래는 불가.
--     · quote_snapshot 이 비어 있으므로 상세를 여는 순간 /prices 와 /candles 를
--       함께 호출해 채우고 UPSERT 한다. 한 번 조회된 종목은 다음부터 DB 에서 나간다.
--     · 8,500종목을 매일 도는 배치는 만들지 않는다. 대부분 아무도 안 보는 종목이다.
--     · 검색 결과 목록에 가격을 함께 보여주려면 20건을 /prices 배치 1콜로 받는다.
--       종목마다 따로 부르면 20콜이 되어 rate limit 에 걸린다.
--
--   [시세 제공 규칙 — 판정 기준은 "그 종목의 시장이 열려 있는가"]
--     보는 사람의 시각이 아니라 종목이 속한 시장 기준이다.
--     한국 낮에 엔비디아를 열면 미국장이 닫혀 있으므로 전일 종가가 나간다.
--
--       해당 시장 정규장 + 상위 100 → 5초 실시간 주가 + 1분봉(온디맨드 60초 캐시)
--       그 외 모든 경우            → 전일 종가        + 마지막 장 분봉(온디맨드)
--
--     차트는 어느 경우에도 그려진다. 빈 차트는 "고장난 화면"으로 읽히므로
--     거래 불가와 조회 불가를 반드시 분리할 것.

-- 종목 마스터 갱신 배치에서 실행할 분류 판정
--   -- 1) 상품 유형
--   UPDATE stock SET stock_category =
--     CASE WHEN security_type = 'ETN'          THEN 'ETN'
--          WHEN security_type = 'ETF'          THEN 'ETF'
--          WHEN is_common_share = FALSE        THEN 'PREFERRED'
--          ELSE 'INDIVIDUAL' END;
--   -- 2) 배당 속성 (임계값은 팀에서 결정. 예: 연 3% 이상)
--   UPDATE stock SET is_dividend = (dividend_yield >= 0.03)
--    WHERE dividend_yield IS NOT NULL;
--
-- 프론트 분기 예시
--   INDIVIDUAL                    → "개별주"
--   INDIVIDUAL + is_dividend      → "개별주" + "배당주" 뱃지
--   PREFERRED                     → "우선주"
--   ETF  + leverage_factor = 1.0  → "ETF"
--   ETF  + leverage_factor >= 2.0 → "레버리지 ETF" + 변동성 경고
--   ETF  + leverage_factor <  0   → "인버스 ETF"  + 구조 설명


-- ────────────────────────────────────────────────────────────────────────────
--  4. 외부 소스별 심볼 매핑 (소스 교체/추가 대비)
-- ────────────────────────────────────────────────────────────────────────────
CREATE TABLE stock_external_id (
    stock_id    BIGINT      NOT NULL REFERENCES stock(stock_id) ON DELETE CASCADE,
    source      VARCHAR(20) NOT NULL,        -- TOSS / DART / FINNHUB ...
    external_id VARCHAR(50) NOT NULL,
    PRIMARY KEY (stock_id, source),
    CONSTRAINT uq_source_external UNIQUE (source, external_id)
);


-- ────────────────────────────────────────────────────────────────────────────
--  5. 현재가 스냅샷 — 전 종목 1행씩, 스케줄러가 UPDATE 한다 (이력 쌓지 않음)
--
--   [전 종목을 담는다 — 약 8,500행 고정]
--     상위 200종목 (국내 100 + 미국 100)
--       · 해당 시장 정규장 중 5초마다 last_price 갱신, quote_at = 방금 전
--     나머지 약 8,300종목
--       · 일 1회 last_price = 전일 종가, quote_at = 전일 장 마감 시각
--
--   [이렇게 하면 화면 로직이 하나로 통일된다]
--     상세 페이지는 종목이 상위 100 이든 아니든 항상 이 테이블만 조회하고,
--     quote_at 을 보고 문구만 바꾼다.
--       quote_at 이 지금  → "241,500  12:36:59 기준 · 실시간"
--       quote_at 이 어제  → "241,500  8월 11일 종가 · 거래 불가"
--     "이 종목이 상위 100 인가?"를 화면이 알 필요가 없어진다.
--
--   [거래 가능 판정은 별도다]
--     stock.is_ranked = true  AND  해당 시장 정규장 중  AND  거래정지 아님
-- ────────────────────────────────────────────────────────────────────────────
CREATE TABLE quote_snapshot (
    stock_id     BIGINT        PRIMARY KEY REFERENCES stock(stock_id),
    last_price   NUMERIC(19,4) NOT NULL,
    -- 전일 기준가. 랭킹 API 의 price.basePrice 또는 전일 일봉 종가로 채운다.
    -- 등락률 = (last_price - prev_close) / prev_close 로 계산하며 별도 저장하지 않는다.
    prev_close   NUMERIC(19,4),
    -- 상·하한가. 전일 종가 기준으로 정해져 하루 동안 고정.
    -- !! 미국 종목은 가격제한이 없어 NULL 이다. 주문 검증 시 KR 일 때만 확인할 것.
    upper_limit  NUMERIC(19,4),
    lower_limit  NUMERIC(19,4),
    currency     VARCHAR(3)    NOT NULL,
    quote_at     TIMESTAMPTZ   NOT NULL,     -- 토스가 알려준 시세 기준 시각
    collected_at TIMESTAMPTZ   NOT NULL DEFAULT now(),  -- 우리가 받은 시각
    CONSTRAINT ck_price_positive CHECK (last_price > 0)
);
COMMENT ON COLUMN quote_snapshot.quote_at IS '주문 시 유효시간(staleness) 검증 + 화면의 "언제 기준" 표시';
COMMENT ON COLUMN quote_snapshot.upper_limit IS '미국 종목은 NULL. 상하한가 검증은 market_country=KR 에만 적용';

-- ── 종가 갱신은 3단계다 ─────────────────────────────────────────────────────
--   15:30  장 마감 → 스케줄러 정지
--          last_price 에 마지막 값(= 그날 종가)이 그대로 남는다.  ← 코드 없음, 자동
--   15:40  일봉 수집 → daily_candle.close_price 확정  (마감 10분 후)  ← 배치
--   08:50  다음날 장 시작 직전
--          prev_close ← 전일 close_price                           ← 배치
--
--   즉 "장 마감 시 종가 반영"은 아무것도 안 해도 이루어진다.
--   실제 배치는 두 개(일봉 수집 · prev_close 갱신)뿐이다.
--
-- ── MVP 동작 매트릭스 ───────────────────────────────────────────────────────
--                    국내 상위 100     미국 상위 100     그 외 전 종목
--   09:00~15:30      5초 실시간 · 거래O  전일 종가 · 거래X  전일 종가 · 거래X
--   22:30~05:00 (*)  전일 종가 · 거래X   5초 실시간 · 거래O  전일 종가 · 거래X
--   그 외            전일 종가 · 거래X   전일 종가 · 거래X   전일 종가 · 거래X
--
--   조회는 언제나 전 종목 가능. 거래는 "해당 시장 정규장 + 상위 100" 에만 허용.
--
--   (*) !! 미국 정규장 시각은 서머타임에 따라 1시간 이동한다.
--       서머타임(3월 둘째 일요일~11월 첫째 일요일)  22:30 ~ 05:00 KST
--       표준시                                      23:30 ~ 06:00 KST
--       절대 하드코딩하지 말고 /market-calendar/US 의 regularMarket 세션을 쓸 것.
--       (응답이 KST 기준으로 오므로 변환도 필요 없다)
--
-- ── prev_close 갱신 ─────────────────────────────────────────────────────────
--   전일 daily_candle.close_price 를 복사한다. 시장마다 마감 시각이 다르므로
--   배치도 시장별로 나눈다.
--
--   국내 (매일 08:50, 장 시작 10분 전)
--     UPDATE quote_snapshot q
--        SET prev_close = c.close_price
--       FROM stock s
--       JOIN daily_candle c ON c.stock_id = s.stock_id
--                          AND c.trade_date = (직전 거래일)
--      WHERE s.stock_id = q.stock_id AND s.market_country = 'KR';
--
--   미국 (매일 22:00, 정규장 시작 30분 전) — market_country = 'US'
--
--   폴백: 일봉 수집이 실패했다면 last_price 를 복사한다.
--         장 마감 시점의 last_price 가 곧 그날 종가이기 때문이다.
--   신규 편입 종목: 랭킹 응답의 price.basePrice 로 초기화한다.
--
--   !! 갱신 시점이 "마감 직후"가 아니라 "다음 장 시작 직전"인 이유
--      마감 직후에 갱신하면 prev_close = last_price 가 되어
--      장외 시간 내내 등락률이 0% 로 표시된다.


-- ────────────────────────────────────────────────────────────────────────────
--  6. 일봉 — 차트 + prev_close 의 원천
--
--   · 국내 15:40, 미국 06:10 KST에 실행하며 시장 캘린더로 휴장일을 제외한다.
--   · 여기서 확정된 close_price 가 다음 장 시작 직전
--     quote_snapshot.prev_close 로 복사되어 등락률의 기준이 된다.
--   · 200종목 × 250거래일 = 연 5만 행(약 3MB). 10년 쌓아도 50만 행이다.
--   · 주봉·월봉은 API 가 주지 않는다(interval 은 1m·1d 뿐). 이 테이블을 집계해서 만든다.
--
--   [이 테이블을 TimescaleDB 하이퍼테이블로 지정한다]   ← 확정
--     · 파티션 키는 trade_date. PK 가 이미 (stock_id, trade_date) 라
--       "하이퍼테이블은 PK 에 파티션 키를 포함해야 한다"는 요건을 그대로 만족한다.
--     · 청크는 1년 단위로 자른다. 연 5만 행이면 하루·한 달 단위로 자를 이유가 없고,
--       청크가 너무 잘게 쪼개지면 플래너 오버헤드만 늘어난다.
--
--     !! 솔직히 말하면 이 규모에서 성능상 이득은 거의 없다.
--        일반 테이블 + (stock_id, trade_date) 인덱스로도 50만 행은 순식간에 훑는다.
--        그럼에도 지정하는 실질적 이유는 [연속 집계]다 —
--          주봉·월봉을 별도 테이블이나 배치 없이 뷰 하나로 파생할 수 있다.
--          time_bucket('1 week', trade_date) 로 묶으면 끝이고,
--          일봉이 새로 들어올 때마다 정책이 알아서 갱신한다.
--        성능이 아니라 "파생 데이터를 코드로 관리하지 않는다"가 명분이다.
--
--     !! 수정주가가 소급될 때는 연속 집계를 수동으로 새로고침해야 한다.
--        액면분할이 일어나면 과거 일봉이 통째로 조정되는데, 연속 집계는
--        원본이 UPDATE 된 것을 자동으로 따라가지 않는다.
--          CALL refresh_continuous_aggregate('candle_1w', NULL, NULL);
--        분할은 드물게 일어나므로 그때만 수동 실행하면 된다.
--
--     !! 하이퍼테이블은 다른 테이블에서 FK 로 참조받을 수 없다.
--        daily_candle 을 참조하는 테이블은 없으므로 문제되지 않는다.
--        (daily_candle 이 stock 을 참조하는 방향은 허용된다)
--   · 수정주가(adjusted) 적용 여부를 팀에서 정하고 고정할 것.
--     중간에 바꾸면 이미 저장된 과거 데이터와 어긋난다.
-- ────────────────────────────────────────────────────────────────────────────
CREATE TABLE daily_candle (
    stock_id     BIGINT        NOT NULL REFERENCES stock(stock_id),
    -- 토스 Candle.timestamp 는 '봉 시작 시각'(타임스탬프)이다.
    -- 거래소 현지 날짜(KR=Asia/Seoul, US=America/New_York)로 저장한다.
    trade_date   DATE          NOT NULL,
    open_price   NUMERIC(19,4) NOT NULL,
    high_price   NUMERIC(19,4) NOT NULL,
    low_price    NUMERIC(19,4) NOT NULL,
    close_price  NUMERIC(19,4) NOT NULL,
    volume       NUMERIC(20,0),
    -- 하이퍼테이블은 PK·UNIQUE 에 파티션 키(trade_date)를 반드시 포함해야 한다
    PRIMARY KEY (stock_id, trade_date)
);

COMMENT ON TABLE daily_candle IS 'TimescaleDB 하이퍼테이블. 주봉·월봉은 연속 집계로 파생한다';

-- ── TimescaleDB 하이퍼테이블 지정은 별도 파일에서 한다 ─────────────────────
--
--   timescale.sql 을 이 파일 다음에 실행하세요.
--     docker compose 가 01-schema.sql → 02-timescale.sql 순서로 돌려줍니다.
--
--   !! 여기에 넣을 수 없는 이유
--      이 파일은 BEGIN ... COMMIT 으로 감싸여 있는데,
--      CREATE EXTENSION timescaledb 는 트랜잭션 블록 안에서 실행할 수 없습니다.
--      (다른 확장과 달리 백그라운드 워커를 띄우기 때문)
--      여기 넣으면 스키마 생성 전체가 통째로 실패합니다.
--
--   !! 확장이 없는 환경(관리형 DB 등)에서는 timescale.sql 을 그냥 건너뛰세요.
--      daily_candle 이 일반 테이블로 남을 뿐 나머지 기능은 전부 그대로 동작합니다.
--      PK 가 이미 (stock_id, trade_date) 라 나중에 언제든 전환할 수 있습니다.



-- ────────────────────────────────────────────────────────────────────────────
--  7. 환율 — 아래 "8-1. 환율" 항목으로 통합했다.
--     (mid_rate · collected_at 이 추가된 쪽이 최신 정의다)
-- ────────────────────────────────────────────────────────────────────────────


-- ────────────────────────────────────────────────────────────────────────────
--  8. (선택) 장 운영 캘린더 — 토스 market-calendar 를 하루 1회 적재.
--     서머타임·수능일·임시휴장이 있으므로 절대 하드코딩하지 않는다.
--
--   [테이블을 만들지, 메모리 캐시로 끝낼지]
--     · 1주차 기능만 보면 메모리 캐시로 충분하다. 앱 기동 시 한 번 받아
--       주문 가능 시간 판정 · 수집기 on/off · 화면 문구 분기에 쓰면 끝이다.
--     · 다만 테이블로 두면 "그날 장이 왜 안 열렸는지"가 남고, 배치가 실패해도
--       마지막 값으로 폴백할 수 있다. 5개 컬럼짜리 테이블이라 비용도 거의 없다.
--     · ERD 문서는 메모리 캐시 기준으로 설명되어 있다. 팀에서 한쪽으로 정하고
--       쓰지 않기로 했다면 이 블록을 통째로 지워도 나머지는 그대로 동작한다.
-- ────────────────────────────────────────────────────────────────────────────
CREATE TABLE market_calendar (
    market_country VARCHAR(2)  NOT NULL CHECK (market_country IN ('KR','US')),
    trade_date     DATE        NOT NULL,
    is_open        BOOLEAN     NOT NULL,
    open_at        TIMESTAMPTZ,               -- 정규장 시작 (UTC 저장)
    close_at       TIMESTAMPTZ,               -- 정규장 종료
    PRIMARY KEY (market_country, trade_date)
);


-- ────────────────────────────────────────────────────────────────────────────
--  6-1. 분봉 — 실시간 차트용 시계열
--
--   [1주차는 온디맨드로 채운다 — 스케줄러 상시 수집은 2주차]
--     · 사용자가 종목 상세를 열 때 /candles?interval=1m 을 호출한다.
--     · 받은 봉을 이 테이블에 UPSERT 하고, 60초 동안은 DB 에서 바로 내려준다.
--       테이블이 저장소이자 캐시 역할을 겸한다.
--     · 동시 시청 30명 기준 0.5 req/s → MARKET_DATA_CHART 5 TPS 의 10%.
--       상시 적재(1.67 req/s)보다 오히려 싸다. 아무도 안 보는 종목까지
--       1분마다 긁을 이유가 없기 때문이다.
--
--   [장외 시간이나 다른 나라 종목도 똑같이 동작한다]
--     · 장이 닫힌 종목에 /candles 를 부르면 마지막 장의 분봉이 그대로 온다.
--       한국 낮에 엔비디아를 열면 전일 종가 + 지난 미국장 분봉이 보인다.
--     · 화면은 quote_at 과 장 운영 캘린더로 "실시간 / 종가" 문구만 바꾸면 되고
--       차트 자체는 분기가 필요 없다.
--
--   [한 번에 받을 수 있는 봉은 200개다]
--     · 국내 정규장 5시간 30분 = 330분 → 하루치를 다 받으려면 before 로 2회 호출.
--     · 1주차 차트가 "최근 200분"이면 1콜로 끝난다.
--       기본은 1콜, 전체 보기를 누를 때만 2콜 쓰는 편이 단순하다.
--     · !! before 는 inclusive("이 시각과 같거나 이전인 봉")로 보이나 실측이 필요하다.
--       마감 동시호가(15:20~15:30) 결과가 어느 봉에 담기는지,
--       15:30 봉이 존재하는지(없으면 330개가 아니라 329개) 확인할 것.
--
--   [2주차에 스케줄러 상시 적재로 전환한다]
--     · 체결 엔진이 "그 1분 안에 지정가에 닿았는가"를 판정해야 하는데,
--       사용자가 차트를 안 봐도 과거 봉이 필요하기 때문이다.
--       low <= 지정가(매수) / high >= 지정가(매도)
--     · 정규장 중 1분마다 해당 시장 상위 100종목 → 1.67 req/s (한도의 8.3%).
--       단건 조회이므로 1분 안에 고르게 분산시킨다(0.6초 간격).
--       한 번에 몰아 보내면 순간 버스트로 429 가 난다.
--     · 테이블 구조는 그대로 두고 채우는 방식만 바뀐다.
--
--   [TimescaleDB 를 쓴다면 명분은 연속 집계다]
--     · 1분봉에서 5분봉·15분봉을 테이블 추가 없이 뷰로 파생할 수 있다.
--     · 다만 일봉까지 여기서 만들려 하지 말 것. 액면분할 시 과거 일봉이 소급
--       조정되는데 연속 집계는 그것을 반영하지 못한다. 일봉은 API 의
--       adjusted=true 값을 그대로 쓰는 것이 맞다.
-- ────────────────────────────────────────────────────────────────────────────
CREATE TABLE minute_candle (
    stock_id    BIGINT        NOT NULL REFERENCES stock(stock_id),
    candle_at   TIMESTAMPTZ   NOT NULL,     -- 봉 시작 시각 (응답의 timestamp)
    open_price  NUMERIC(19,4) NOT NULL,
    high_price  NUMERIC(19,4) NOT NULL,
    low_price   NUMERIC(19,4) NOT NULL,
    close_price NUMERIC(19,4) NOT NULL,
    volume      NUMERIC(20,0),
    -- 하이퍼테이블은 PK·UNIQUE 에 파티션 키(candle_at)를 반드시 포함해야 한다
    PRIMARY KEY (stock_id, candle_at)
);

COMMENT ON TABLE minute_candle IS '1주차는 상세 진입 시 온디맨드 적재 + 60초 캐시. 상시 수집은 2주차';


-- ── minute_candle 하이퍼테이블 — 2주차 상시 적재를 시작할 때 켤 것 ──────────
--
--   1주차에는 온디맨드 캐시라 데이터가 얼마 없다. 상시 적재로 전환하면
--   연 977만 행이 되므로 그때 아래를 실행한다.
--   확장은 daily_candle 쪽에서 이미 만들었으므로 다시 만들 필요가 없다.
--
--   주의 1) 하이퍼테이블은 "다른 테이블에서 FK 로 참조받을 수 없다".
--           (하이퍼테이블이 다른 테이블을 참조하는 것은 가능하므로 위 stock 참조는 OK)
--   주의 2) 압축·연속집계는 Apache 판이 아니라 TSL 라이선스 기능이다.
--           학습·포트폴리오 용도는 무관하지만 서비스화 시 조항 확인이 필요하다.
--   주의 3) 관리형 DB(AWS RDS 등)는 대체로 미지원이라 배포 방식에 영향이 있다.
--
-- -- 하루 단위 청크로 분할 (일봉은 1년, 분봉은 1일. 행 수가 200배 차이 난다)
-- SELECT create_hypertable('minute_candle', 'candle_at',
--                          chunk_time_interval => INTERVAL '1 day');
--
-- -- 7일 지난 청크는 컬럼 압축 (90% 이상 축소)
-- ALTER TABLE minute_candle SET (
--     timescaledb.compress,
--     timescaledb.compress_segmentby = 'stock_id',
--     timescaledb.compress_orderby   = 'candle_at DESC'
-- );
-- SELECT add_compression_policy('minute_candle', INTERVAL '7 days');
--
-- -- 1년 지난 데이터는 삭제 (일봉이 남아 있으므로 손실 없음)
-- SELECT add_retention_policy('minute_candle', INTERVAL '1 year');
--
-- -- 5분봉 자동 파생 — 1분봉만 쌓으면 나머지는 뷰로 해결된다
-- CREATE MATERIALIZED VIEW candle_5m WITH (timescaledb.continuous) AS
-- SELECT stock_id,
--        time_bucket('5 minutes', candle_at)      AS bucket,
--        first(open_price, candle_at)             AS open_price,
--        max(high_price)                          AS high_price,
--        min(low_price)                           AS low_price,
--        last(close_price, candle_at)             AS close_price,
--        sum(volume)                              AS volume
--   FROM minute_candle
--  GROUP BY stock_id, bucket;
--
-- SELECT add_continuous_aggregate_policy('candle_5m',
--        start_offset => INTERVAL '1 day',
--        end_offset   => INTERVAL '1 minute',
--        schedule_interval => INTERVAL '1 minute');


-- ────────────────────────────────────────────────────────────────────────────
--  8-1. 환율 — (시점, 환율) 을 쌓아 그래프를 그린다
--
--   [일반 테이블이다. 하이퍼테이블로 만들지 않는다]   ← 확정
--     · 매시 정각 적재라 통화쌍 하나당 연 6,000행 남짓이다.
--       10년을 모아도 6만 행이라 청크로 자를 이유가 전혀 없다.
--     · 파생할 것도 없다. 일간·주간 그래프가 필요하면 그냥 GROUP BY 하면 된다.
--       연속 집계를 걸 만큼 무거운 쿼리가 아니다.
--     · BIGINT 대리키(exchange_rate_id)를 그대로 쓸 수 있다는 점도 있다.
--       하이퍼테이블이면 PK 에 파티션 키(rate_at)를 넣어야 해서
--       (exchange_rate_id, rate_at) 같은 어색한 복합 PK 가 된다.
--
--   [수집 주기: 매시 정각 (확정)]
--     · 하루 24콜. 환율은 하루 0.3~0.5% 정도만 움직여서 분 단위로 쌓으면
--       노이즈만 늘고 그래프는 같아 보인다.
--     · 시간별로 쌓아두면 일간·주간·월간 그래프를 전부 여기서 집계할 수 있다.
--       반대로 일별로만 집계해두면 "시간별로 보고 싶다"가 나왔을 때 복구가 안 된다.
--
--   [주말·휴장에도 그냥 돌린다]
--     · 외환시장이 쉬면 토스가 같은 validFrom 을 계속 반환하므로
--       UNIQUE (base, quote, rate_at) 에 걸려 자동으로 걸러진다.
--       INSERT ... ON CONFLICT DO NOTHING 으로 적재하면
--       스케줄러에 주말 조건을 넣을 필요가 없다.
--     · 그래서 실제 적재량은 평일 위주로 연 6,000행 남짓이다.
--     · 한 시간 건너뛰어도 그래프에 점 하나가 빠질 뿐이니 재시도하지 않는다.
--       연속 실패만 알림으로 잡는다.
--
--   [두 경로를 구분할 것]
--     · 그래프용 이력  → 이 테이블 (매시 정각 적재)
--     · 체결용 현재 환율 → 메모리 캐시 (TTL 1분). 캐시가 비었으면 API 조회,
--                          그것도 실패하면 이 테이블의 최신 행으로 폴백한다
--                          (최대 1시간 오래되지만 환율 변동은 0.02% 수준).
--   · append-only 로 쌓이지만 규모가 작아서 평범한 테이블로 충분하다.
--     (시세는 quote_snapshot 에 UPDATE 하므로 애초에 이력이 없다)
-- ────────────────────────────────────────────────────────────────────────────
CREATE TABLE exchange_rate (
    exchange_rate_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    base_currency    VARCHAR(3)    NOT NULL,   -- USD
    quote_currency   VARCHAR(3)    NOT NULL,   -- KRW
    rate             NUMERIC(19,6) NOT NULL,   -- 매수 환율 (응답의 rate)
    mid_rate         NUMERIC(19,6),            -- 매매기준율 (응답의 midRate)
    rate_at          TIMESTAMPTZ   NOT NULL,   -- 환율 시점 = 응답의 validFrom
    collected_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uq_rate_point UNIQUE (base_currency, quote_currency, rate_at),
    CONSTRAINT ck_rate_positive CHECK (rate > 0)
);

-- 그래프 조회: 통화쌍 + 기간 범위 스캔
CREATE INDEX ix_rate_series
    ON exchange_rate (base_currency, quote_currency, rate_at DESC);

COMMENT ON COLUMN exchange_rate.rate_at IS '응답의 validFrom 을 그대로 쓴다. collected_at(우리가 받은 시각)과 구분할 것';
COMMENT ON COLUMN exchange_rate.rate IS '실제 매수 시 적용되는 환율. mid_rate 와의 차이가 환전 스프레드';
COMMENT ON COLUMN exchange_rate.mid_rate IS '은행간 매매기준율. 일반적인 "환율"로 표시할 때 사용';

-- 환율 적용 정책 (팀에서 합의하고 코드에 고정할 것)
--   체결 시 환산    → rate      (매수 환율, 현실적)
--   평가금액 환산   → mid_rate  (중립적)
--   그래프 표시     → mid_rate
--   ※ MVP 에서 단순화하려면 전부 mid_rate 로 통일해도 무방
--
-- 일별 그래프용 집계 예시 (KST 기준 일별 종가)
--   SELECT (rate_at AT TIME ZONE 'Asia/Seoul')::date AS d,
--          (array_agg(mid_rate ORDER BY rate_at DESC))[1] AS close_rate,
--          MIN(mid_rate) AS low, MAX(mid_rate) AS high
--     FROM exchange_rate
--    WHERE base_currency = 'USD' AND quote_currency = 'KRW'
--      AND rate_at >= now() - INTERVAL '1 month'
--    GROUP BY 1 ORDER BY 1;


-- ────────────────────────────────────────────────────────────────────────────
--  9. 주문 (MVP 는 시장가 즉시 체결이므로 주문 = 체결)
--     지정가를 도입하면 trade_execution 을 분리한다.
-- ────────────────────────────────────────────────────────────────────────────
CREATE TABLE trade_order (
    order_id        BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    account_id      BIGINT        NOT NULL REFERENCES account(account_id),
    stock_id        BIGINT        NOT NULL REFERENCES stock(stock_id),

    client_order_id UUID          NOT NULL,   -- 멱등성 키 (중복 클릭 방지)
    side            VARCHAR(4)    NOT NULL CHECK (side IN ('BUY','SELL')),
    order_type      VARCHAR(10)   NOT NULL DEFAULT 'MARKET',
    quantity        NUMERIC(19,6) NOT NULL CHECK (quantity > 0),

    -- PENDING : 접수 완료, 자금(또는 수량) 동결됨. 아직 체결 전.
    -- FILLED  : 체결 완료. 동결 해제 + 실제 출금/입금 확정.
    -- REJECTED: 검증 단계에서 거절. 동결하지 않는다.
    -- CANCELED: 사용자가 취소. 동결 해제.  EXPIRED: 타임아웃 자동 해제.
    status          VARCHAR(12)   NOT NULL
                    CHECK (status IN ('PENDING','FILLED','REJECTED','CANCELED','EXPIRED')),
    reject_reason   VARCHAR(40),              -- MARKET_CLOSED / STOCK_SUSPENDED /
                                              -- INSUFFICIENT_CASH / STALE_QUOTE /
                                              -- FUTURE_QUOTE ...
    reference_price NUMERIC(19,4),            -- REJECTED 판정에 사용한 기준 가격
    quote_at        TIMESTAMPTZ,              -- 체결 또는 거절 판정에 사용한 시세 시각
    exchange_rate   NUMERIC(19,6),            -- 체결 또는 거절 판정에 사용한 환율

    -- 체결 결과 (status = FILLED 일 때만 채워짐)
    executed_price  NUMERIC(19,4),            -- 체결 단가 (종목 통화 기준)
    gross_amount    NUMERIC(19,4),            -- 체결 금액 (원화 환산)
    fee             NUMERIC(19,4) DEFAULT 0,  -- 수수료
    -- 매도 시에만 발생. 국내(k_tax)와 미국(a_tax)의 계산식이 다르지만,
    -- 한 주문은 둘 중 하나이므로 컬럼은 하나면 된다.
    -- 요율이 아니라 "적용된 금액"이 들어간다.
    tax             NUMERIC(19,4) DEFAULT 0,
    net_amount      NUMERIC(19,4),            -- 실제 예수금 증감액

    ordered_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uq_account_client_order UNIQUE (account_id, client_order_id)
);
CREATE INDEX ix_order_history ON trade_order (account_id, ordered_at DESC);

COMMENT ON COLUMN trade_order.quote_at IS '체결 또는 거절 판정에 사용한 시세의 기준 시각';
COMMENT ON COLUMN trade_order.reference_price IS 'REJECTED 판정 당시 사용한 종목 통화 기준 가격';
COMMENT ON COLUMN trade_order.exchange_rate IS '나중에 환차손익을 분리하려면 반드시 필요';
COMMENT ON COLUMN trade_order.tax IS '적용된 세금 "금액". 요율은 DB 가 아니라 .env 에 있다 (K_TAX_RATE / A_TAX_RATE)';
COMMENT ON COLUMN trade_order.fee IS '적용된 수수료 "금액". 요율은 .env 의 FEE_RATE';


-- ────────────────────────────────────────────────────────────────────────────
--  10. 거래 원장 — append only. 예수금이 움직인 모든 사건을 기록한다.
--
--  [항목은 세 가지뿐이다]  INITIAL_DEPOSIT / BUY / SELL
--    수수료와 세금은 별도 항목으로 쪼개지 않고 BUY / SELL 의 amount 에 포함한다.
--      매수  amount = -(gross_amount + fee)
--      매도  amount = +(gross_amount - fee - tax)
--    즉 amount 는 언제나 trade_order.net_amount 와 같다 (부호만 다름).
--    수수료 총액을 따로 집계할 일이 없다면 이쪽이 훨씬 단순하고, 목록도 절반으로 줄어든다.
--    나중에 수수료 통계가 필요해지면 trade_order.fee 를 SUM 하면 되므로 정보가 사라지지도 않는다.
--
--  [포트폴리오 초기화에 RESET 항목이 없는 이유]
--    초기화는 기존 계좌를 CLOSED 로 바꾸고 새 계좌를 만드는 일이다.
--    새 계좌에 INITIAL_DEPOSIT 한 줄이 들어가는 것으로 충분하고,
--    이전 회차의 마감 시각은 account.closed_at 에 남는다.
--
--  [검증식]  SUM(ledger_entry.amount) = account.cash_balance   (계좌별)
--    항목이 세 가지뿐이라 이 식이 아주 단순해진다. 테스트로 만들어 두면 좋다.
-- ────────────────────────────────────────────────────────────────────────────
CREATE TABLE ledger_entry (
    entry_id      BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    account_id    BIGINT        NOT NULL REFERENCES account(account_id),
    order_id      BIGINT        REFERENCES trade_order(order_id),  -- 초기금 지급은 NULL
    entry_type    VARCHAR(20)   NOT NULL
                  CHECK (entry_type IN ('INITIAL_DEPOSIT','BUY','SELL')),
    amount        NUMERIC(19,4) NOT NULL,   -- 예수금 증감 (부호 있음, = net_amount)
    balance_after NUMERIC(19,4) NOT NULL,   -- 반영 후 잔액 (정합성 검증용)

    -- 체결 시점 환율. 원화 종목은 1, 미국 종목은 그때의 USD/KRW.
    --   amount 는 이미 원화로 환산된 값이므로 계산에는 쓰이지 않는다.
    --   "이 거래를 얼마짜리 환율로 했는가"를 원장만 보고 알 수 있게 하는 감사 항목이다.
    --   trade_order.exchange_rate 와 같은 값이지만, 원장은 append-only 라
    --   주문 테이블이 나중에 어떻게 바뀌든 이 기록은 그대로 남는다.
    --   1주차에는 화면에 안 써도 좋다. 지금 안 남기면 과거 값은 복원할 수 없다.
    exchange_rate NUMERIC(19,6) NOT NULL DEFAULT 1,

    memo          VARCHAR(200),             -- "삼성전자 10주 @ 241,500 (수수료 포함)"
    occurred_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),

    CONSTRAINT ck_ledger_rate_positive CHECK (exchange_rate > 0)
);
CREATE INDEX ix_ledger_account ON ledger_entry (account_id, occurred_at);
CREATE INDEX ix_ledger_order ON ledger_entry (order_id, entry_id) WHERE order_id IS NOT NULL;

COMMENT ON TABLE ledger_entry IS 'UPDATE/DELETE 금지. 잘못 기록했으면 반대 부호 항목을 새로 넣어 상쇄한다';
COMMENT ON COLUMN ledger_entry.amount IS '수수료·세금 포함. trade_order.net_amount 와 절대값이 같다';
COMMENT ON COLUMN ledger_entry.exchange_rate IS '체결 시점 환율. 원화 종목은 1';


-- ────────────────────────────────────────────────────────────────────────────
--  11. 보유 종목 — 원장에서 파생되는 집계. 계좌+종목당 1행.
-- ────────────────────────────────────────────────────────────────────────────
CREATE TABLE holding (
    holding_id        BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    account_id        BIGINT        NOT NULL REFERENCES account(account_id),
    stock_id          BIGINT        NOT NULL REFERENCES stock(stock_id),
    quantity          NUMERIC(19,6) NOT NULL CHECK (quantity >= 0),
    -- 미체결 매도 주문에 묶인 수량. 예수금 동결과 정확히 같은 원리다.
    -- 10주를 갖고 5주 매도 주문을 세 번 걸면 15주를 팔게 되므로 반드시 필요하다.
    -- 매도 가능 수량 = quantity - locked_quantity 로 계산한다.
    locked_quantity   NUMERIC(19,6) NOT NULL DEFAULT 0,
    avg_buy_price     NUMERIC(19,4) NOT NULL,   -- 이동평균 매입단가 (종목 통화)
    avg_exchange_rate NUMERIC(19,6) NOT NULL DEFAULT 1,  -- 매수 시점 평균 환율
    updated_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uq_holding UNIQUE (account_id, stock_id),
    CONSTRAINT ck_locked_qty CHECK (locked_quantity >= 0 AND locked_quantity <= quantity)
);
CREATE INDEX ix_holding_account ON holding (account_id);


-- ────────────────────────────────────────────────────────────────────────────
--  12. 일별 자산 스냅샷 — 2주차 자산 추이 그래프용.
--      1주차에 배치를 넣지 않으면 과거 데이터를 영원히 만들 수 없다.
-- ────────────────────────────────────────────────────────────────────────────
CREATE TABLE daily_account_snapshot (
    account_id      BIGINT        NOT NULL REFERENCES account(account_id),
    snapshot_date   DATE          NOT NULL,
    cash_balance    NUMERIC(19,4) NOT NULL,
    stock_value     NUMERIC(19,4) NOT NULL,
    total_asset     NUMERIC(19,4) NOT NULL,
    unrealized_pnl  NUMERIC(19,4) NOT NULL,
    PRIMARY KEY (account_id, snapshot_date)
);


-- ────────────────────────────────────────────────────────────────────────────
--  13. (선택) 금융 용어 위키 — 부가기능
-- ────────────────────────────────────────────────────────────────────────────
CREATE TABLE wiki_term (
    term_id     BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    term        VARCHAR(100) NOT NULL UNIQUE,
    summary     VARCHAR(500) NOT NULL,
    body        TEXT,
    source      VARCHAR(20)  NOT NULL DEFAULT 'MANUAL',  -- MANUAL / LLM
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

COMMIT;


-- ============================================================================
--  매수 처리 — 2단계 모델
--
--   MVP(시장가 즉시 체결)는 두 단계를 한 트랜잭션 안에서 연속 실행하며,
--   trade_order 를 PENDING 없이 곧바로 FILLED 로 INSERT 한다.
--   (접수와 체결이 같은 순간이라 중간 상태가 의미 없다)
--
--   지정가를 도입하면 Phase 1 을 PENDING 으로 커밋하고,
--   체결 엔진이 나중에 Phase 2 를 돌린다.
--   스키마를 미리 이렇게 잡아두면 그때 로직만 쪼개면 된다.
-- ============================================================================
--
-- ── Phase 1. 주문 접수 ──────────────────────────────────────────────────────
-- BEGIN;
--   -- 1) 계좌 행 잠금  (동시 주문 시 예수금 이중 차감 방지)
--   SELECT cash_balance, locked_cash FROM account WHERE account_id = ? FOR UPDATE;
--
--   -- 2) 검증
--   --    장 시간 · is_ranked · 거래정지 · 시세 유효시간(15초)
--   --    주문가능금액 = cash_balance - locked_cash  >=  net_amount ?
--
--   -- 3) 자금 동결  (매수 net_amount = gross + fee. gross 만 묶으면 안 된다)
--   UPDATE account SET locked_cash = locked_cash + ? WHERE account_id = ?;
--
--   -- 4) 주문 접수 기록 (account_id + client_order_id 유니크 위반 → 중복 클릭이므로 무시)
--   INSERT INTO trade_order (..., status) VALUES (..., 'PENDING');
-- COMMIT;
--
-- ── Phase 2. 주문 체결 ──────────────────────────────────────────────────────
-- BEGIN;
--   -- 1) 계좌 행 잠금 → 동결 해제 + 실제 출금 확정
--   SELECT ... FROM account WHERE account_id = ? FOR UPDATE;
--   UPDATE account
--      SET locked_cash  = locked_cash  - ?,
--          cash_balance = cash_balance - ?
--    WHERE account_id = ?;
--
--   -- 2) 보유 종목 반영 (이동평균 단가·환율 재계산)
--   --    락 순서는 항상 account → holding. 엇갈리면 데드락이 난다.
--   INSERT INTO holding (...) VALUES (...)
--   ON CONFLICT (account_id, stock_id) DO UPDATE SET ...;
--
--   -- 3) 주문 상태 전이  (조건부 UPDATE 로 경합을 막는다)
--   UPDATE trade_order SET status = 'FILLED', ...
--    WHERE order_id = ? AND status = 'PENDING';
--   --   영향 행이 0 이면 이미 취소됐거나 다른 워커가 가져간 것 → 롤백
--
--   -- 4) 원장 기록 (append only) — 수수료를 쪼개지 않고 한 줄로 남긴다
--   INSERT INTO ledger_entry
--          (account_id, order_id, entry_type, amount, balance_after, exchange_rate, memo)
--   VALUES (?, ?, 'BUY', -2415242, 47584758, 1, '삼성전자 10주 @ 241,500 (수수료 포함)');
-- COMMIT;
--
-- ── 매도는 대칭이다 ─────────────────────────────────────────────────────────
--   Phase 1  holding.locked_quantity += 수량   (매도 가능 수량 검증 후)
--   Phase 2  quantity -= 수량, locked_quantity -= 수량, 예수금 입금
--            avg_buy_price 는 건드리지 않는다 (평가손익의 기준)
--
-- ── 지정가 도입 시 반드시 필요한 것 ─────────────────────────────────────────
--   Phase 1 커밋 직후 장애가 나면 동결액이 영원히 안 풀린다.
--   타임아웃 기반 자동 해제 배치를 만들 것.
--     UPDATE trade_order SET status='EXPIRED'
--      WHERE status='PENDING' AND ordered_at < now() - INTERVAL '5 min';
--     → 해당 금액만큼 locked_cash 를 되돌린다.
--
--   검증식:  locked_cash = SUM(net_amount WHERE status='PENDING')
--
--  포트폴리오 초기화
--   UPDATE account SET status='CLOSED', closed_at=now() WHERE account_id=?;
--   INSERT INTO account (user_id, round_no, initial_cash, cash_balance)
--        VALUES (?, prev_round + 1, 50000000, 50000000);
--   INSERT INTO ledger_entry (account_id, entry_type, amount, balance_after, memo)
--        VALUES (새 account_id, 'INITIAL_DEPOSIT', 50000000, 50000000, '2회차 모의투자금 지급');
--   -- 기존 원장·체결내역·보유종목은 그대로 보존된다 (삭제하지 않는다)
--   -- RESET 이라는 원장 항목은 따로 두지 않는다. 새 계좌의 INITIAL_DEPOSIT 이 그 역할을 한다.
-- ============================================================================
