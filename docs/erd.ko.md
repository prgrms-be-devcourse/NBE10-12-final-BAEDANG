# 모의 주식 트레이딩 서비스 — ERD

> **버전**: 3주차 MVP 기준 · 26.09.03 ~ 09.09 · PostgreSQL 18 + TimescaleDB
>
> - **배지**: Java 21 · Spring Boot 3.5.16 · PostgreSQL 18 · 12 tables · append-only ledger · 회차 기반 초기화

## 목차
- [전체 관계도](#전체-관계도)
- [토스증권 API 매핑](#토스증권-api-매핑)
- [컬럼 사전](#컬럼-사전)
- [종목 분류 모델](#종목-분류-모델)
- [매수 처리 — 2단계 모델](#매수-처리--2단계-모델)
- [지켜야 할 설계 규칙](#지켜야-할-설계-규칙)

---

## 전체 관계도

파란 테이블이 **계정계(사용자의 돈)**, 흰 테이블이 **시세·마스터**입니다. 돈이 움직이는 경로는 `account → trade_order → ledger_entry → holding` 하나뿐이고, 시세 쪽과 만나는 접점은 `trade_order` 와 `holding` 둘뿐입니다.

**범례**
- 파란색 = 계정계 · 흰색 = 시세·마스터 · 보라 = 시계열 (append only)
- `PK` 기본키 · `FK` 외래키 · `UK` 유니크
- **분류①② 태그** — 종목 유형 판정 컬럼
- `TOSS` — 토스증권 API 로 채우는 테이블
- 점선 화살표 = 데이터 흐름 (FK 아님)

**관계 요약**
| 관계 | 종류 |
|---|---|
| users → account | 1:N |
| account → trade_order | 1:N |
| trade_order → ledger_entry | 1:N |
| trade_order → holding | 1:N |
| account → ledger_entry | 1:N |
| account → daily_account_snapshot | 1:N |
| stock → trade_order | 1:N |
| stock → holding | 1:N |
| stock → quote_snapshot | 1:1 |
| stock → stock_external_id | 1:N |
| stock → daily_candle | 1:N |
| stock → minute_candle | 1:N |
| daily_candle → quote_snapshot | 데이터 흐름 (`close_price` → `prev_close`) |

### 테이블 맵 (12개)

| 그룹 | 테이블 | 비고 |
|---|---|---|
| **계정계** | `users` | 회원 및 JWT 인증 정보 |
| | `account` | 모의 계좌 (회차 기반) · `locked_cash` 보유 |
| | `trade_order` | 주문+체결 (일부 TOSS) |
| | `ledger_entry` | 거래 원장 · append only |
| | `holding` | 보유 종목 · `locked_quantity` 보유 |
| | `daily_account_snapshot` | 2주차에 화면 사용 |
| **시세 · 마스터** | `stock` | 종목 마스터 (TOSS /stocks) |
| | `stock_external_id` | 소스별 심볼 매핑 |
| | `quote_snapshot` | 현재가 스냅샷 (TOSS /prices) |
| | `daily_candle` | 일봉 · TimescaleDB (TOSS /candles) |
| | `minute_candle` | 분봉 시계열 · 상위 100 스케줄러 + 상위 100 밖 온디맨드 |
| | `exchange_rate` | 환율 이력 · 일반 테이블 · FK 관계 없음 |

### MVP 동작 매트릭스 (확정)

> **조회는 언제나 전 종목 가능하고, 거래는 "해당 시장 정규장 + 상위 100" 에만 허용**됩니다.
> **판정 기준은 보는 사람의 시각이 아니라 그 종목이 속한 시장이 열려 있는가입니다** — 한국 낮에 엔비디아를 열면 미국장이 닫혀 있으므로 전일 종가가 나갑니다.

| 시간대 (KST) | 국내 상위 100 | 미국 상위 100 | 그 외 전 종목 |
|---|---|---|---|
| 09:00 ~ 15:30 | **5초 실시간 · 거래 O** · 차트 1분봉(1분 수집) | 전일 종가 · 거래 X · 차트 마지막 장 분봉 | 전일 종가 · 거래 X · 차트 마지막 장 분봉 |
| 22:30 ~ 05:00 * | 전일 종가 · 거래 X · 차트 마지막 장 분봉 | **5초 실시간 · 거래 O** · 차트 1분봉(1분 수집) | 전일 종가 · 거래 X · 차트 마지막 장 분봉 |
| 그 외 시간 | 전일 종가 · 거래 X | 전일 종가 · 거래 X | 전일 종가 · 거래 X |

> **차트는 어느 칸에서든 그려집니다.** 정규장이면 5초 시세와 함께 1분봉이 이어지고, 장외이거나 다른 나라 종목이면 마지막 장의 분봉이 그대로 보입니다. 빈 차트는 사용자에게 "고장난 화면"으로 읽히므로 **거래 불가와 조회 불가를 반드시 분리하세요.**
> **상위 100종목의 분봉은 1분마다 스케줄러가 수집합니다.** 별도 `MARKET_DATA_CHART` 20 TPS 그룹에서 20종목 단위로 순차 호출합니다. 상위 100 밖 종목과 장외 상세 차트는 토스에 온디맨드로 요청하고 `minute_candle` 을 60초 캐시로 재사용합니다.

> ⚠️ * **미국 정규장 시각은 서머타임에 따라 1시간 이동합니다.** 서머타임(3월 둘째 일요일 ~ 11월 첫째 일요일) **22:30 ~ 05:00** ← 지금(8월) / 표준시(11월 첫째 일요일 ~ 3월 둘째 일요일) **23:30 ~ 06:00**.
> **절대 하드코딩하지 마세요.** `/market-calendar/US` 의 `regularMarket` 세션 시각을 그대로 쓰면 됩니다 — 응답이 KST 기준으로 오므로 변환도 필요 없습니다. 하드코딩하면 11월 첫째 주에 **장 시작 후 한 시간 동안 거래가 막힙니다.**

> 📌 **상위 100 밖 종목의 전일 종가는 어떻게 채우나요?**
> 스케줄러가 도는 대상은 상위 100 뿐이라 나머지 8,300여 종목은 `quote_snapshot` 이 비어 있습니다.
> **상세 페이지를 여는 순간 `/prices` 와 `/candles` 를 함께 호출해 채우고, 그 결과를 `quote_snapshot` 에 UPSERT 해두세요.** 한 번 조회된 종목은 다음부터 DB 에서 나갑니다.
> 검색 결과 목록에 가격을 같이 보여주고 싶다면, 20건이면 **`/prices` 배치 1콜로 한 번에 받아옵니다** — 종목마다 따로 부르면 20콜이 되어 rate limit 에 걸립니다.

### 종가 데이터 파이프라인

토스 캔들 API 의 종가가 어떻게 화면의 등락률이 되는지:

```
GET /api/v1/candles?symbol=005930&interval=1d&count=200
     └ 국내 15:40 / 미국 05:10 · 시장별 100콜 · 약 20초
        ↓ closePrice 를 저장
daily_candle.close_price          그날 종가 확정
        ↓ 다음 장 시작 직전 복사 (국내 08:50 KST / 미국 09:00 ET)
quote_snapshot.prev_close
        ↓ 5초마다 갱신되는 last_price 와 함께
등락률 = (last_price − prev_close) / prev_close
        ↓
랭킹 목록 · 상세 페이지 · 마이페이지에 표시
```

> **토스 현재가 응답에는 등락률이 없습니다.** `symbol · timestamp · lastPrice · currency` 네 필드뿐이라 전일 종가를 따로 확보해서 직접 계산해야 합니다. 그 전일 종가의 원천이 `daily_candle` 이고, 조인 없이 쓰려고 `quote_snapshot` 에 복사해둡니다.
> **복사 시점이 "마감 직후"가 아니라 "다음 장 시작 직전"** 인 점을 주의하세요. 마감 직후에 복사하면 `prev_close = last_price` 가 되어 장외 시간 내내 등락률이 0% 로 표시됩니다.

> 📌 **수집 범위** — 거래대금 상위 **국내 100 + 해외 100 = 총 200종목**. 랭킹 API 의 `count` 최대값이 100 이라 **시장별 1콜로 완결**됩니다. 선정 기준은 `duration=1w`, 갱신은 **매주 월요일 국내 08:00 · 미국 21:00**(각 시장 장 시작 직전).
> **메모리 캐시로만 처리하는 것** — `market_calendar`(장 운영 시간), 현재 환율(1분 TTL). 이력을 쌓을 이유가 없기 때문입니다.
> **이후 기능 추가 시** — `wiki_term`(금융 용어 위키), `index_candle`(지수 일봉 — 수익률 벤치마크 비교용).

---

## 토스증권 API 매핑

어느 엔드포인트가 어느 컬럼을 채우는지, 얼마나 자주 호출하는지 정리했습니다. **이 표가 곧 수집기 구현 스펙**입니다. 토스의 한도는 API 그룹별로 따로 걸리므로 그룹이 다르면 서로 영향을 주지 않습니다.

**범례**: `TOSS` 토스증권 API · `외부` 다른 소스 필요 · `자체` 우리가 생성

### 엔드포인트별 호출 계획 (2026-08 문서 기준)

| 엔드포인트 | 그룹 / 한도                    | 호출 주기 | 채우는 대상 |
|---|--------------------------------|---|---|
| `POST /oauth2/token` | AUTH · 5 TPS                   | 만료 직전 1회 | DB 저장 없음. **토큰은 메모리 캐싱 필수** — 매 요청마다 발급하면 그것만으로 차단됩니다. |
| `GET /api/v1/stocks/all` | STOCK_ALL · **1 TPS**          | **매주 월요일 07:00** | **마켓별 전체 종목 목록.** 페이지네이션 없이 한 번에 반환(NASDAQ 약 2,800건, gzip 30KB). `market` 7개(KOSPI·KOSDAQ·NYSE·NASDAQ·AMEX·KR_ETC·US_ETC)를 각각 부르면 **7콜로 전 종목 심볼 확보**. 필터가 우리 설계와 맞습니다 — `commonShare=true`(우선주 제외), `status=ACTIVE`(상장폐지 제외), `securityType`(STOCK·ETF·ETN·REIT…). |
| `GET /api/v1/stocks` | STOCK · 5 TPS                  | 매주 월요일 07:00 | `stock` 상세 — 종목명·통화·ISIN·`security_type`·`is_common_share`·`leverage_factor`·상장주식수·상장일, `koreanMarketDetail` 의 거래정지·정리매매 플래그. `/stocks/all` 심볼을 **200개씩 배치**로 — 8,500종목이면 43콜, 약 9초. |
| `GET /api/v1/rankings` | RANKING · 5 TPS                | 유니버스: 월요일 KR 08:00 · US 21:00 / 화면 랭킹: 30초 TTL | `stock.is_ranked`, `stock.rank_no`, `stock.trading_amount`, 신규 편입의 `prev_close`(`price.basePrice`). **시장별 100개씩이라 KR·US 각 1콜로 완결**. `type=MARKET_TRADING_AMOUNT`, `duration=1w`, `excludeInvestmentCaution=true`. 주말엔 집계가 없을 수 있으니 **빈 배열이면 지난주 유니버스 유지**. |
| `GET /api/v1/prices` | MARKET_DATA · **15 TPS**       | **5초** (정규장 시간에만) | `quote_snapshot.last_price`, `quote_at`, `currency`. **시장별 100종목이 배치 1콜** — 5초 주기여도 **한도의 1.3%**. 국내장·미국장이 겹치지 않아 동시 부하 없음. **장이 닫히면 스케줄러 멈춤** → 마지막 값(=종가)이 남아 "전일 종가" 조회. 가격이 **문자열로 오므로 BigDecimal 로 파싱**. |
| `GET /api/v1/price-limits` | MARKET_DATA · 15 TPS           | 장 시작 전 1회 | `quote_snapshot.upper_limit`, `lower_limit`. **전일 종가 기준으로 정해져 하루 동안 안 바뀌므로** 실시간 폴링 불필요. 단건 조회라 국내 100종목이면 100콜, 약 7초. **미국 종목은 가격제한이 없어 NULL**. |
| `GET /api/v1/candles` (interval=1d) | MARKET_DATA_CHART · **20 TPS** | 국내 15:40~17:10 / 미국 현지 16:10~17:10, 30분 간격 재시도 | `daily_candle`. 당일 이미 저장된 종목은 건너뛰고 누락 종목만 재시도합니다. 캘린더 거래일과 응답 일봉 날짜가 일치할 때만 성공 처리합니다. 확정된 `close_price` 는 다음 장 시작 전 `quote_snapshot.prev_close` 로 복사합니다. `timestamp` 는 시각이므로 **KST 기준 날짜로 변환**합니다. |
| `GET /api/v1/candles` (interval=1m) | MARKET_DATA_CHART · **20 TPS** | **상위 100: 1분마다 20종목 단위 순차 호출** / 그 외 종목: 상세 진입 시 온디맨드 | `minute_candle`. 상위 100은 정규장 중 스케줄러로 수집합니다. 장외이거나 다른 나라 종목은 온디맨드로 호출하고 최근 60초 캐시를 재사용합니다. 2주차에는 지정가 체결 판정과 5m·10m 집계를 추가합니다. |
| `GET /api/v1/stocks/{symbol}/warnings` | STOCK · 5 TPS                  | **1주차 미사용** · 필요 시 08:00 배치 | `stock.is_warned`. 정리매매·단기과열·투자경고/위험·VI 발동. **단건 조회라 100종목이면 100콜, 약 20초.** **확정 스케줄에는 넣지 않았습니다** — 랭킹 API 의 `excludeInvestmentCaution=true` 로 이미 대부분 걸러지기 때문. |
| `GET /api/v1/exchange-rate` | MARKET_INFO · 3 TPS            | 이력 적재: **매시 정각** / 현재 환율: 1분 TTL 캐시 | **두 경로가 다릅니다.** 그래프용 이력은 매시 정각 `exchange_rate` 로 적재(하루 24콜), 체결용 현재 환율은 **1분 TTL 메모리 캐시**. 응답의 `validFrom` 을 `rate_at` 으로, `ON CONFLICT DO NOTHING` 으로 **주말 중복 자동 차단**. |
| `GET /api/v1/market-calendar/KR·US` | MARKET_INFO · 3 TPS            | 앱 기동 시 + 매일 1회 | **메모리 캐시로 충분**(이력을 남기고 싶으면 `schema.sql` 의 `market_calendar` 테이블 선택). 세 곳에 쓰임 — **① 주문 가능 시간 판정, ② 시세 수집 스케줄러 on/off, ③ 화면 "실시간/종가" 분기**. 서머타임·수능일·임시휴장 때문에 **절대 하드코딩 금지**. |
| `wss://openapi-ws/ws/v1` | 구독 100건 / 연결 2개          | **2주차 개선 과제** | **실시간 체결·호가 웹소켓.** 연결당 구독 100건, 계정당 연결 2개라 **국내 100 + 미국 100 = 정확히 200종목**. 도입하면 폴링이 사라지고 진짜 실시간. 재연결·재구독, 60초 PING, full-replace 구독 관리 필요, 시세는 **LOSSY 보장**이라 프레임 유실 감안. **1주차에는 폴링**. |
| `POST /api/v1/orders` 등 | —                              | 사용 안 함 | **주문 API 는 절대 호출하지 않습니다** — 실제 계좌에 실주문이 나갑니다. `TossSecuritiesClient` 등 외부 API 클라이언트에서 호출 가능 경로를 화이트리스트로 고정하세요. |

### 테이블별 데이터 출처

| 테이블 | 출처 | 비고 |
|---|---|---|
| `stock` | TOSS | 대부분 `/stocks` + `/warnings` + `/rankings`. `stock_category` 는 **자체** 판정하고, `dividend_yield` 와 배당 뱃지는 토스가 배당 데이터를 주지 않으므로 **MVP에서 비활성화**합니다. |
| `quote_snapshot` | TOSS | `collected_at` 만 **자체**. 나머지 `/prices`, `/price-limits`, `/rankings`. |
| `daily_candle` | TOSS | 전부 `/candles?interval=1d`. 장 마감 직후 시장별 100콜. 수정주가(`adjusted`) 적용 여부를 팀에서 정하고 **고정** — 중간에 바꾸면 과거 데이터와 어긋남. |
| `minute_candle` | TOSS | 전부 `/candles?interval=1m`. 상위 100은 1분마다 20종목 단위 순차 호출로 채우고, 상위 100 밖·장외 종목은 상세 진입 시 받아 저장해 60초간 캐시로 씁니다. |
| `exchange_rate` | TOSS | 전부 `/exchange-rate`. `collected_at` 만 **자체**. |
| `trade_order` | 자체 + TOSS | 주문 내용은 자체 생성, `executed_price`·`quote_at` 은 `quote_snapshot` 에서 복사(원천 `/prices`), `exchange_rate` 는 `/exchange-rate`. **토스에 주문을 보내지는 않음** — 체결은 우리 DB 안에서만. |
| `holding` | 자체 | 원장에서 파생. `avg_exchange_rate` 만 토스 환율에서 유래. |
| `ledger_entry` | 자체 | 전부 자체 생성. `exchange_rate` 만 `trade_order.exchange_rate` 를 **그대로 복사**해 넣음(원천은 토스 `/exchange-rate`). 원장은 append-only 라 주문 테이블이 나중에 어떻게 바뀌든 이 기록은 그대로 남습니다. |
| `users` `account` `daily_account_snapshot` `stock_external_id` | 자체 | 외부 API 와 무관. **계정계는 전적으로 우리가 소유** — 이것이 이 프로젝트가 채널계가 아니라 계정계인 이유. |

### 배치 일정 (확정)

| 시각 (KST) | 주기 | 하는 일                                                                                                                                                                                                                |
|---|---|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| 월요일 **07:00** | 주 1회 | **① 전체 종목 마스터 갱신** — `/stocks/all` × 마켓 7개 → 전 종목 심볼, `/stocks` 배치 200개씩 → 상세. 신규 상장·상장폐지 반영. **약 50콜, 15초.**                                                                      |
| 월요일 **08:00** | 주 1회 | **② 국내 거래대금 상위 100 선정 — 토스 1콜.** `/rankings?market=KR&duration=1w&count=100` → `is_ranked`, `rank_no`, `trading_amount` 갱신 · 신규 편입의 `prev_close`·일봉 백필 · **빈 배열이면 지난주 유니버스 유지**. |
| 월요일 **21:00** | 주 1회 | **③ 미국 거래대금 상위 100 선정 — 토스 1콜.** 국내와 동일한 처리. 미국장 시작(22:30) **1시간 30분 전**이라 새 유니버스로 첫 시세 수집을 시작할 수 있습니다.                                                            |
| **08:50** | 일 1회 | 국내 `prev_close` ← 전일 `daily_candle.close_price` (장 시작 10분 전). 상하한가도 이때 함께 받음. **확정 목록에는 없지만 등락률 계산에 필요** (아래 설명).                                                             |
| 09:00 ~ 15:30 | 5초 | 국내 상위 100 현재가 수집 — **`/prices` 배치 1콜, 한도의 1.3%**. 이 시간에만 국내 종목 거래가 열립니다.                                                                                                                |
| 09:00 ~ 15:30 | 1분 | 국내 상위 100 분봉 수집 — 별도 `MARKET_DATA_CHART` 20 TPS 그룹에서 20종목 단위 순차 호출.                                                                                                                              |
| **15:40 ~ 17:10** | 30분 | **국내 일봉 적재 재시도.** 캘린더상 마감 10분 후부터 실행하며 수능일 지연 마감도 반영. 당일 저장 완료 종목은 건너뜀.                                                                                                   |
| **09:00 ET** * | 일 1회 | **미국 `prev_close` 갱신 — 정규장 시작 30분 전.** KST 기준 서머타임 22:00, 표준시 23:00.                                                                                                                               |
| 22:30 ~ 05:00 * | 5초 | 미국 상위 100 현재가 수집 — 배치 1콜. **표준시(겨울)에는 23:30 ~ 06:00 으로 1시간 이동.**                                                                                                                              |
| 22:30 ~ 05:00 * | 1분 | 미국 상위 100 분봉 수집 — 별도 `MARKET_DATA_CHART` 20 TPS 그룹에서 20종목 단위 순차 호출.                                                                                                                              |
| **미국 현지 16:10 ~ 17:10** * | 30분 | **미국 일봉 적재 재시도.** KST로 서머타임 05:10~06:10, 표준시 06:10~07:10. 당일 저장 완료 종목은 건너뜀.                                                                                                               |
| 매시 정각 | 1시간 | **환율 적재** — 하루 24콜. 주말·휴장에도 그냥 돌림(중복은 UNIQUE 로 자동 차단).                                                                                                                                        |
| 그 외 시간 | — | **시세 수집 정지.** 조회는 되지만 전일 종가 표시 + 주문 거부.                                                                                                                                                          |

> ⚠️ **정규장 중에는 5초마다 배치 1콜이 전부입니다. 한도의 1.3% 만 씁니다.** 200종목을 한 번에 조회하는 배치 API 덕분에 **사용자 수와 외부 API 호출량이 완전히 분리**됩니다 — 이 프로젝트에서 처음 풀어야 했던 문제가 이 한 줄로 해결됩니다.
> **국내장과 미국장은 시간대가 겹치지 않습니다.** 09:00~15:30 과 22:30~05:00 이라 **같은 순간에 도는 수집기는 언제나 하나**. 합산 부하를 걱정할 필요가 없습니다.

> 📌 **`prev_close` 갱신을 목록에 넣은 이유**
> 토스 현재가 응답에는 등락률이 없습니다(`symbol·timestamp·lastPrice·currency` 네 필드뿐). 전일 종가를 직접 확보해 `(last_price − prev_close) / prev_close` 로 계산해야 합니다.
> 복사 시점이 **"마감 직후"가 아니라 "다음 장 시작 직전"** 이어야 합니다. 마감 직후에 복사하면 `prev_close = last_price` 가 되어 장외 시간 내내 등락률이 0% 로 표시됩니다. **일봉 적재(15:40)와 prev_close 복사(다음날 08:50)를 반드시 분리하세요.**

> **스케줄러에 넣지 않은 것 — 온디맨드로 처리합니다**
> · 장외 분봉 — 상세 진입 시 `/candles?interval=1m` 호출 + 60초 캐시. 상위 100 분봉 수집은 1주차 스케줄러에 포함하고, 2주차에는 지정가 체결 판정을 추가합니다.
> · 상위 100 밖 종목의 시세 — 상세 진입 시 `/prices`·`/candles` 를 불러 `quote_snapshot` 에 UPSERT. **8,500종목을 매일 도는 배치는 만들지 않습니다.**
> · 매수 유의사항(`/warnings`) — 단건 조회라 100종목이면 20초. 필요해지면 08:00 배치에 붙이세요.

> 💡 **수집기가 토스와 대화하는 유일한 지점입니다.** 화면(채널계)과 원장(계정계)은 토스를 직접 호출하지 않고 우리 DB 만 봅니다. 그래서 나중에 시세 공급자를 교체해도 `QuotePort` 구현체 하나만 갈아끼우면 되고, 원장·주문 코드는 손댈 일이 없습니다.

---

## 컬럼 사전

테이블별 전체 컬럼과 의도입니다. 특히 **왜 이 컬럼이 필요한가**에 초점을 맞췄습니다. 나중에 추가할 수 있는 컬럼과, 지금 안 넣으면 영원히 복구 불가능한 컬럼이 섞여 있습니다.

### 계정계 — 사용자의 돈

#### `users` — 회원
> 회원은 Stateless JWT로 인증합니다. 탈퇴는 행 삭제 대신 `WITHDRAWN` 상태로 전환해 account·ledger 외래 키를 보존합니다.
| 컬럼 | 타입 | 설명 |
|---|---|---|
| `user_id` | BIGINT PK | 내부 식별자. IDENTITY 로 자동 채번. |
| `email` | VARCHAR(255) UK | 로그인 아이디 겸함. **대소문자 구분 문제**가 있으니 저장 전 소문자로 정규화. |
| `password_hash` | VARCHAR(255) | **평문 저장 금지.** BCrypt 로 해시. Spring Security 의 `BCryptPasswordEncoder` 기본값이면 충분. 1주차엔 비움/더미. |
| `nickname` | VARCHAR(50) | 화면 노출 이름. 이메일 노출 방지. |
| `status` | VARCHAR(20) | `ACTIVE` / `DORMANT` / `WITHDRAWN`. 탈퇴를 물리 삭제로 하면 원장 FK 가 깨지므로 **상태 전환으로만**. |
| `created_at` `updated_at` | TIMESTAMPTZ | 감사용 공통 컬럼. 모든 테이블 권장. |

#### `account` — 모의 투자 계좌
포트폴리오 초기화의 단위입니다. 초기화 시 이 행을 지우지 않고 **회차를 올린 새 행을 만듭니다.**
| 컬럼 | 타입 | 설명 |
|---|---|---|
| `account_id` | BIGINT PK | 주문·원장·보유종목이 전부 이 값에 매달림. **회차가 바뀌면 값도 바뀌어 새 회차만 보임.** |
| `user_id` | BIGINT FK | 소유자. 한 회원이 여러 회차 계좌를 가질 수 있음. |
| `round_no` | INT | **포트폴리오 초기화 회차.** 1부터 시작해 초기화마다 +1. "3번째 도전 중" 같은 표시나 회차별 성적 비교로 확장 가능. |
| `status` | VARCHAR(20) | `ACTIVE` / `CLOSED`. **부분 유니크 인덱스**로 회원당 ACTIVE 계좌 하나만 강제(`WHERE status='ACTIVE'`). |
| `initial_cash` | NUMERIC(19,4) | 지급액(5,000만). 수익률 계산의 분모. 계좌마다 저장해 과거 회차의 기준 보존. |
| `cash_balance` | NUMERIC(19,4) | **전체 예수금.** **매수 트랜잭션에서 `FOR UPDATE` 로 잠그는 대상**. `CHECK (cash_balance >= 0)` 로 음수를 DB 레벨에서 차단. |
| `locked_cash` | NUMERIC(19,4) | **미체결 주문에 묶인 금액.** 주문 접수 시 더하고 체결·취소 시 뺍니다. **주문가능금액은 저장하지 않고 `cash_balance − locked_cash` 로 계산** — 파생값을 저장하면 한쪽만 갱신되는 버그가 조용히 지속됩니다. 동결액에는 **수수료·세금을 포함한 `net_amount`** 를 씁니다. `gross_amount` 만 묶으면 체결 시점에 수수료만큼 부족해집니다. `CHECK (locked_cash <= cash_balance)` 로 과다 동결을 DB 가 막습니다. |
| `version` | BIGINT | JPA 낙관적 락(`@Version`). 비관적 락 주 전략에도 이중 안전장치로. |
| `opened_at` `closed_at` | TIMESTAMPTZ | 회차의 시작·종료 시각. 회차별 운용 기간 계산. |

#### `trade_order` — 주문 + 체결
MVP는 시장가 즉시 체결이라 주문과 체결이 한 행. **거절된 주문도 남깁니다** — "왜 안 됐는지" 설명용. 지정가를 도입하면 체결 부분을 `trade_execution` 으로 분리하게 됩니다.
| 컬럼 | 타입 | 설명 |
|---|---|---|
| `order_id` | BIGINT PK | 주문 번호. 체결 내역 화면 정렬 키. |
| `account_id` | BIGINT FK | 어느 회차 계좌인지. 초기화 후에는 새 계좌 것만 조회. |
| `stock_id` | BIGINT FK | 거래 대상 종목. 심볼이 아니라 내부 ID(심볼은 바뀔 수 있고 시장마다 중복). |
| `client_order_id` | UUID | **계좌 범위 멱등성 키.** 프론트가 주문 화면 진입 시 생성해 함께 보냄. `UNIQUE(account_id, client_order_id)`로 같은 계좌의 중복 체결을 차단하면서 서로 다른 사용자의 우연한 UUID 중복은 허용합니다. |
| `side` | VARCHAR(4) | `BUY` / `SELL`. |
| `order_type` | VARCHAR(10) | MVP는 `MARKET` 고정. 컬럼 미리 두어 `LIMIT` 추가 시 스키마 변경 불필요. |
| `quantity` | NUMERIC(19,6) | 주문 수량. 국내는 정수지만 미국은 소수점 주식 가능 — NUMERIC 으로 여유. |
| `status` | VARCHAR(12) | **주문의 생애주기.** 시장가는 **`PENDING` 을 거치지 않습니다** — 하나의 트랜잭션에서 처음부터 `FILLED` 또는 `REJECTED` 로 INSERT 합니다. `PENDING` 은 주문 접수·동결과 체결이 별도 트랜잭션인 지정가 주문에서만 사용합니다.
  `PENDING` 접수 완료·자금/수량 동결 · `FILLED` 체결 완료·동결 해제+출금 확정 · `REJECTED` 검증 단계 거절(동결 안 함) · `CANCELED` 사용자 취소 · `EXPIRED` 타임아웃 자동 해제.
  상태 전이는 **조건부 UPDATE** 로 — `WHERE order_id=? AND status='PENDING'` 영향 행이 0 이면 이미 취소됐거나 다른 워커가 가져간 것. |
| `reject_reason` | VARCHAR(40) | `MARKET_CLOSED` · `NOT_IN_UNIVERSE` · `STOCK_SUSPENDED` · `STOCK_LIQUIDATION` · `INSUFFICIENT_CASH` · `INSUFFICIENT_QUANTITY` · `STALE_QUOTE` · `FUTURE_QUOTE` · `INVALID_SETTLEMENT_AMOUNT`. 화면 문구 근거. |
| `reference_price` | NUMERIC(19,4) | `REJECTED` 판정에 사용한 종목 통화 기준 가격. 체결가와 구분하기 위해 `executed_price`에는 넣지 않습니다. |
| `executed_price` | NUMERIC(19,4) | 체결 단가. **종목 통화 기준**(미국이면 달러). 원화 환산은 `gross_amount` 에 별도 저장. |
| `quote_at` | TIMESTAMPTZ | 체결 또는 거절 판정에 사용한 시세의 기준 시각. `quote_snapshot.quote_at` 을 그대로 복사. |
| `exchange_rate` | NUMERIC(19,6) | 체결 또는 거절 판정 시점 환율. 원화 종목은 1. |
| `gross_amount` | NUMERIC(19,4) | 체결 금액(원화 환산). `executed_price × quantity × exchange_rate`. |
| `fee` | NUMERIC(19,4) | 거래 수수료 — **`gross_amount × 0.0001` (0.01%, 매수·매도 공통)**. **율이 아니라 적용된 금액 저장** — 정책이 바뀌어도 과거 기록 보존. |
| `tax` | NUMERIC(19,4) | 시장별 매도 비용. 국내: **`gross_amount × 0.002` (0.2%)**. 미국: SEC Fee **`max(USD gross × 0.0000206, $0.01)`**, 원화 환산 전 센트 반올림. 매수는 0. 요율과 최소 금액은 `.env` 로 관리하고 적용된 금액을 저장합니다. |
| `net_amount` | NUMERIC(19,4) | 실제 예수금 증감액. **매수: gross + fee (차감) / 매도: gross − fee − tax (입금)**. 이 등식이 항상 성립하는지 검증 테스트 필수. |
| `ordered_at` | TIMESTAMPTZ | 주문 접수 시각. `quote_at` 과의 차이가 곧 시세 지연. |

#### `ledger_entry` — 거래 원장
**예수금이 움직인 모든 사건을 기록합니다. UPDATE 와 DELETE 를 하지 않는 것이 이 테이블의 존재 이유입니다.** 잘못 기록했으면 수정하지 말고 반대 부호 항목을 넣어 상쇄합니다.
**항목은 세 가지뿐입니다 — `INITIAL_DEPOSIT` · `BUY` · `SELL`.** 수수료와 세금을 **별도 줄로 쪼개지 않고 매수·매도 금액에 포함**합니다. 정상 체결은 원장 한 줄이 `trade_order.net_amount` 하나에 대응하지만, append-only 정정이나 향후 부분 체결에서는 같은 `order_id`에 후속 행이 추가될 수 있습니다. 멱등 응답의 최초 체결 잔액은 `(order_id, entry_id)` 인덱스로 가장 이른 행을 조회합니다.
| 컬럼 | 타입 | 설명 |
|---|---|---|
| `entry_id` | BIGINT PK | 원장 번호. 시간순으로 증가. |
| `account_id` | BIGINT FK | 어느 계좌의 원장인지. |
| `order_id` | BIGINT FK, NULL | 원인이 된 주문. **최초 지급·초기화는 NULL.** `(order_id, entry_id)` 인덱스로 주문별 원장을 시간순 조회. |
| `entry_type` | VARCHAR(20) | **세 가지뿐.**
  `INITIAL_DEPOSIT` — 모의투자금 5천만원 지급 (+)
  `BUY` — gross + fee 차감 (−)
  `SELL` — gross − fee − tax 입금 (+)
  **`RESET` 항목은 두지 않습니다.** 초기화는 새 계좌를 만드는 일이고, 새 계좌의 `INITIAL_DEPOSIT` 한 줄이 그 역할을 대신합니다. 이전 회차의 마감 시각은 `account.closed_at` 에 남습니다. |
| `amount` | NUMERIC(19,4) | **부호 있는 예수금 증감액, 수수료·세금 포함** — 언제나 `trade_order.net_amount` 와 절대값이 같음. 매수는 음수, 매도는 양수. **이 컬럼의 누적 합이 `account.cash_balance` 와 일치해야 함** — 항목이 세 가지뿐이라 이 검증식이 아주 단순해집니다. |
| `balance_after` | NUMERIC(19,4) | 이 항목 반영 직후의 잔액. 엄밀히는 파생값이지만, **정합성이 깨진 지점을 즉시 찾아내는 용도**로 매우 유용. |
| `exchange_rate` | NUMERIC(19,6) | **체결 시점 환율.** 원화 종목은 1, 미국 종목은 그때의 USD/KRW. `amount` 는 이미 원화 환산값이라 계산에는 안 쓰임 — **"이 거래를 얼마짜리 환율로 했는가"를 원장만 보고 알 수 있게 하는 감사 항목.** `trade_order.exchange_rate` 와 같은 값이지만, 원장은 append-only 라 주문 테이블이 나중에 어떻게 바뀌든 이 기록은 그대로 남습니다. **1주차 화면에는 안 써도 됨 — 다만 지금 안 남기면 과거 값은 복원 불가.** |
| `memo` | VARCHAR(200) | 사람이 읽을 설명. 수수료를 별도 줄로 쪼개지 않으므로 **"삼성전자 10주 @ 241,500 (수수료 포함)"** 처럼 내역을 여기에 담습니다. 디버깅·CS 대응이 쉬워짐. |
| `occurred_at` | TIMESTAMPTZ | 발생 시각. `(account_id, occurred_at)` 인덱스로 기간별 조회 처리. |

#### `holding` — 보유 종목
원장에서 파생되는 집계. 이론적으로는 원장을 재생하면 복원할 수 있지만, **조회 성능을 위해 별도 유지**. 계좌+종목당 한 행.
| 컬럼 | 타입 | 설명 |
|---|---|---|
| `holding_id` | BIGINT PK | 대리키. `(account_id, stock_id)` 유니크. |
| `account_id` | BIGINT FK | 회차 계좌. 초기화하면 새 계좌라 보유 종목 자동 비움. |
| `stock_id` | BIGINT FK | 보유 종목. |
| `quantity` | NUMERIC(19,6) | 보유 수량. 수량 0인 행은 유지하지만 두 매수금액은 0으로 초기화하므로, 재매수하면 과거 원가를 승계하지 않고 새 평균을 계산합니다. |
| `locked_quantity` | NUMERIC(19,6) | **미체결 매도 주문에 묶인 수량.** 예수금 동결과 정확히 같은 원리 — 10주를 갖고 **5주 매도 주문을 세 번 걸면 15주를 팔게** 되니까요. **매도 가능 수량 = `quantity − locked_quantity`**. `CHECK (locked_quantity <= quantity)` 로 과다 동결 방지. |
| `avg_buy_price` | NUMERIC(19,4) | **수수료 제외 이동평균 체결단가.** 국내는 `krw_purchase_amount ÷ quantity`, 미국은 `usd_purchase_amount ÷ quantity`로 계산합니다. 반올림된 평균값은 응답값일 뿐 다음 매수 계산의 입력으로 재사용하지 않습니다. 부분 매도 시 유지하고, 전량 매도 후 재매수하면 새 매수금액만으로 다시 계산합니다. |
| `avg_exchange_rate` | NUMERIC(19,6) | **원본 USD/KRW 환율의 매수금액 가중평균.** 미국은 `krw_purchase_amount ÷ usd_purchase_amount`, 국내는 항상 1입니다. 원 단위 반올림 금액에서 환율을 역산하지 않으므로 단일 매수에서는 받은 환율이 그대로 보존됩니다. 매수 수수료는 제외합니다. |
| `usd_purchase_amount` | NUMERIC(29,10) | 현재 잔여 수량에 귀속되는 **수수료 제외 USD 매수금액**. 국내 종목은 0입니다. 미국 매수마다 `체결단가 × 수량`을 더하고, 부분 매도 시 비례 차감하며, 전량 매도 시 0으로 초기화합니다. |
| `krw_purchase_amount` | NUMERIC(38,16) | 현재 잔여 수량에 귀속되는 **원 단위 반올림 전·수수료 제외 원화 매수금액**. 국내 평단가, 미국 평균환율 계산 및 보유 원가 평가에 사용합니다. 부분 매도 시 비례 차감하며, 전량 매도 시 0으로 초기화합니다. |
| `updated_at` | TIMESTAMPTZ | 마지막 변동 시각. |

#### `daily_account_snapshot` — 일별 자산 스냅샷
**1주차에는 화면에 쓰지 않지만 배치는 지금 넣으세요.** 매일 장 마감 후 한 줄씩 쌓는 단순한 작업인데, 이게 없으면 2주차에 자산 추이 그래프를 그릴 과거 데이터가 아예 없습니다. 거래 내역으로 역산하려면 과거 시점의 모든 시세가 필요해 현실적이지 않습니다.
| 컬럼 | 타입 | 설명 |
|---|---|---|
| `account_id` + `snapshot_date` | 복합 PK | 계좌 × 날짜로 하루 한 행. |
| `cash_balance` | NUMERIC(19,4) | 그날 마감 시점 예수금. |
| `stock_value` | NUMERIC(19,4) | 보유 종목 평가금액(종가 × 수량, 원화 환산). |
| `total_asset` | NUMERIC(19,4) | 예수금 + 평가금액. 자산 추이 그래프 Y축. |
| `unrealized_pnl` | NUMERIC(19,4) | 평가손익. 실현손익은 원장에서 집계하므로 별도 컬럼 없음. |

### 시세 · 마스터

#### `stock` — 종목 마스터
내부 `stock_id` 를 정규 식별자로 삼고, 외부 심볼은 매핑 테이블로 분리. 매주 월요일 07:00 배치 갱신.
| 컬럼 | 타입 | 설명 |
|---|---|---|
| `stock_id` | BIGINT PK | 내부 식별자. **도메인 코드는 이 값만 알면 됨.** 소스가 바뀌어도 유지. |
| `symbol` | VARCHAR(20) | `005930` / `AAPL`. `market_country` 와 함께 유니크. 점 포함 티커(`BRK.B`) → **Spring URL 경로 변수 주의**. |
| `market_country` | VARCHAR(2) | `KR` / `US`. 국내·해외 탭과 장 시간 판정. |
| `market` | VARCHAR(20) | `KOSPI` / `KOSDAQ` / `NASDAQ` / `NYSE`. 화면 뱃지. |
| `name` `english_name` | VARCHAR(200) | 검색 대상. 한글 부분일치를 위해 **`pg_trgm` GIN 인덱스**. |
| `isin_code` | VARCHAR(12) | 국제 표준 종목 식별자(`KR7005930003`). **종목코드는 바뀔 수 있지만 ISIN 은 상대적으로 안정적** — 실제 증권사도 둘 다 관리. |
| `currency` | VARCHAR(3) | `KRW` / `USD`. 환율 환산 필요 여부 결정. |
| `security_type` | VARCHAR(20) | 토스가 준 원본 값(`STOCK` / `ETF` / `ETN` …). **가공하지 않고 그대로 보관** — 분류 규칙이 바뀌어도 원본이 남아야 재계산 가능. |
| `stock_category` | VARCHAR(20) | **상품 유형 — 배타적, 프론트 분기의 1차 키.** `INDIVIDUAL`/`PREFERRED`/`ETF`/`ETN`. `security_type` 과 `is_common_share` 로 배치에서 자동 판정. |
| `leverage_factor` | NUMERIC(4,1) | **ETF·ETN 의 레버리지 배수 — 프론트 분기의 2차 키.** null=일반주식, 1.0=일반 ETF, 2.0·3.0=레버리지, -1.0·-2.0=인버스. 레버리지·인버스 종목에 변동성 경고 배너를 띄우는 근거. |
| `is_dividend` | BOOLEAN | **배당 속성 — 태그.** 유형과 독립이라 개별주에도 ETF 에도 붙을 수 있음. KB금융은 배당주이면서 개별주이므로 한 컬럼에 유형과 섞으면 표현 불가. |
| `dividend_yield` | NUMERIC(6,4) | 향후용 연 배당수익률. MVP에서는 NULL로 두고 `is_dividend`를 비활성화합니다. 별도 배당 데이터 소스 추가는 향후 범위입니다. |
| `is_common_share` | BOOLEAN | 토스 원본. `false` 면 우선주(삼성전자우 등). 거래대금 상위에 우선주가 섞여 들어오므로 **`stock_category = PREFERRED` 판정에 사용**. |
| `shares_outstanding` | NUMERIC(20,0) | 상장주식수. **시가총액 = 현재가 × 이 값** (시총 컬럼 없음). |
| `list_date` `delist_date` | DATE | 상장일 / 상장폐지일. 팩트 시트. |
| `is_suspended` | BOOLEAN | 거래정지. **주문 검증 즉시 거절 사유.** |
| `is_liquidation` | BOOLEAN | 정리매매 중. 상장폐지 직전 단계라 위험 안내 필요. |
| `is_warned` | BOOLEAN | 투자경고·투자위험 지정. 주문은 막지 않고 **경고 배너**. |
| `is_ranked` | BOOLEAN | 거래대금 상위 100 포함 여부. **시세 수집 대상 판정.** |
| `rank_no` | INT | 랭킹 순위(1~100). **화면 표시 전용.** ⚠️ **커서로 쓰면 안 됩니다** — 배치가 통째로 다시 쓰는 값이라 갱신 직후 같은 번호가 다른 종목을 가리킵니다. 상위 100 밖이면 NULL. |
| `trading_amount` | NUMERIC(24,0) | **최근 1주 누적 거래대금(`duration=1w`).** 랭킹 **정렬 기준이자 커서의 1차 키**. 선정 기준을 그대로 화면에 보여주므로 사용자가 "왜 이 순서인지" 이해. **커서는 `(trading_amount, stock_id)` 튜플** — 거래대금이 같은 종목이 있으면 `stock_id` 가 순서를 유일하게 결정. 인덱스도 `(market_country, trading_amount DESC, stock_id DESC)` 로 **같은 순서·같은 방향**이어야 추가 정렬 없이 훑습니다. |

#### `quote_snapshot` — 현재가 스냅샷
**전 종목을 1행씩 — 약 8,500행 고정.** 이력을 쌓지 않고 계속 UPDATE 하므로 시계열 테이블이 아닙니다.

| 대상 | 갱신 | `quote_at` |
|---|---|---|
| **상위 200종목**(국내 100 + 미국 100) | 해당 시장 정규장 중 **5초마다** | 방금 전 → **"12:36:59 기준 · 실시간"** |
| 나머지 약 8,300종목 | **온디맨드** — 상세 진입 시 `/prices`+`/candles` 호출 후 UPSERT | 조회 시점 → `quote_at` 에 따라 실시간/전일 종가 라벨 |

> **이렇게 하면 화면 로직이 하나로 통일됩니다.** 상세 페이지는 종목이 상위 100 이든 아니든 항상 이 테이블만 조회하고, `quote_at` 을 보고 문구만 바꿉니다. **"이 종목이 상위 100 인가?"를 화면이 알 필요가 없어집니다.** 거래 가능 판정은 별도 — `stock.is_ranked` AND 해당 시장 정규장 중 AND 거래정지 아님.

| 컬럼 | 타입 | 설명 |
|---|---|---|
| `stock_id` | BIGINT PK/FK | 종목당 한 행이라 PK=fk (1:1). |
| `last_price` | NUMERIC(19,4) | 현재가(종목 통화). 토스가 **문자열로 주므로 반드시 BigDecimal 파싱**. double 로 받으면 잔고 어긋남. |
| `prev_close` | NUMERIC(19,4) | **전일 종가.** 토스 현재가 응답에 등락률이 없어 `(last_price − prev_close)/prev_close` 로 직접 계산. **전일 `daily_candle.close_price` 복사**(국내 08:50 / 미국 22:00, 다음 장 시작 직전). 일봉 수집 실패면 `last_price` 복사 폴백(마감 시점 값이 곧 종가), 신규 편입 종목은 랭킹 `price.basePrice` 로 초기화. **갱신 시점이 "마감 직후"가 아니라 "다음 장 시작 직전"인 이유** — 마감 직후 갱신하면 `prev_close = last_price` 가 되어 장외 시간 내내 등락률이 0% 로 표시. |
| `upper_limit` `lower_limit` | NUMERIC(19,4) | 상한가/하한가. **전일 종가 기준으로 하루 동안 안 바뀌므로 장 시작 전 1회만 조회.** 주문 가격 검증. |
| `currency` | VARCHAR(3) | 가격의 통화. `stock` 과 중복이지만 조인 없이 시세만 조회할 때 편함. |
| `quote_at` | TIMESTAMPTZ | **토스가 알려준 시세 기준 시각.** 두 곳에 사용 — 화면의 "12:36:59 기준" 표시, 주문 시 유효시간 검증(15초 넘게 오래됐으면 `STALE_QUOTE` 로 거절). |
| `collected_at` | TIMESTAMPTZ | 우리가 수집한 시각. `quote_at` 과의 차이로 수집 파이프라인 지연 모니터링. |

#### `daily_candle` — 일봉
용도가 둘 — **일봉 차트**와 **`prev_close` 원천**. 장 마감 직후 수집해 그날 봉을 확정하고, 그 `close_price` 가 다음 장 시작 전 `quote_snapshot.prev_close` 로 복사되어 등락률의 기준이 됩니다. 일봉과 분봉은 **TimescaleDB** 에 저장해 두 차트 시계열을 같은 보존·시간 범위 조회 모델로 관리합니다. 200종목 × 250거래일 = **연 5만 행, 약 3MB**라 일봉 하이퍼테이블은 분봉이 커져도 작게 유지됩니다.

| 컬럼 | 타입 | 설명 |
|---|---|---|
| `stock_id` + `trade_date` | 복합 PK | 종목 × 거래일로 하루 한 행. 응답의 `timestamp` 는 시각이므로 **KST 기준 날짜로 변환** — UTC 로 자르면 미국 종목이 하루씩 밀립니다. |
| `open_price` | NUMERIC(19,4) | 시가. |
| `high_price` `low_price` | NUMERIC(19,4) | 고가/저가. 캔들 차트의 꼬리. |
| `close_price` | NUMERIC(19,4) | 종가. 다음 거래일의 `prev_close` 로 복사되어 등락률 계산의 분모. |
| `volume` | NUMERIC(20,0) | 거래량. 차트 하단 막대. |

> ⚠️ **수정주가 적용 여부 지금 정하세요.** 액면분할·무상증자가 일어나면 과거 주가가 소급 조정됩니다. 토스 캔들 API 의 `adjusted` 파라미터를 합의하고 **고정**. 현재 온디맨드 백필은 상세·차트 최초 진입 시 외부 API를 한 번만 호출해 최신 200봉을 저장하고, 이후 기간 전환은 DB를 재사용합니다. 이후 조회에서는 시장 캘린더로 구한 최신 확정 거래일(장 마감 10분 후)보다 저장 일봉이 오래된 경우에만 200봉을 다시 UPSERT하며, 같은 실행·같은 확정 거래일의 성공한 요청은 반복하지 않습니다. 저장 이력이 없는 종목의 1Y 응답은 최대 200봉이며 스케줄러 등 별도 적재 이력이 있으면 최대 250봉을 반환합니다. **주봉·월봉은 API 가 안 줌** — `interval` 이 `1m`·`1d` 둘뿐이라 이 테이블을 집계해서 만들어야 함.

#### `minute_candle` — 분봉 시계열
**상위 100종목은 1분 주기로 채웁니다.** 별도 `MARKET_DATA_CHART` 20 TPS 그룹에서 20종목 단위로 순차 호출해 이 테이블에 저장합니다. 상위 100 밖 종목과 장외 상세는 `/candles?interval=1m` 을 온디맨드로 호출하고 **60초 동안은 DB 에서 바로 내려줍니다 — 테이블이 저장소이자 캐시 역할을 겸합니다.**
**장외 시간에도 차트는 그려집니다.** 장이 닫힌 종목에 `/candles` 를 부르면 마지막 장의 분봉이 그대로 옵니다. 한국 낮에 엔비디아를 열어도 마찬가지 — 전일 종가와 함께 지난 미국장의 분봉 차트가 보입니다. 화면은 `quote_at` + 장 운영 캘린더로 "실시간/종가" 문구만 바꾸면 되고, 차트 자체는 분기 필요 없음.
**한 번에 받을 수 있는 봉은 200개.** 국내 정규장 09:00~15:30 = 330분이라 하루치를 다 받으려면 `before` 로 2회 호출. **1주차 차트가 "최근 200분"이면 1콜로 끝나니, 기본은 1콜로 두고 전체 보기를 누를 때만 2콜** 쓰는 편이 단순.
**실측 필요** — `before` 가 inclusive 인지, 마감 동시호가(15:30) 봉이 존재하는지. 15:30 봉이 없으면 330개가 아니라 329개라 개수로 검증하는 로직이 깨짐. 경계 봉이 중복돼도 `(stock_id, candle_at)` PK 의 `ON CONFLICT DO NOTHING` 이 걸러줌.
**2주차에는 지정가 체결 판정과 집계를 추가합니다.** 체결 엔진은 사용자가 차트를 안 봐도 과거 봉을 조회해야 하므로 "그 1분 안에 지정가에 닿았는가"를 `low <= 지정가` 로 판정합니다. **테이블 구조는 그대로 두고 사용하는 방식만 확장합니다.**

| 컬럼 | 타입 | 설명 |
|---|---|---|
| `stock_id` + `candle_at` | 복합 PK | `candle_at` 은 **봉 시작 시각**(응답 `timestamp`). TimescaleDB 하이퍼테이블은 PK 에 파티션 키를 반드시 포함해야 하는데 이 구조가 이미 만족. |
| `open_price` | NUMERIC(19,4) | 그 1분의 시가. |
| `high_price` `low_price` | NUMERIC(19,4) | 고가/저가. 차트 꼬리 + 나중에 **지정가 체결 판정** — "그 1분 안에 지정가에 닿았는가"를 `low <= 지정가`(매수) 로 판정. |
| `close_price` | NUMERIC(19,4) | 종가. |
| `volume` | NUMERIC(20,0) | 거래량. |

> ⚠️ **일봉과 분봉 모두 TimescaleDB 를 사용합니다.** `continuous aggregate` 로 1분봉→5분봉·15분봉을 테이블 없이 뷰로 파생합니다. 일봉은 API `adjusted=true` 원본을 사용하고 분봉에서 파생하지 마세요 — 액면분할 시 과거 일봉 조정을 반영할 수 없습니다. **하이퍼테이블은 다른 테이블 FK 참조 불가**하고, 압축·연속집계는 **TSL 라이선스**, 관리형 DB(RDS)는 대체로 미지원 → 배포 방식 영향.

#### `exchange_rate` — 환율 이력 (일반 테이블)
**TimescaleDB 하이퍼테이블이 아닌 일반 append-only 테이블입니다.** 시세는 `quote_snapshot` 에 UPDATE 하므로 이력이 없지만, 환율은 그래프를 그려야 해서 시점별로 쌓습니다. 다른 테이블과 FK 연결 없음 — **원장에 필요한 환율은 "그때 그 값"이지 참조가 아니어야** 하기 때문. 나중에 환율 데이터를 정정해도 과거 체결 기록은 흔들리면 안 됩니다.

| 컬럼 | 타입 | 설명 |
|---|---|---|
| `exchange_rate_id` | BIGINT PK | 대리키. 실제 식별은 `(base_currency, quote_currency, rate_at)` 유니크. |
| `base_currency` `quote_currency` | VARCHAR(3) | 통화쌍. MVP 에서는 USD → KRW 하나뿐이지만, 컬럼으로 두면 나중에 통화가 늘어도 스키마 안 고쳐도 됨. |
| `rate` | NUMERIC(19,6) | **매수 환율** — 실제로 달러를 살 때 적용되는 값. `mid_rate` 와의 차이가 **환전 스프레드**이고, 이것도 거래 비용의 일종이라 수수료·세금과 같은 맥락의 교육 소재. |
| `mid_rate` | NUMERIC(19,6) | **매매기준율(은행간 mid rate)** — 일반적으로 "환율"이라고 하면 이 값. 그래프 표시와 평가금액 환산에 사용. |
| `rate_at` | TIMESTAMPTZ | **환율 시점 — 응답의 `validFrom` 을 그대로.** 토스는 1분 단위로 갱신하며 `validFrom~validUntil` 유효 윈도 제공. 10:03:27 에 조회했어도 그 환율의 시점은 10:03:00 이므로, 그래프 X축은 이 값이어야 정확. |
| `collected_at` | TIMESTAMPTZ | 우리가 받은 시각. `rate_at` 과의 차이로 수집 지연 확인. |

> **수집 주기: 매시 정각 (확정).** 환율은 하루 0.3~0.5% 정도만 움직여 분 단위로 쌓으면 노이즈만 늘고 그래프는 같아 보입니다. 시간별로 쌓아두면 일간·주간·월간 그래프를 전부 여기서 집계. 반대로 일별로만 집계해두면 "시간별로 보고 싶다"가 나왔을 때 데이터가 없습니다. **주말·휴장에도 그냥 돌리세요** — 외환시장이 쉬는 동안 토스가 같은 `validFrom` 을 계속 반환하므로 `UNIQUE (base_currency, quote_currency, rate_at)` 에 걸려 자동으로 걸러집니다. `ON CONFLICT DO NOTHING` 이면 스케줄러에 주말 조건 불필요. 실제 적재량은 평일 위주 **연 6,000행**. **한 시간 건너뛰어도 괜찮음** — 그래프에 점 하나 빠질 뿐, 실패 시 재시도 말고 다음 정각에. 연속 실패만 알림으로.

#### `stock_external_id` — 소스별 심볼 매핑
같은 삼성전자를 토스는 `005930`으로 부르고, 향후 소스는 DART의 `00126380`처럼 다른 식별자를 사용할 수 있습니다. **지금 만들어두면 소스 추가·교체 시 도메인 코드 무수정.** 비용은 거의 0 인데 나중에 넣으려면 이미 짠 코드를 전부 손대야 함.

| 컬럼 | 타입 | 설명 |
|---|---|---|
| `stock_id` + `source` | 복합 PK | MVP에서는 `source` 를 `TOSS` 로 사용하고, `DART` / `FINNHUB` 는 향후 연동용으로 예약합니다. |
| `external_id` | VARCHAR(50) | 해당 소스 식별자. `(source, external_id)` 유니크로 **한 외부 ID 가 두 종목에 매핑되는 사고 방지**. |

---

## 종목 분류 모델

레버리지·인버스·우선주를 **숨기지 않고 전부 노출**하되, 유형에 따라 다른 안내를 보여줍니다. 모르는 상품을 가려두면 사용자는 실전에서 처음 만나게 됩니다. 손실이 0원인 환경에서 설명하는 편이 "거래를 이해시키는 도구"라는 목적에 맞습니다.

### 유형(배타적) × 속성(태그) 조합
배당주는 **유형이 아니라 속성**. KB금융은 배당주이면서 개별주이므로 한 컬럼에 넣으면 표현 불가. 그래서 유형과 태그를 분리.

| 조합 | 화면 뱃지 | 안내 문구 (프론트 정적 콘텐츠) |
|---|---|---|
| `INDIVIDUAL` | 개별주 | 특정 기업 한 곳의 지분을 사는 것입니다. 그 회사가 잘되면 오르고 어려워지면 내립니다. 한 종목에 자산을 몰아넣지 않는 것이 중요합니다. |
| `INDIVIDUAL` + `is_dividend` | 개별주 · 배당주 | 이익의 일부를 주주에게 정기적으로 나눠주는 기업입니다. 주가 상승이 크지 않아도 배당으로 수익이 발생할 수 있습니다. |
| `PREFERRED` | 우선주 | 의결권이 없는 대신 배당을 우선적으로 받는 주식입니다. 같은 회사의 보통주와 가격이 다르게 움직이며 거래량이 적은 편입니다. |
| `ETF` + leverage = 1.0 | ETF | 여러 종목을 묶어 담은 상품입니다. 한 기업이 흔들려도 충격이 분산되어 개별주보다 변동이 작습니다. |
| `ETF` + leverage ≥ 2.0 | **레버리지 ETF** ⚠ 경고 배너 | **지수가 1% 오르면 약 2% 오르고, 1% 내리면 약 2% 내립니다.** 또한 매일 수익률을 재계산하는 구조라, 장기 보유하면 지수가 제자리로 돌아와도 손실이 남을 수 있습니다. |
| `ETF` + leverage < 0 | **인버스 ETF** ⚠ 경고 배너 | **지수가 내릴 때 오르는 상품입니다.** 방향을 반대로 베팅하는 것이라 시장이 오르면 손실이 납니다. 레버리지와 마찬가지로 장기 보유에 불리합니다. |

> ⚠️ **레버리지의 "일일 재계산"을 꼭 설명하세요.** 초보자가 가장 크게 손해 보는 지점입니다. 지수가 +10% 후 −9.09% 해서 제자리로 돌아와도, 2배 레버리지는 원금을 회복하지 못합니다. 이 개념을 안전한 환경에서 배우게 하는 것이 이 서비스의 존재 이유에 가깝습니다.

> 📌 **배치에서 실행할 분류 판정**
> `stock_category` = ETN이면 ETN, ETF면 ETF, `is_common_share = false` 면 PREFERRED, 나머지는 INDIVIDUAL.
> `is_dividend` = `dividend_yield >= 임계값`. 임계값은 팀 결정. 1주차엔 전부 `false` 로 두고 뱃지 꺼도 됨 (토스 API 에 배당 데이터 없음).

---

## 주문 처리 — 시장가는 즉시, 지정가는 2단계

### 시장가 주문 — 한 트랜잭션에서 즉시 체결

시장가 주문에는 커밋되는 중간 상태가 없습니다. 계좌 행을 먼저 잠근 뒤 `cash_balance − locked_cash` 또는 `quantity − locked_quantity` 기준으로 검증하고, 계좌·보유 수량 변경, `FILLED` 주문 INSERT, 원장 INSERT를 하나의 트랜잭션에서 처리합니다. 다른 트랜잭션이 볼 수 없는 동결액을 증가시켰다가 바로 감소시키지 않습니다.

형식이 유효해도 외부 조회 전 정적 사전 검증에서 거절되거나 시장 컨텍스트가 만료된 요청은 주문 행을 만들지 않으며 같은 `client_order_id`로 재시도할 수 있습니다. 사전 검증 통과 후 계좌 락 획득 사이 종목 상태가 바뀌거나, 트랜잭션 내부 업무 규칙으로 거절된 경우에만 예수금·수량·원장을 변경하지 않고 `reject_reason`과 함께 `REJECTED` 주문을 커밋합니다. 이 저장된 거절은 최종 결과이므로 새 주문에는 새 `client_order_id`를 사용합니다. FK나 숫자 형식조차 만족할 수 없는 잘못된 요청도 주문 행으로 저장하지 않고 요청 오류로 반환합니다.

### 지정가 주문 — 서로 다른 두 트랜잭션

지정가 주문은 아래 두 단계를 사용합니다. Phase 1이 동결과 `PENDING`을 커밋하고 체결 Worker가 나중에 Phase 2를 수행합니다. 이 흐름을 도입할 때 취소·만료·장애 복구도 반드시 함께 구현합니다.

### Phase 1 — 주문 접수 [동결]
| 단계 | 동작 | 설명 |
|---|---|---|
| ① | `SELECT … FOR UPDATE` | 계좌 행 잠금. **검증보다 먼저 잠가야** 그 사이에 값이 안 바뀝니다. |
| ② | 검증 | 장 시간 · `is_ranked` · 거래정지 · 시세 유효시간(15초) · **주문가능금액 = `cash_balance − locked_cash` ≥ `net_amount`** |
| ③ | `locked_cash += net_amount` | **자금 동결.** 수수료·세금을 포함한 `net_amount` 를 묶습니다 — `gross_amount` 만 묶으면 체결 시점에 수수료만큼 부족해집니다. |
| ④ | `INSERT trade_order (PENDING)` | `(account_id, client_order_id)` 유니크 위반이면 중복 클릭이므로 기존 주문 결과를 반환. |

### Phase 2 — 주문 체결 [확정]
| 단계 | 동작 | 설명 |
|---|---|---|
| ① | `locked_cash −= ?` `cash_balance −= ?` | 계좌 재잠금 → **동결 해제와 실제 출금을 동시에** 반영. |
| ② | `UPSERT holding` | 수량 증가 + 이동평균 단가·환율 재계산. **락 순서는 항상 `account` → `holding`**. 엇갈리면 데드락. |
| ③ | `UPDATE … WHERE status='PENDING'` | **조건부 UPDATE 로 경합 방지.** 영향 행이 0 이면 이미 취소됐거나 다른 워커가 가져간 것이므로 롤백. |
| ④ | `INSERT ledger_entry` | 원장 기록. append only — **`BUY` 한 줄에 수수료까지 포함해 넣고 `exchange_rate` 를 함께 남깁니다.** |

> 💡 **지정가 매도는 대칭입니다.** Phase 1 에서 `holding.locked_quantity` 를 늘리고, Phase 2 에서 `quantity` 와 `locked_quantity` 를 함께 줄이며 예수금을 입금합니다. **`avg_buy_price` 는 건드리지 않습니다** — 이동평균법에서는 매도 시 수량과 취득원가가 같은 비율로 줄어 남은 주당 평균단가가 변하지 않기 때문입니다.

> ⛔ **지정가를 도입하면 반드시 필요한 것** — Phase 1 커밋 직후 장애가 나면 **동결액이 영원히 안 풀립니다.** 사용자는 "돈이 있는데 왜 주문이 안 되지?"가 됩니다. 타임아웃 기반 자동 해제 배치를 만드세요:
> `UPDATE trade_order SET status='EXPIRED' WHERE status='PENDING' AND ordered_at < now() − INTERVAL '5 min'` → 해당 금액만큼 `locked_cash` 를 되돌림.
> **시장가 주문은 즉시 체결 트랜잭션 전체가 롤백되므로 이 배치가 필요 없습니다.**

> ✅ **검증식이 하나 늘어납니다.** `locked_cash = SUM(trade_order.net_amount WHERE status='PENDING')` — 미체결 주문 합계와 동결액이 항상 같아야 합니다. 고아 PENDING 이 생기면 이 식이 깨지므로 즉시 잡아낼 수 있습니다.

---

## 지켜야 할 설계 규칙

1. **금액은 전부 `NUMERIC`** — `double`/`float` 을 쓰면 잔고가 미세하게 어긋나기 시작합니다. Java 에서도 `BigDecimal` 로 받으세요. 토스 API 가 가격을 문자열로 주는 것도 같은 이유입니다.
2. **시각은 전부 `TIMESTAMPTZ`** — 국내장·미국장·서머타임이 섞이므로 DB 에는 UTC 로 저장하고 표시할 때만 변환합니다.
3. **매수 트랜잭션은 계좌 행 잠금부터** — `SELECT … FROM account WHERE account_id = ? FOR UPDATE` 로 시작해야 동시 주문 시 예수금 이중 차감을 막습니다. 종목 단위로 잠그면 못 막습니다.
4. **원장은 append-only** — 잘못 기록했으면 수정하지 말고 반대 부호 항목을 넣어 상쇄합니다.
5. **포트폴리오 초기화는 삭제가 아니다** — 요청한 현재 `account_id`를 `FOR UPDATE`로 잠그고 기존 계좌를 CLOSED 로 바꾼 뒤 `round_no + 1` 인 새 계좌와 `INITIAL_DEPOSIT` 원장을 한 트랜잭션으로 만듭니다. 종료·개설·원장에는 같은 UTC 시각을 사용하고 기존 원장·체결·보유는 보존합니다. 같은 종료 계좌 ID의 즉시 재요청은 직전 신규 계좌를 반환하여 회차를 중복 생성하지 않습니다.
6. **지금 안 넣으면 복구 불가능한 것 다섯 가지** — `trade_order.quote_at`, `trade_order.exchange_rate`, `ledger_entry.exchange_rate`, `holding.avg_exchange_rate`, 그리고 `daily_account_snapshot` 배치입니다. 컬럼은 나중에 추가할 수 있지만 **과거 값은 복원할 수 없습니다.**

> 🧪 **검증 테스트로 만들면 좋은 것** — 모든 거래 후 `매수 시 net_amount = gross_amount + fee`, `매도 시 net_amount = gross_amount − fee − tax` 가 항상 성립하는지, 그리고 `ledger_entry.amount`(수수료 포함) 의 누적 합이 `account.cash_balance` 와 일치하는지 확인하는 테스트를 두세요. 원장을 제대로 이해했다는 가장 확실한 증거가 됩니다.

---
> 모의 주식 트레이딩 서비스 · 현재 ERD · `schema.sql` 과 함께 보세요
