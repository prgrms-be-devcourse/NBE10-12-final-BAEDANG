# 모의 주식 트레이딩 서비스 — API 명세서 (1주차 MVP)

> **버전**: 1주차 MVP · 26.08.20 ~ 08.25 · ERD 와 와이어프레임에서 도출 · **인증은 1주차 미구현** — 서버는 시드 사용자 1명(`user_id = 1`)으로 동작 (`AUTH_ENABLED=false`)
>
> **배지**: 17 엔드포인트 · Java 21 · Spring Boot 3.5.16 · PostgreSQL 18 + TimescaleDB · REST · JSON

## 목차
- [공통 규칙](#공통-규칙)
- [인증 · 회원](#인증--회원)
- [시장](#시장)
- [종목](#종목)
- [거래](#거래)
- [계좌](#계좌)
- [화면 ↔ API 매핑](#화면--api-매핑)
- [폴링 정책](#폴링-정책)
- [남은 결정 사항](#남은-결정-사항)
- [2주차 이후](#2주차-이후)

---

## 공통 규칙

### Base URL
| | |
|---|---|
| 개발 | `http://localhost:8080/api` |
| 운영 | `https://{도메인}/api` |

### 인증

로그인 후 발급받은 토큰을 헤더에 담습니다.
```
Authorization: Bearer {accessToken}
```

- **1주차에는 인증을 구현하지 않습니다.** 회원가입·로그인 화면은 **UX 설계만** 하고, 서버는 **시드 사용자 1명(`user_id = 1`)으로 고정**해 동작합니다 (`AUTH_ENABLED=false` in `.env`). 아래 **🔒 표시된 엔드포인트도 1주차에는 토큰 없이 호출**되며, 서버가 고정 사용자의 계좌를 씁니다.
- **2주차에 인증 방식(JWT vs 세션 쿠키)을 정하세요.** Next.js 를 쓰신다면 **Route Handler 를 BFF 로 두고 httpOnly 쿠키에 토큰을 담는 방식**이 가장 안전합니다 — 토큰이 브라우저 JS 에 노출되지 않습니다.

| 구분 | 대상 |
|---|---|
| 비로그인 허용 | 랭킹 · 검색 · 종목 상세 · 차트 · 환율 · 이용 가이드 |
| 🔒 로그인 필수 | 주문 · 계좌 · 보유종목 · 체결내역 · 포트폴리오 초기화 |

### 응답 형식

성공 시 데이터를 **그대로** 반환하고, 목록은 커서를 함께 내려줍니다.
```json
{
  "items": [ ... ],
  "nextCursor": "eyJ0YSI6IjEyNDAwMDAwMDAwMDAiLCJpZCI6MTAyNH0",
  "hasNext": true
}
```

### 에러 형식

```json
{
  "error": {
    "code": "INSUFFICIENT_CASH",
    "message": "주문가능금액이 부족합니다.",
    "data": { "required": "2415242", "available": "1200000" }
  }
}
```
`message` 는 **사용자에게 그대로 보여줄 수 있는 문장**으로 작성합니다.

### 표현 규칙

| 항목 | 규칙 | 예시 |
|---|---|---|
| 금액 · 수량 | 문자열 | `"241500"`, `"0.5"` |
| 시각 | ISO 8601 + 오프셋 | `"2026-08-11T12:36:59+09:00"` |
| 날짜 | YYYY-MM-DD | `"2026-08-11"` |
| 등락률 | 소수 비율 문자열 | `"0.0231"` = +2.31% |
| 통화 | ISO 4217 | `"KRW"`, `"USD"` |

**금액을 숫자로 내리지 마세요.** JavaScript 의 `number` 는 배정밀도 부동소수라 큰 금액이나 소수점 주문에서 오차가 생깁니다. 토스 API 가 가격을 문자열로 주는 것과 같은 이유입니다. 프론트에서는 **Decimal.js 를 쓰거나 문자열 그대로 표시**하세요.

### 커서 페이지네이션

```
GET /stocks/rankings?market=KR&size=20
→ { "items": [...], "nextCursor": "abc", "hasNext": true }
GET /stocks/rankings?market=KR&size=20&cursor=abc
```
랭킹은 순위가 바뀔 수 있어 **OFFSET 방식이면 항목이 중복·누락**됩니다. **`cursor` 는 서버가 인코딩한 불투명 문자열이며 클라이언트는 해석하지 않습니다.**

**커서 페이로드 — 정렬 기준을 그대로 담습니다**
랭킹 정렬 기준은 거래대금 내림차순 하나뿐이므로, 커서에도 그 값을 담습니다.
```js
// 커서에 담기는 값
{ "ta": "1240000000000", "id": 1024 }   tradingAmount · stockId
// Base64URL 로 인코딩해서 내려보낸다
"eyJ0YSI6IjEyNDAwMDAwMDAwMDAiLCJpZCI6MTAyNH0"
```
```sql
-- 다음 페이지 조회
SELECT ... FROM stock s JOIN quote_snapshot q USING (stock_id)
 WHERE s.is_ranked AND s.market_country = :market
   AND (s.trading_amount, s.stock_id) < (:ta, :id)   -- 튜플 비교
 ORDER BY s.trading_amount DESC, s.stock_id DESC
 LIMIT :size + 1;
```
**`stock_id` 를 반드시 함께 넣으세요.** 거래대금이 완전히 같은 종목이 있으면 `trading_amount` 만으로는 그 경계에서 순서가 매번 달라져 항목이 중복되거나 사라집니다. `stock_id` 를 2차 정렬 키로 두면 순서가 유일하게 결정됩니다. PostgreSQL 의 튜플 비교 `(a, b) < (:a, :b)` 를 쓰면 `a < :a OR (a = :a AND b < :b)` 를 직접 쓰지 않아도 되고, `(trading_amount DESC, stock_id DESC)` 복합 인덱스를 그대로 탑니다.

**커서 중에 유니버스가 갱신되면 어떻게 되나요?**
월요일 08:00 배치가 도는 그 순간 사용자가 2페이지를 넘기면, 새 유니버스 기준으로 조회되어 일부 종목이 빠지거나 나타납니다. **1주차에는 그냥 두세요** — 주 1회 갱신이라 실제로 걸릴 확률이 거의 없고, 오류가 아니라 **"그 사이에 순위가 바뀌었다"는 정상 동작**입니다. 엄밀히 막으려면 커서에 유니버스 버전(갱신 시각)을 함께 담고 달라지면 409 로 첫 페이지부터 다시 받게 하면 됩니다 — **2주차 과제**로 두세요.

**`rank_no` 를 커서로 쓰지 마세요.** `rank_no` 는 배치가 통째로 다시 쓰는 값이라 갱신 직후 같은 번호가 다른 종목을 가리킵니다. **화면에 순위를 표시하는 용도로만** 쓰고, 페이지 이동의 기준은 거래대금 + stock_id 로 잡으세요.

---

## 인증 · 회원

### `POST /auth/signup`
회원가입 + 계좌 개설 + 모의 투자금 지급

**Request**
```json
{
  "email": "user@example.com",
  "password": "********",
  "nickname": "홍길동"
}
```

**Response · 201**
```json
{
  "userId": 1,
  "nickname": "홍길동",
  "accessToken": "eyJhbGciOi...",
  "account": {
    "accountId": 1,
    "roundNo": 1,
    "initialCash": "50000000",
    "cashBalance": "50000000"
  }
}
```
가입과 동시에 계좌를 만들고 5,000만원을 지급합니다. **`users` INSERT → `account` INSERT → `ledger_entry(INITIAL_DEPOSIT)` INSERT 가 한 트랜잭션**이어야 합니다.

| 에러 코드 | 상황 |
|---|---|
| `DUPLICATE_EMAIL` | 이미 가입된 이메일 |
| `INVALID_PASSWORD` | 비밀번호 정책 미충족 |

### `POST /auth/login`
**Request**
```json
{ "email": "user@example.com", "password": "********" }
```
응답은 회원가입과 동일한 형태입니다.

| 에러 코드 | 상황 |
|---|---|
| `LOGIN_FAILED` | 이메일 또는 비밀번호 불일치 |

### `GET /users/me` 🔒
내 정보
```json
{ "userId": 1, "email": "user@example.com", "nickname": "홍길동" }
```

---

## 시장

### `GET /market/status`
장 운영 상태 — 거래 버튼 활성화 판단

```json
{
  "markets": [
    {
      "marketCountry": "KR",
      "open": true,
      "opensAt": "2026-08-11T09:00:00+09:00",
      "closesAt": "2026-08-11T15:30:00+09:00",
      "nextOpensAt": null
    },
    {
      "marketCountry": "US",
      "open": false,
      "opensAt": null,
      "closesAt": null,
      "nextOpensAt": "2026-08-11T22:30:00+09:00"
    }
  ],
  "serverTime": "2026-08-11T12:36:59+09:00"
}
```
프론트는 이 응답으로 **거래 버튼 활성화**와 **"실시간 / 종가" 문구**를 판단합니다. 토스 `/market-calendar` 를 **하루 1회** 받아 캐싱한 값에서 계산합니다.
**미국 정규장 시각은 서머타임에 따라 1시간 이동합니다** — 서머타임(3월 둘째 일요일 ~ 11월 첫째 일요일) 22:30 ~ 05:00 KST ← 지금(8월) / 표준시(11월 첫째 일요일 ~ 3월 둘째 일요일) 23:30 ~ 06:00 KST. 하드코딩하면 11월 첫째 주에 **장 시작 후 한 시간 동안 거래가 막힙니다.**

### `GET /exchange-rates/latest`
랭킹 페이지 환율 배너

| 파라미터 | 필수 | 설명 |
|---|---|---|
| `base` | — | 기본 USD |
| `quote` | — | 기본 KRW |

```json
{
  "baseCurrency": "USD",
  "quoteCurrency": "KRW",
  "rate": "1398.50",
  "changeRate": "0.0016",
  "rateAt": "2026-08-11T15:00:00+09:00"
}
```
배너용 환율은 `exchange_rate` 테이블의 **최신 행**에서 응답합니다. **매시 정각 적재되므로 프론트 폴링도 1시간이면 충분** — 더 자주 불러도 같은 값입니다. 환율은 하루에 0.3~0.5% 정도만 움직입니다.
**체결에 쓰는 환율은 이 경로가 아닙니다.** 주문 처리 시에는 별도의 **1분 TTL 메모리 캐시**에서 가져옵니다 — 최대 1시간 오래된 값으로 체결하면 안 되니까요.

### `GET /exchange-rates/history`
환율 추이 그래프

| 파라미터 | 값 |
|---|---|
| `period` | `1d` · `1w` · `1m` · `3m` · `1y` |

```json
{
  "items": [
    { "rateAt": "2026-07-11T00:00:00+09:00", "rate": "1385.20" },
    { "rateAt": "2026-07-11T01:00:00+09:00", "rate": "1385.60" }
  ]
}
```
`exchange_rate` 테이블(매시 정각 적재)에서 집계합니다.

---

## 종목

### `GET /stocks/rankings`
거래대금 상위 100 · 커서 페이지네이션

| 파라미터 | 필수 | 설명 |
|---|---|---|
| `market` | O | `KR` / `US` |
| `size` | — | 기본 20, 최대 100 |
| `cursor` | — | 다음 페이지 커서 — 거래대금 + stockId 를 인코딩한 값 (공통 규칙 참고) |

**선택한 시장의 랭킹 100개는 기본적으로 20개씩 5페이지로 제공합니다.** 각 응답의 불투명한 커서를 다음 요청에 보내며, 커서는 계속 `(tradingAmount, stockId)` 튜플입니다.

**정렬 기준은 거래대금 내림차순 하나뿐입니다.** 1주차에는 `sort` 파라미터를 두지 않습니다 — 정렬 축이 늘어나면 커서 페이로드도 축마다 달라져야 하므로, **기준을 하나로 고정**하는 편이 구현도 설명도 단순합니다. 등락률순·거래량순은 2주차에 추가하세요.

**Response**
```json
{
  "items": [
    {
      "rank": 1,
      "symbol": "005930",
      "name": "삼성전자",
      "market": "KOSPI",
      "category": "INDIVIDUAL",
      "isDividend": false,
      "leverageFactor": null,
      "currency": "KRW",
      "lastPrice": "241500",
      "prevClose": "236050",
      "changeAmount": "5450",
      "changeRate": "0.0231",
      "tradingAmount": "1240000000000",
      "quoteAt": "2026-08-11T12:36:59+09:00",
      "realtime": true
    }
  ],
  "nextCursor": "eyJ0YSI6IjEyNDAwMDAwMDAwMDAiLCJpZCI6MTAyNH0",
  "hasNext": true
}
```
- **`realtime`** — `quoteAt` 이 현재 정규장 시간 내이면 `true`. 프론트가 **"12:36:59 기준 · 실시간"** 과 **"8월 11일 종가"** 를 구분하는 근거입니다.
- **화면 컬럼 매핑** — 종목명(`name`) · 티커(`symbol`) · 종류(`category`) · 현재가(`lastPrice`) · 전일대비(`changeAmount`, `changeRate`) · 거래대금(`tradingAmount`).
- `tradingAmount` 는 **최근 1주 누적**(`duration=1w`). **선정 기준이 곧 표시 값**이라 사용자가 "왜 이 순서인지"를 이해할 수 있습니다. 화면에 **"최근 1주 거래대금"** 이라고 밝혀주세요.

### `GET /stocks/search`
한글명 · 영문명 · 티커 부분 일치

| 파라미터 | 필수 | 설명 |
|---|---|---|
| `q` | O | 검색어 (2자 이상) |
| `size` | — | 기본 10 |

```json
{
  "items": [
    {
      "symbol": "005930",
      "name": "삼성전자",
      "englishName": "SamsungElec",
      "market": "KOSPI",
      "marketCountry": "KR",
      "category": "INDIVIDUAL"
    }
  ]
}
```
**검색 범위는 전 종목(약 8,500개)으로 확정했습니다.** `stock` 테이블 전체가 대상이며, 상위 100 여부와 무관하게 모두 검색됩니다. 클릭하면 상세 페이지도 정상적으로 열립니다 — 차이는 실시간이냐 전일 종가냐뿐입니다.
**상위 100 밖 종목은 `quote_snapshot` 이 비어 있습니다** — 스케줄러가 도는 대상은 상위 100 뿐이니까요. **상세를 여는 순간 `/prices` 와 `/candles` 를 함께 호출해 채우고 `quote_snapshot` 에 UPSERT** 해두세요. 한 번 조회된 종목은 다음부터 DB 에서 나갑니다. 검색 결과 목록에 가격을 같이 보여주려면 **20건을 `/prices` 배치 1콜로** 받아오세요 — 종목마다 따로 부르면 20콜이 되어 rate limit 에 걸립니다. **가격 없이 종목명만 먼저 보여주고 클릭 후에 채우는 편이 1주차에는 더 간단합니다.**
**토스가 미국 종목에도 한글명을 주므로 "엔비디아"로도 검색됩니다.** 다만 영문명 표기가 일정하지 않아(SamsungElec, HyundaiMtr, KIA CORP.) **공백 제거 + 소문자 정규화 후 부분 일치**를 권합니다. PostgreSQL **생성 컬럼**으로 검색 키를 만들어두면 편합니다.
**1주차 구현은 `LIKE '%검색어%'` 로 갑니다.** 8,500행이면 풀스캔이어도 수 ms 라 문제되지 않습니다. 다만 앞뒤 `%` 는 인덱스를 타지 않으니, 데이터가 커지면 **`pg_trgm` 확장 + GIN 인덱스**로 바꾸세요 — 쿼리는 그대로 두고 인덱스만 추가하면 됩니다.
**정렬은 ① 정확 일치 → ② 앞부분 일치 → ③ 부분 일치 순으로 주세요.** "삼성"을 쳤을 때 삼성전자가 미래에셋삼성... 보다 위에 와야 합니다.

| 에러 코드 | 상황 |
|---|---|
| `INVALID_QUERY` | 검색어 2자 미만 |

### `GET /stocks/{symbol}?marketCountry={KR|US}`
종목 상세 — 전 종목 대상

`marketCountry`는 필수입니다. 종목은 `(UPPER(symbol), market_country)` 조합으로 식별하므로 동일한 심볼의 국내·미국 종목을 구분합니다.

시세를 어디서 가져올지는 **"그 종목의 시장이 열려 있는가"** 로 갈립니다. 보는 사람의 시각이 아니라 **종목이 속한 시장 기준**입니다 — 한국 낮에 엔비디아를 열면 미국장이 닫혀 있으므로 전일 종가가 나갑니다.

| 상황 | 주가 | 차트 |
|---|---|---|
| 해당 시장 정규장 + 상위 100 | **5초 실시간** · `quote_snapshot` · `realtime: true` | 1분 주기 스케줄러 수집 |
| 장 마감 · 다른 나라 종목 또는 상위 100 밖 | 전일 종가 · `realtime: false` | 마지막 장의 분봉 (온디맨드 + 60초 캐시) |

```json
{
  "symbol": "005930",
  "name": "삼성전자",
  "englishName": "SamsungElec",
  "market": "KOSPI",
  "marketCountry": "KR",
  "currency": "KRW",
  "isinCode": "KR7005930003",
  "category": "INDIVIDUAL",
  "leverageFactor": null,
  "isDividend": false,
  "price": {
    "lastPrice": "241500",
    "prevClose": "236050",
    "changeAmount": "5450",
    "changeRate": "0.0231",
    "upperLimit": "313500",
    "lowerLimit": "169500",
    "quoteAt": "2026-08-11T12:36:59+09:00",
    "realtime": true
  },
  "info": {
    "marketCap": "1441000000000000",
    "sharesOutstanding": "5969782550",
    "listDate": "1975-06-11"
  },
  "warnings": [
    { "type": "INVESTMENT_WARNING", "label": "투자경고" }
  ],
  "tradable": true,
  "tradableReason": null
}
```

**핵심 필드**
| 필드 | 의미 |
|---|---|
| `tradable` | 지금 이 종목을 거래할 수 있는가 |
| `tradableReason` | `tradable=false` 일 때의 사유 코드 |

**`tradableReason` 값**
| 코드 | 화면 문구 |
|---|---|
| `MARKET_CLOSED` | 장 마감 · 09:00~15:30 거래 가능 |
| `NOT_IN_UNIVERSE` | 이 종목은 아직 거래를 지원하지 않아요 |
| `SUSPENDED` | 거래정지 종목 |
| `LIQUIDATION` | 정리매매 종목 |
| `QUOTE_NOT_FOUND` | 아직 적재된 시세가 없음 |

`quote_snapshot` 적재는 별도 시세 적재 작업이 담당합니다. 아직 시세가 없는 경우에도 종목 메타데이터와 null 가격 필드를 반환하며 `realtime: false`, `tradable: false`, `tradableReason: "QUOTE_NOT_FOUND"`로 표시합니다.

| 에러 코드 | 상황 |
|---|---|
| `STOCK_NOT_FOUND` | 존재하지 않는 심볼 |
| `INVALID_INPUT` | `marketCountry` 누락 또는 KR/US 이외의 값 |

### `GET /stocks/{symbol}/candles`
일봉 · 분봉 차트

| 파라미터 | 필수 | 값 |
|---|---|---|
| `marketCountry` | O | 심볼이 속한 시장 — `KR` · `US` |
| `interval` | O | 봉 하나의 시간 단위 — `1m` · `5m` · `10m` · `1d` · `1w` |
| `range` | O | 조회 기간 — `1D` · `1W` · `1M` · `6M` · `1Y` · `3Y` |

**유효 조합 — 그 외는 400 으로 거절**

| interval | 허용 range | 봉 개수 | 데이터 출처 |
|---|---|---|---|
| `1m` | `1D` | 최근 200 | 상위 100: 1분 주기 스케줄러 · 그 외 종목: 토스 `/candles?interval=1m` 온디맨드 |
| `5m` | `1D` · `1W` | 78 / 390 | 1분봉을 집계 |
| `10m` | `1W` | 195 | 1분봉을 집계 |
| `1d` | `1M` · `6M` · `1Y` | 22 / 130 / 250 | `daily_candle` |
| `1w` | `3Y` | 156 | 일봉을 집계 |

**토스는 `1m` 과 `1d` 두 가지만 제공합니다.** 5m·10m·1w 는 우리가 집계해서 만들어야 합니다(1분봉 200개를 5개씩 묶으면 5분봉 40개). **`1m` + `1Y` 같은 조합은 반드시 막으세요** — 1분봉으로 1년이면 12만 개가 됩니다.

**Response**
```json
{
  "symbol": "005930",
  "interval": "1d",
  "range": "6M",
  "currency": "KRW",
  "items": [
    {
      "at": "2026-08-11T00:00:00+09:00",
      "open": "237000",
      "high": "242500",
      "low": "236500",
      "close": "241500",
      "volume": "12345678"
    }
  ]
}
```

MVP 일봉은 금융 데이터 정합성을 위해 확정되어 저장된 `daily_candle`만 반환합니다. `quote_snapshot.last_price`만으로는 당일 시가·고가·저가를 알 수 없으므로 임의의 오늘 OHLC를 만들지 않습니다. 현재가는 `GET /stocks/{symbol}`에서 별도로 표시합니다.
**우리 API 에는 200봉 제한이 없습니다.** 토스의 `count` 상한 200 은 **수집할 때만** 해당합니다(일봉 200개 ≈ 10개월이라 1년치는 `before` 로 두 번 받습니다). `daily_candle` 에 쌓아두면 250봉을 그대로 내려주면 됩니다.

| 에러 코드 | 상황 |
|---|---|
| `INVALID_INTERVAL_RANGE` | 허용되지 않은 interval × range 조합 |
| `STOCK_NOT_FOUND` | 존재하지 않는 심볼 |

**MVP 범위는 `1d` 와 `1m` 둘입니다.** 지원 조합은 `1m+1D`, `1d+1M/6M/1Y`이며 나머지는 `INVALID_INTERVAL_RANGE`로 거절합니다. 일봉은 `daily_candle`(스케줄러가 마감 후 적재)에서 제공합니다. 랭킹 상위 100종목의 분봉은 `MARKET_DATA_CHART` 별도 5 TPS 그룹에서 20종목 단위로 순차 호출해 1분마다 수집합니다. 상위 100 밖 종목과 장외 상세 차트는 `minute_candle` 60초 캐시를 사용하는 온디맨드 방식입니다. 5m·10m·1w 집계는 2주차로 미룹니다.

**분봉은 상위 100종목은 스케줄러로 수집하고, 그 외에는 온디맨드로 60초 캐싱합니다**
```
// 1주차 분봉 처리 흐름
GET /stocks/NVDA/candles?marketCountry=US&interval=1m&range=1D
   ↓
minute_candle 에 60초 이내 데이터가 있나?
   ├ 있다  → DB 에서 바로 반환                      토스 호출 없음
   └ 없다  → 토스 /candles?interval=1m&count=200 호출 (상위 100 밖 또는 장외 상세)
             ↓  ON CONFLICT DO NOTHING 으로 UPSERT
             DB 에서 반환
```
**장외 시간이나 다른 나라 종목도 똑같이 동작합니다.** 장이 닫힌 종목에 `/candles` 를 부르면 마지막 장의 분봉이 그대로 옵니다 — 한국 낮에 엔비디아를 열면 전일 종가 + 지난 미국장 분봉 차트가 보입니다. 프론트는 `realtime` 값으로 "실시간 / 종가" 문구만 바꾸면 되고, **차트 자체는 분기가 필요 없습니다.** 빈 차트는 "고장난 화면"으로 읽히므로 **거래 불가와 조회 불가를 반드시 분리하세요.**
상위 100종목 수집기는 별도 `MARKET_DATA_CHART` 5 TPS 그룹에서 20종목 단위로 순차 호출하며 1분마다 실행합니다. 상위 100 밖 상세 요청은 온디맨드로만 처리하고 60초 캐시를 재사용하므로 아무도 보지 않는 종목까지 계속 수집하지 않습니다. 2주차에는 지정가 체결 판정과 5m·10m 집계를 추가합니다.
**한 번에 받을 수 있는 봉은 200개.** 국내 정규장 09:00~15:30 은 330분이라 하루치를 다 받으려면 `before` 로 2회 호출해야 합니다. **1주차 차트를 "최근 200분"으로 잡으면 1콜로 끝납니다** — 기본은 1콜로 두고 전체 보기를 누를 때만 2콜을 쓰는 편이 단순합니다.
**실측 필요** — `before` 가 inclusive 인지, 마감 동시호가 봉(15:30)이 존재하는지. 15:30 봉이 없으면 330개가 아니라 329개입니다. 경계 봉이 중복돼도 `PRIMARY KEY (stock_id, candle_at)` 라 `ON CONFLICT DO NOTHING` 이 걸러줍니다.

---

## 거래

### `GET /orders/quote` 🔒
수수료 · 세금 미리보기

```
?symbol=005930&marketCountry=KR&side=BUY&quantity=10
```

`marketCountry`는 `KR` 또는 `US`이며 필수입니다. 심볼은 시장마다 중복될 수 있으므로 서버는 `(symbol, marketCountry)`로 종목을 식별합니다.

**Response**
```json
{
  "symbol": "005930",
  "marketCountry": "KR",
  "side": "BUY",
  "quantity": "10",
  "executedPrice": "241500",
  "exchangeRate": "1",
  "grossAmount": "2415000",
  "fee": "242",
  "tax": "0",
  "netAmount": "2415242",
  "availableCash": "48240000",
  "quoteAt": "2026-08-11T12:36:59+09:00",
  "executable": true,
  "reason": null
}
```

**계산 규칙**
```
매수   netAmount = grossAmount + fee           (예수금에서 차감)
매도   netAmount = grossAmount − fee − tax     (예수금으로 입금)
KR grossAmount = round(executedPriceKrw × quantity, 0)
US priceUsd    = round(executedPriceUsd, 2)
US grossAmount = round(priceUsd × quantity × exchangeRate, 0)
fee            = round(grossAmount × 0.0001, 0)  거래 수수료 0.01% (매수·매도 공통)
KR tax         = round(grossAmount × 0.002, 0)   (국내 매도만)
US secFeeUsd   = round(max(priceUsd × quantity × 0.0000206, $0.01), 2)
US tax         = round(secFeeUsd × exchangeRate, 0) (미국 매도만)
```
**예시 — 삼성전자 10주 @ 241,500**
```
매수  gross 2,415,000 + fee   242              = 2,415,242 차감
매도  gross 2,415,000 − fee   242 − tax 4,830  = 2,409,928 입금
```
- **시장별 요율을 `.env` 설정값으로 두고 하드코딩하지 마세요.** 국내 매도 세금은 0.2%, 미국 매도는 증권거래세 대신 SEC Fee `0.0000206`과 최소 `$0.01`을 적용합니다. 거래 수수료 0.01%는 두 시장 모두 적용합니다.
- 통화 경계마다 **HALF_UP**으로 반올림합니다. 미국 주문은 주당 달러 가격을 센트로 먼저 반올림합니다. 그 가격으로 `grossKrw`를 계산하고, 거래 수수료와 `secFeeUsd → secFeeKrw`도 각각 원 단위로 반올림합니다. 국내 주문은 원화 gross를 먼저 반올림한 뒤 fee·tax를 각각 계산하고 다시 반올림합니다. 최종 원장 금액은 정수로 보존해야 합계 불변식이 맞습니다.
- **견적과 실제 체결 사이에 가격이 바뀔 수 있습니다.** 견적은 참고값이고, 체결 시점에 서버가 다시 계산합니다.

### `POST /orders` 🔒
매수 · 매도 (시장가 즉시 체결)

**Request**
```json
{
  "accountId": 42,
  "clientOrderId": "018f2c9e-4a1b-7c3d-9e5f-1a2b3c4d5e6f",
  "symbol": "005930",
  "marketCountry": "KR",
  "side": "BUY",
  "quantity": "10"
}
```
`accountId`는 주문을 시작한 계좌 회차를 고정합니다. 주문 처리 중 포트폴리오가 초기화되어 해당 계좌가 `CLOSED`가 되면 새 ACTIVE 계좌로 주문을 넘기지 않고 `ACCOUNT_ROUND_CHANGED`로 거절합니다. 계좌 정보를 새로 조회한 뒤 사용자가 다시 주문할 때는 최신 `accountId`와 새로운 `clientOrderId`를 사용합니다. 이미 처리된 주문의 동일 요청 재시도는 계좌가 이후 종료되었더라도 최초 저장 결과를 반환합니다.

`clientOrderId` 는 프론트가 **UUID v4 로 생성**하며 한 계좌 안에서 한 번의 의도적인 주문을 식별합니다. 중복 클릭과 네트워크 재시도에는 같은 값을 유지하고, 사용자가 새 주문을 추가할 때는 새 값을 발급합니다. **같은 값과 요청 내용으로 재요청하면 시장 API 호출 없이 저장된 결과를 반환하고, 같은 값에 다른 요청 내용을 보내면 충돌로 거절합니다.**

실패 응답의 `data.retryPolicy`가 재시도 시 `clientOrderId` 처리 방법을 알려줍니다. `SAME_CLIENT_ORDER_ID`는 주문 행이 만들어지지 않은 실패이므로 같은 ID로 안전하게 재시도하고, `NEW_CLIENT_ORDER_ID`는 `REJECTED` 행이 최종 결과로 저장된 실패이므로 조건이 바뀐 뒤 새 ID를 발급합니다. `NOT_RETRYABLE`은 같은 ID의 요청 내용 충돌처럼 그대로 재전송해도 성공할 수 없는 요청입니다.

```json
{
  "code": "MARKET_CONTEXT_EXPIRED",
  "message": "시장 정보를 다시 확인한 뒤 주문해주세요",
  "timestamp": "2026-08-27T10:30:00+09:00",
  "data": {
    "retryPolicy": "SAME_CLIENT_ORDER_ID"
  }
}
```

클라이언트는 HTTP 상태나 오류 코드만으로 ID 재사용 여부를 추론하지 않고, 응답에 포함된 `data.retryPolicy`를 우선합니다. `retryPolicy`가 없는 잘못된 JSON 등의 요청은 기존 요청을 그대로 자동 재전송하지 않습니다.

**Response · 201**
```json
{
  "orderId": 1024,
  "status": "FILLED",
  "symbol": "005930",
  "marketCountry": "KR",
  "side": "BUY",
  "quantity": "10",
  "executedPrice": "241500",
  "exchangeRate": "1",
  "grossAmount": "2415000",
  "fee": "242",
  "tax": "0",
  "netAmount": "2415242",
  "quoteAt": "2026-08-11T12:36:59+09:00",
  "orderedAt": "2026-08-11T12:37:02+09:00",
  "account": {
    "cashBalanceAfter": "45824758"
  }
}
```
주문 응답의 `cashBalanceAfter`는 현재 조회 시점 잔액이 아니라 해당 주문의 최초 체결 원장에 기록된 **체결 직후 잔액**입니다. 멱등 재응답에서도 같은 감사 값을 반환합니다. 포트폴리오 평가와 현재 계좌 상태는 체결과 분리하며, 최신 값이 필요하면 `GET /accounts/me`를 조회합니다.

**서버 처리 순서**
```
① accountId 소유권 확인 및 clientOrderId 조회 — 동일 요청 재시도면 종료된 회차에서도 저장된 결과 즉시 반환
② 종목 조회 후 정적 검증 — 거래 대상 → 거래정지 → 정리매매
③ 정적 검증 통과 시에만 장 운영 정보와 미국 주문 실행 환율 조회(국내는 환율 1), 외부 데이터 준비 완료 시 checkedAt 기록
④ 요청의 accountId와 userId로 정확한 계좌를 SELECT ... FOR UPDATE, CLOSED이면 새 회차로 넘기지 않고 거절
⑤ clientOrderId 재확인 — 락 대기 중 동일 주문이 확정됐으면 저장 결과 반환
⑥ 신규 주문만 시장 컨텍스트 만료 검사 후 시세 조회 및 통화 일치 검증, 매도 시 holding FOR UPDATE (락 순서: account → holding)
⑦ 검증 — 거래 대상 → 거래정지 → 정리매매 → 장 운영 → 시세 시각 → 정산금액 → 예수금/보유수량
⑧ INSERT trade_order FILLED (트랜잭션 내부의 유효한 업무 거절은 REJECTED)
⑨ UPDATE account.cash_balance 및 holding 잠금·UPSERT
⑩ INSERT ledger_entry (append only, FILLED만 기록)
```

시장가 주문은 `PENDING`을 저장하지 않고 `locked_cash`나 `locked_quantity`도 변경하지 않습니다. 동결은 추후 지정가 주문 흐름에서만 사용합니다. 거절된 시장가 주문은 잔액·보유·원장을 변경하지 않습니다. 유효한 `clientOrderId`를 파싱한 뒤 발생한 입력 필드 검증 실패, 외부 시장 데이터 실패, 정적 사전 검증 실패, 락 대기 중 시장 컨텍스트 만료, 시세 통화 불일치는 주문 행을 만들지 않으며 `SAME_CLIENT_ORDER_ID`를 응답합니다. 트랜잭션 안에서 확정되어 `REJECTED` 행이 저장된 실패만 `NEW_CLIENT_ORDER_ID`를 응답하는 최종 결과입니다. 사전 검증 통과 후 락 획득 사이 종목 상태가 바뀌면 동일한 오류 코드라도 후자에 해당할 수 있으므로 프론트는 코드가 아니라 `data.retryPolicy`를 따릅니다. JSON 자체를 읽을 수 없거나 `clientOrderId`가 유효하지 않은 요청은 재사용할 정상 ID가 없으므로 이 규칙의 대상이 아닙니다.

시장가 주문 유스케이스는 최상위 트랜잭션 경계로만 실행합니다. 애플리케이션 진입점은 `Propagation.NEVER`로 외부 트랜잭션 안에서의 호출을 금지하고, 실제 DB 변경 서비스가 자체 `REQUIRED` 트랜잭션을 시작합니다. 따라서 커밋된 `REJECTED` 기록이 관련 없는 외부 업무의 롤백에 함께 사라지지 않습니다.

**거래 대상 범위** — 현재 MVP는 정기 수집되는 시장별 거래대금 상위 100종목만 거래하며 `is_ranked` 검증을 유지합니다. 추후 온디맨드 시세 환경이 도입되면 상위 100 밖 종목은 사용자가 견적 또는 주문을 요청할 때 Toss API에서 현재가와 거래 가능 정보를 받아 캐시에 저장한 뒤 거래합니다. 그 시점에 `is_ranked`를 거래 허용 조건에서 “정기 수집 대상 여부”로 역할을 축소하고 주문 정책을 함께 변경합니다. 온디맨드 조회가 없는 현재 단계에서는 상위 100 밖 주문을 열지 않습니다.

주문 수량은 1회 최대 **1,000,000주**이며 지수 표기는 허용하지 않습니다. 상한은 `trading.max-order-quantity` 설정으로 관리합니다.

**에러**
| 코드 | HTTP | 기본 재시도 정책 | 화면 문구 |
|---|---|---|---|
| `MARKET_CLOSED` | 422 | `NEW_CLIENT_ORDER_ID` | 지금은 거래할 수 없는 시간이에요 |
| `MARKET_CONTEXT_EXPIRED` | 422 | `SAME_CLIENT_ORDER_ID` | 시장 정보를 다시 확인한 뒤 주문해주세요 |
| `NOT_IN_UNIVERSE` | 422 | 처리 경로의 `data.retryPolicy` 확인 | 이 종목은 아직 거래를 지원하지 않아요 |
| `STOCK_SUSPENDED` | 422 | 처리 경로의 `data.retryPolicy` 확인 | 거래정지 종목이에요 |
| `STOCK_LIQUIDATION` | 422 | 처리 경로의 `data.retryPolicy` 확인 | 정리매매 종목이에요 |
| `INSUFFICIENT_CASH` | 422 | `NEW_CLIENT_ORDER_ID` | 주문가능금액이 부족해요 |
| `INSUFFICIENT_QUANTITY` | 422 | `NEW_CLIENT_ORDER_ID` | 보유 수량이 부족해요 |
| `STALE_QUOTE` | 422 | `NEW_CLIENT_ORDER_ID` | 시세 정보가 오래되었어요. 다시 시도해주세요 |
| `FUTURE_QUOTE` | 422 | `NEW_CLIENT_ORDER_ID` | 시세 기준 시각이 올바르지 않아요. 다시 시도해주세요 |
| `INVALID_SETTLEMENT_AMOUNT` | 422 | `NEW_CLIENT_ORDER_ID` | 정산 금액이 올바르지 않아요 |
| `QUOTE_CURRENCY_MISMATCH` | 502 | `SAME_CLIENT_ORDER_ID` | 시세 통화 정보가 올바르지 않아요 |
| `INVALID_QUANTITY` | 400 | `SAME_CLIENT_ORDER_ID` | 수량은 1주 이상의 정수로 입력해주세요 |
| `DUPLICATE_ORDER` | 409 | `NOT_RETRYABLE` | 이미 처리된 주문이에요 |
| `ACCOUNT_ROUND_CHANGED` | 409 | `NOT_RETRYABLE` | 포트폴리오가 초기화됐어요. 계좌 정보를 새로고침한 후 다시 주문해주세요 |

**`STALE_QUOTE`** 는 `trading.quote-max-staleness-seconds` 기준으로 `quote_at`이 오래되면 거절하고, **`FUTURE_QUOTE`** 는 서버 검증 시각보다 미래인 시세를 거절합니다. 외부 시장 데이터 준비 완료 후부터 계좌 락 획득까지의 컨텍스트 허용 시간은 별도 설정 `trading.execution-context-max-age-seconds`를 사용합니다.

---

## 계좌 · 마이페이지

### `GET /accounts/me` 🔒
계좌 요약

```json
{
  "accountId": 1,
  "roundNo": 1,
  "initialCash": "50000000",
  "cashBalance": "48240000",
  "stockValue": "2172300",
  "totalAsset": "50412300",
  "unrealizedPnl": "137300",
  "unrealizedPnlRate": "0.0675",
  "exchangeRate": "1398.50",
  "asOf": "2026-08-11T12:36:59+09:00"
}
```
**1주차는 평가손익만 제공합니다.** 실현손익은 체결 내역이 쌓인 뒤 2주차에 분리합니다. `stockValue` 는 `holding × quote_snapshot.last_price` 로 계산하며, 해외 종목은 `exchangeRate` 로 원화 환산합니다.

### `GET /accounts/me/holdings` 🔒
보유 종목

```json
{
  "items": [
    {
      "symbol": "005930",
      "name": "삼성전자",
      "currency": "KRW",
      "quantity": "6",
      "avgBuyPrice": "228000",
      "avgExchangeRate": "1",
      "lastPrice": "241500",
      "evaluationAmount": "1449000",
      "unrealizedPnl": "81000",
      "unrealizedPnlRate": "0.0592",
      "realtime": true
    }
  ],
  "asOf": "2026-08-11T12:36:59+09:00"
}
```
**보유 종목은 랭킹에서 빠져도 계속 시세를 수집해야 합니다.** 안 그러면 평가금액이 그 시점에 멈춰서 사용자에겐 명백한 버그로 보입니다.

### `GET /accounts/me/ledger` 🔒
체결 내역 — 원장 기준 · 커서 페이지네이션

**주문 목록이 아니라 원장(`ledger_entry`)을 보여줍니다.** "무엇을 샀나"가 아니라 "**돈이 어떻게 움직였나**"가 됩니다. 초기 지급과 포트폴리오 초기화까지 한 줄로 들어와 계좌의 전체 이력이 되고, `balanceAfter` 를 그대로 찍으면 사용자가 잔고 변화를 눈으로 따라갈 수 있습니다.

| 파라미터 | 필수 | 설명 |
|---|---|---|
| `cursor` | — | 이전 응답의 `nextCursor` |
| `size` | — | 기본 20 |
| `entryType` | — | 필터. 생략하면 전체 |

**Response**
```json
{
  "items": [
    {
      "entryId": 3041,
      "entryType": "BUY",
      "amount": "-2415242",        // gross 2,415,000 + fee 242
      "balanceAfter": "47584758",
      "exchangeRate": "1",
      "memo": "삼성전자 10주 @ 241,500 (수수료 포함)",
      "orderId": 1024,
      "symbol": "005930",
      "name": "삼성전자",
      "occurredAt": "2026-08-11T12:37:02+09:00"
    },
    {
      "entryId": 3040,
      "entryType": "INITIAL_DEPOSIT",
      "amount": "50000000",
      "balanceAfter": "50000000",
      "exchangeRate": "1",
      "memo": "모의투자금 지급",
      "orderId": null,
      "symbol": null,
      "name": null,
      "occurredAt": "2026-08-10T09:00:00+09:00"
    }
  ],
  "nextCursor": "eyJlbnRyeUlkIjozMDQwfQ",
  "hasNext": false
}
```

**`entryType` — 세 가지뿐입니다**
| 코드 | 부호 | 화면 문구 | amount |
|---|---|---|---|
| `INITIAL_DEPOSIT` | + | 모의투자금 지급 | `initial_cash` |
| `BUY` | − | 매수 | `−(gross + fee)` |
| `SELL` | + | 매도 | `+(gross − fee − tax)` |

**수수료·세금은 별도 항목으로 쪼개지 않고 매수·매도 금액에 포함합니다.** 원장 한 줄이 `trade_order.net_amount` 하나에 대응하므로 목록이 절반으로 짧아지고 커서 처리도 단순해집니다. 수수료 총액이 필요해지면 `SUM(trade_order.fee)` 로 언제든 구할 수 있습니다. **`RESET` 항목도 두지 않습니다** — 포트폴리오 초기화는 새 계좌를 만드는 일이라 새 계좌의 `INITIAL_DEPOSIT` 한 줄이 그 역할을 대신합니다.
**`exchangeRate`** — 체결 시점 환율. 원화 종목은 1, 미국 종목은 그때의 USD/KRW. `amount` 는 이미 원화 환산값이라 계산에 쓰이지는 않습니다 — **"이 거래를 얼마짜리 환율로 했는가"를 원장만 보고 알 수 있게 하는 감사 항목**입니다. 1주차 화면에는 안 띄워도 되지만, **지금 안 남기면 과거 값은 복원할 수 없습니다.**
**커서는 `entryId` 로 잡으세요. `occurredAt` 은 안 됩니다.** 연속 주문이면 TIMESTAMPTZ 정밀도 안에서 시각이 겹칠 수 있고, 그 경계에서 항목이 누락되거나 무한 루프에 빠집니다. `entryId` 는 단조 증가라 **중복도 누락도 구조적으로 불가능**합니다. 최신순이므로 `WHERE account_id = ? AND entry_id < :cursor ORDER BY entry_id DESC LIMIT :size + 1` 로 조회하고, `size + 1` 번째 행의 존재 여부로 `hasNext` 를 판단합니다.
**거절된 주문은 원장에 남지 않습니다.** 돈이 안 움직였으니까요. "왜 안 됐는지"를 보여주려면 `trade_order` 기반 주문 내역 탭을 따로 두거나 2주차로 미루세요. 1주차 화면에는 원장 하나만 있으면 충분합니다.

### `POST /accounts/me/reset` 🔒
포트폴리오 초기화

**Request**
```json
{
  "accountId": 1
}
```

`accountId`는 `GET /accounts/me`에서 받은 현재 활성 계좌 ID입니다. 중복 클릭과 네트워크 재시도에는 같은 값을 유지합니다. 같은 계좌 ID로 성공 요청을 다시 보내면 회차를 추가하지 않고 직전에 생성한 계좌를 그대로 반환합니다. 재시도 응답의 `cashBalance`도 현재 조회 잔액이 아니라 최초 초기화 직후의 `initialCash` 값입니다. 사용자가 새 회차를 다시 초기화하려면 새로 조회한 활성 `accountId`를 보냅니다.

**Response · 200**
```json
{
  "accountId": 2,
  "roundNo": 2,
  "initialCash": "50000000",
  "cashBalance": "50000000"
}
```

**서버 처리**
```
SELECT ... FROM account WHERE account_id = 요청값 AND user_id = 현재 사용자 FOR UPDATE;
UPDATE account SET status='CLOSED', closed_at=:resetAt WHERE account_id = 요청값;
INSERT INTO account (user_id, round_no, ...) VALUES (?, 이전+1, 50000000, 50000000);
INSERT INTO ledger_entry (entry_type='INITIAL_DEPOSIT', occurred_at=:resetAt, ...);
```
**삭제가 아니라 새 회차 계좌 개설입니다.** 기존 원장·체결내역·보유종목은 그대로 보존되고, 조회 시 새 `account_id` 기준이라 화면에서는 자동으로 비워집니다. 나중에 **"지난 회차 성적"** 기능으로 확장할 수 있습니다. **프론트는 확인 모달을 반드시 띄우세요.**

기존 계좌 종료, 신규 계좌 개설, 초기 지급 원장은 한 트랜잭션이며 같은 UTC `resetAt`을 사용합니다. 현재 시장가 주문과는 계좌 행 잠금으로 직렬화됩니다. 향후 지정가 주문의 동결액 또는 동결 수량이 남아 있으면 `ACCOUNT_HAS_PENDING_ORDERS`(409)로 초기화를 거절합니다. 요청 계좌보다 두 회차 이상 진행된 상태에서 오래된 ID를 다시 보내면 `ACCOUNT_RESET_CONFLICT`(409)를 반환합니다.

---

## 화면 ↔ API 매핑

| 화면 | 호출 API |
|---|---|
| 메인 | `/market/status` (선택) |
| 주식 랭킹 | `/exchange-rates/latest` · `/stocks/rankings` · `/stocks/search` |
| 종목 상세 | `/stocks/{symbol}` · `/stocks/{symbol}/candles` |
| 거래 패널 | `/orders/quote` · `POST /orders` |
| 마이페이지 | `/accounts/me` · `/accounts/me/holdings` · `/accounts/me/ledger` |
| 포트폴리오 초기화 | `POST /accounts/me/reset` |
| 이용 가이드 | 없음 (정적 콘텐츠) |
| 회원가입 유도 | 1주차에는 호출 없음 — 화면 UX 만 만들고 `/auth/*` 는 2주차에 붙입니다 |

## 폴링 정책

### 서버 수집 스케줄 확정

**프론트가 폴링하는 것은 우리 API 이고, 우리 서버가 토스를 호출하는 주기는 아래와 같습니다.** 둘은 완전히 분리되어 있습니다 — **사용자가 100명이 되어도 토스 호출량은 그대로입니다.**

| 시각 (KST) | 주기 | 하는 일 |
|---|---|---|
| 월 07:00 | 주 1회 | 전체 종목 마스터 갱신 — `/stocks/all` + `/stocks` 배치 |
| 월 08:00 | 주 1회 | 국내 거래대금 상위 100 선정 — `/rankings?market=KR&duration=1w` · 1콜 |
| 월 21:00 | 주 1회 | 미국 거래대금 상위 100 선정 — 1콜. 미국장 시작 1시간 30분 전 |
| 08:50 | 일 1회 | 국내 `prev_close` ← 전일 종가. 상하한가 동시 수집 |
| 09:00 ~ 15:30 | 5초 | 국내 상위 100 현재가 — `/prices` 배치 1콜 (한도의 1.3%) |
| 09:00 ~ 15:30 | 1분 | 국내 상위 100 분봉 — 별도 `MARKET_DATA_CHART` 5 TPS 그룹에서 20종목 단위 순차 호출 |
| 15:40 | 일 1회 | 국내 일봉 적재 — 마감 10분 후 |
| 22:00 * | 일 1회 | 미국 `prev_close` 갱신 — 정규장 시작 30분 전 |
| 22:30 ~ 05:00 * | 5초 | 미국 상위 100 현재가 — 배치 1콜. 겨울에는 23:30 ~ 06:00 |
| 22:30 ~ 05:00 * | 1분 | 미국 상위 100 분봉 — 별도 `MARKET_DATA_CHART` 5 TPS 그룹에서 20종목 단위 순차 호출 |
| 05:10 * | 일 1회 | 미국 일봉 적재 — 마감 10분 후. 겨울에는 06:10 |
| 매시 정각 | 1시간 | 환율 적재 — 하루 24콜 |

**국내장과 미국장은 시간대가 겹치지 않습니다.** 09:00~15:30 과 22:30~05:00 이라 **같은 순간에 도는 수집기는 언제나 하나**. 합산 부하를 걱정할 필요가 없습니다.
\* **미국 시각은 서머타임에 따라 1시간 이동** — 하드코딩하지 말고 `/market-calendar/US` 의 세션 시각을 그대로 쓰세요.
**스케줄러에 넣지 않은 것** — 상위 100 밖 종목의 시세와 장외 분봉은 **사용자가 상세를 열 때 온디맨드로 채웁니다.** 상위 100 분봉 수집은 1주차 스케줄러에 포함하며, 2주차에는 지정가 체결 판정과 집계를 추가합니다.

### 클라이언트 폴링 정책

| 대상 | 주기 | 엔드포인트 |
|---|---|---|
| 랭킹 목록 | 5초 | `/stocks/rankings` |
| 종목 상세 | 5초 | `/stocks/{symbol}` |
| 마이페이지 | 10초 | `/accounts/me` + `/holdings` |
| 차트 | 60초 | `/stocks/{symbol}/candles` |
| 환율 배너 | 1시간 | `/exchange-rates/latest` |

**세 가지를 꼭 넣으세요.**
① **백그라운드 탭에서는 폴링 중단** — `document.visibilityState` 확인만으로 실사용 트래픽이 절반 가까이 줄어듭니다.
② **장 마감 시 폴링 중단** — `/market/status` 의 `open` 이 `false` 면 갱신할 것이 없습니다. 수집기도 함께 멈추므로 `quote_snapshot.last_price` 에 종가가 그대로 남아 자동으로 전일 종가 역할을 합니다. 차트는 `minute_candle` 에 쌓아둔 마지막 장 분봉을 그대로 보여주면 됩니다.
③ **응답에 다음 조회 시각 힌트** — 서버 수집 주기(5초)와 클라이언트 폴링 주기가 어긋나면 지연이 누적됩니다. `nextUpdateAt` 을 담고 그 직후에 재요청하면 지연이 고정됩니다.
```json
{ "asOf": "...", "nextUpdateAt": "2026-08-11T12:37:04+09:00", "items": [...] }
```

---

## 남은 결정 사항

구현 시작 전에 **팀 회의에서 한 번에 정**하시면 중간에 막히지 않습니다. **아래 확정 항목은 이미 정해져서 목록에서 뺐습니다.**

**확정**
| 항목 | 결정 |
|---|---|
| 검색 범위 | 전 종목(약 8,500개) · `LIKE '%q%'` |
| 수수료 · 세율 | 수수료 0.01%(매수·매도) · 증권거래세 0.2%(매도만) |
| 인증 | 1주차 미구현 — 시드 사용자 1명 고정 |
| 소수점 거래 | 2주차 — 1주차는 정수 주 단위만. **화면에서 토글 자체를 제거했습니다** |
| 체결 내역 | 원장 기준 `GET /accounts/me/ledger` |
| 원장 항목 | 매수 · 매도 · 초기지급 3종. 수수료·세금은 매수·매도 금액에 포함(한 줄) |
| 랭킹 정렬 · 커서 | 거래대금 내림차순 · 커서는 `(tradingAmount, stockId)` |
| 분봉 수집 | 상위 100종목은 1분마다 20종목 단위 순차 호출(5 TPS); 상위 100 밖 상세는 온디맨드 + 60초 캐시 |
| 유니버스 갱신 | 월요일 KR 08:00 · US 21:00 |
| 배당주 판정 | 1주차 비활성 |

**아직 결정 필요**
| 항목 | 선택지 |
|---|---|
| `before` 경계 | 토스 `/candles` 의 `before` 가 inclusive 인지, 마감 동시호가(15:30) 봉이 존재하는지 실측 필요 |
| `STALE_QUOTE` 임계값 | 15초 기준이 적절한지 |
| 인증 방식 (2주차) | JWT vs 세션 쿠키 |
| 소수점 자릿수 (2주차) | 미국 주식 최소 주문 단위 (0.1? 0.001?) |

---

## 2주차 이후 예정

| 엔드포인트 | 내용 |
|---|---|
| `POST /auth/signup` · `/auth/login` | 인증 구현 — 1주차에는 화면만 있고 서버는 시드 사용자 고정 |
| `POST /orders` | 지정가 주문 (`limitPrice`, `PENDING` 상태) |
| `POST /orders` (소수점) | 미국 종목 소수점 주문 개방. 그때 `allowsFractional` 필드를 종목 상세 응답에 추가하고, 미국 종목에서만 입력 단위를 바꿉니다 |
| `GET /accounts/me/orders` | 주문 내역 탭 — 거절된 주문까지 포함 (원장에는 안 남음) |
| `DELETE /orders/{id}` | 주문 취소 |
| `GET /accounts/me/assets/history` | 자산 추이 그래프 (일별 스냅샷) |
| `GET /accounts/me/report` | 투자 습관 진단 |
| `GET /stocks/{symbol}/orderbook` | 호가 |
| WebSocket | 실시간 시세 push (폴링 대체) |

**지금 만들지는 않지만 URL 설계가 충돌하지 않게 미리 자리를 잡아둔 것입니다.**

---
> 모의 주식 트레이딩 서비스 · 1주차 MVP API 명세서 · `erd.md` · `wireframe.md` 와 함께 보세요
