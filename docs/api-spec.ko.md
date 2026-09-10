# 모의 주식 트레이딩 서비스 — API 명세서

> **버전**: 3주차 MVP · 26.09.03 ~ 09.09 · ERD 와 와이어프레임에서 도출
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

Stateless JWT (access + refresh) 토큰을 사용해 인증합니다. 보호 엔드포인트는 헤더에 토큰을 담아야 합니다:
```http
Authorization: Bearer <accessToken>
```

- Access Token 유효기간 기본값은 15분입니다 (`JWT_ACCESS_TTL: 15m`).
- Refresh Token 유효기간 기본값은 7일입니다 (`JWT_REFRESH_TTL: 7d`).
- `X-User-Id` 헤더는 더 이상 지원하지 않으며 401 `UNAUTHORIZED`로 거절됩니다.
- 만료된 토큰은 401 `TOKEN_EXPIRED`, 변조·형식 오류 토큰은 401 `INVALID_TOKEN`을 반환합니다.

| 구분 | 대상 |
|---|---|
| 비로그인 허용 | 회원가입 · 로그인 · 토큰 갱신 · 랭킹 · 검색 · 종목 상세 · 차트 · 환율 · 이용 가이드 |
| 🔒 로그인 필수 | 로그아웃 · `/users/me` (GET/PATCH/DELETE) · `/users/me/password` (PUT) · 주문 · 계좌 · 보유종목 · 체결내역 · 포트폴리오 초기화 |
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
  "code": "INSUFFICIENT_CASH",
  "message": "주문가능금액이 부족합니다.",
  "timestamp": "2026-08-23T14:02:11+09:00",
  "data": { "required": "2415242", "available": "1200000" }
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

환율·비율은 계산값을 반올림하지 않고 불필요한 후행 0만 제거합니다. 따라서 DB의 `NUMERIC(19,6)`에서 읽은 `1.000000`도 API에서는 `"1"`이며, 최초 처리와 DB 재조회 응답의 문자열이 같습니다. `avgBuyPrice`는 통화 표시 단위로 미리 반올림하지 않고 이동평균의 저장 정밀도인 소수점 4자리까지 반환합니다. 원화 화면 표시는 프론트에서 마지막에 원 단위 `HALF_UP`으로 반올림합니다.

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
  "password": "Password123!",
  "nickname": "홍길동"
}
```

**Response · 201**
```json
{
  "userId": 1,
  "email": "user@example.com",
  "nickname": "홍길동",
  "accessToken": "eyJhbGciOi...",
  "refreshToken": "eyJhbGciOi...",
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
| `EMAIL_DUPLICATED` | 이미 가입된 이메일 |
| `NICKNAME_DUPLICATED` | 이미 사용 중인 닉네임 |
| `INVALID_INPUT` | 이메일/비밀번호/닉네임 형식 오류 |

### `POST /auth/login`
**Request**
```json
{
  "email": "user@example.com",
  "password": "Password123!"
}
```
응답은 회원가입과 동일한 형태입니다 (200 OK).

| 에러 코드 | 상황 |
|---|---|
| `LOGIN_FAILED` | 이메일 또는 비밀번호 불일치, 또는 비활성(탈퇴/휴면) 회원 |

### `POST /auth/refresh`
유효한 Refresh Token으로 새 Access Token을 재발급합니다.

**Request**
```json
{
  "refreshToken": "eyJhbGciOi..."
}
```

**Response · 200**
```json
{
  "accessToken": "eyJhbGciOi..."
}
```

| 에러 코드 | 상황 |
|---|---|
| `TOKEN_EXPIRED` | 만료된 Refresh Token |
| `INVALID_TOKEN` | 위조/형식 불일치 토큰이거나 탈퇴 회원 |

### `POST /auth/logout` 🔒
Stateless 로그아웃. 서버 세션이 없으므로 클라이언트가 보관 중인 토큰을 파기합니다.

**Response · 200**
빈 바디.

| 에러 코드 | 상황 |
|---|---|
| `UNAUTHORIZED` | 인증 토큰 누락 |
| `TOKEN_EXPIRED` | 만료된 토큰 |
| `INVALID_TOKEN` | 유효하지 않은 토큰 |

### `GET /users/me` 🔒
내 정보 조회

**Response · 200**
```json
{
  "userId": 1,
  "email": "user@example.com",
  "nickname": "홍길동"
}
```

| 에러 코드 | 상황 |
|---|---|
| `UNAUTHORIZED` | 인증 토큰 누락 또는 유효하지 않음 |
| `USER_NOT_FOUND` | 회원을 찾을 수 없거나 비활성 상태 |

### `PATCH /users/me` 🔒
닉네임 변경

**Request**
```json
{
  "nickname": "새닉네임"
}
```

**Response · 200**
```json
{
  "userId": 1,
  "email": "user@example.com",
  "nickname": "새닉네임"
}
```

| 에러 코드 | 상황 |
|---|---|
| `UNAUTHORIZED` | 인증 토큰 누락 또는 유효하지 않음 |
| `USER_NOT_FOUND` | 회원을 찾을 수 없거나 비활성 상태 |
| `NICKNAME_DUPLICATED` | 다른 회원이 이미 사용 중인 닉네임 |
| `INVALID_INPUT` | 닉네임 길이 2~20자 미충족 |

### `PUT /users/me/password` 🔒
비밀번호 변경

**Request**
```json
{
  "currentPassword": "Password123!",
  "newPassword": "NewPassword123!"
}
```

**Response · 200**
```json
{
  "userId": 1,
  "email": "user@example.com",
  "nickname": "홍길동"
}
```

| 에러 코드 | 상황 |
|---|---|
| `UNAUTHORIZED` | 인증 토큰 누락 또는 유효하지 않음 |
| `USER_NOT_FOUND` | 회원을 찾을 수 없거나 비활성 상태 |
| `INVALID_PASSWORD` | 현재 비밀번호 불일치 |
| `INVALID_INPUT` | 새 비밀번호 정책(8~64자) 미충족 |

### `DELETE /users/me` 🔒
회원 탈퇴 (Soft-delete: 회원 상태 `WITHDRAWN`, 활성 계좌 `CLOSED` 전환)

**Request**
```json
{
  "currentPassword": "NewPassword123!"
}
```

**Response · 200**
| 에러 코드 | 상황 |
|---|---|
| `UNAUTHORIZED` | 인증 토큰 누락 또는 유효하지 않음 |
| `USER_NOT_FOUND` | 회원을 찾을 수 없거나 비활성 상태 |
| `INVALID_PASSWORD` | 현재 비밀번호 불일치 |
| `ACCOUNT_NOT_FOUND` | 활성 계좌를 찾을 수 없음 |

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
  "rate": "1398.5",
  "changeRate": "0.0016",
  "validFrom": "2026-08-11T15:00:00+09:00"
}
```
배너는 매분 적재하는 `exchange_rate` 최신 행을 조회하며 프론트도 1분마다 폴링합니다. `validFrom`은 수신 시각이 아닌 원본 유효 시작 시각입니다.
**체결도 동일 DB를 사용**하되 표시용 `midRate` 대신 `rate`를 적용합니다. 사용 전·금융 잠금 후 원본 유효기간과 미래 수신 시각을 검증하며 메모리 TTL이나 요청 경로의 외부 폴백은 없습니다.

### `GET /exchange-rates/history`
환율 추이 그래프

| 파라미터 | 값 |
|---|---|
| `period` | `1d` · `1w` · `1m` · `3m` · `1y` |

```json
{
  "items": [
    { "validFrom": "2026-07-11T00:00:00+09:00", "rate": "1385.20" },
    { "validFrom": "2026-07-11T01:00:00+09:00", "rate": "1385.60" }
  ]
}
```
DB에서 버킷별 마지막 원본만 선택합니다. `1d`는 1분, `1w`는 30분, `1m`은 2시간, `3m`은 6시간, `1y`는 1일 버킷이며 Asia/Seoul(KST) 자정에 정렬합니다. 요청 시작부터 현재까지 조회하고 빈 버킷은 생략합니다. 선택한 원본 `validFrom`과 반올림하지 않은 표시 환율을 보존하며 장기 조회에는 분 단위 원본 전체를 전송하지 않습니다. 시간축과 십자선 라벨은 KST 기준으로 표시하되 시간대 접미사는 붙이지 않습니다. 그래프 간격과 별개로 십자선은 `1d`만 `YYYY-MM-DD HH:mm`, 나머지는 `YYYY-MM-DD`로 표시합니다. 저장 시각은 UTC를 유지합니다.

환율 이력 모달은 열린 상태에서 화면이 보일 때 1분마다 갱신합니다. 요청은 중복 실행하지 않으며 이전 기간 또는 닫힌 모달의 늦은 응답을 무시합니다. 재조회 시 표시 시간 범위를 유지하고 실패하면 이전 그래프와 안내를 표시한 뒤 다음 주기에 재시도합니다. 최초 조회와 기간 변경 시에만 전체 데이터를 화면에 맞춥니다.

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
**현재가는 정규장 중 랭킹·활성 지정가 주문 종목만 5초 목표로 수집합니다.** 그 외는 상세 온디맨드 조회와 5초 수집 캐시를 사용합니다. 진행 중 요청을 공유하고 원본 quoteAt은 수집 시각으로 교체하지 않습니다. 비랭킹 주문과 견적에도 랭킹 종목과 동일한 신선도 검증을 적용합니다.
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
| `STOCK_NOT_TRADABLE` | 현재 거래를 지원하지 않는 종목이에요 |
| `SUSPENDED` | 거래정지 종목 |
| `LIQUIDATION` | 정리매매 종목 |
| `QUOTE_NOT_FOUND` | 아직 적재된 시세가 없음 |

`quote_snapshot` 적재는 별도 시세 적재 작업이 담당합니다. 아직 시세가 없는 경우에도 종목 메타데이터와 null 가격 필드를 반환하며 `realtime: false`, `tradable: false`, `tradableReason: "QUOTE_NOT_FOUND"`로 표시합니다.

| 에러 코드 | 상황 |
|---|---|
| `STOCK_NOT_FOUND` | 존재하지 않는 심볼 |
| `INVALID_INPUT` | `marketCountry` 누락 또는 KR/US 이외의 값 |


### `GET /stocks/{symbol}/financials?marketCountry=KR`
국내 종목 산업분류 및 재무제표 조회 — 캐시 우선

`GET /stocks/{symbol}`에는 KIS 외부 호출을 섞지 않으며(시세 지연 및 가용성 보호), 기업 재무정보는 이 전용 엔드포인트로 조회합니다.

| 파라미터 | 필수 | 값 |
|---|---|---|
| `marketCountry` | O | 시장 식별자 — `KR`만 지원 |

**응답 · 200**
```json
{
  "symbol": "005930",
  "marketCountry": "KR",
  "dataStatus": "FRESH",
  "industry": {
    "standard": { "code": "0326", "name": "전자부품, 컴퓨터, 영상, 음향 및 통신장비 제조업" },
    "large": { "code": "03", "name": "제조업" },
    "medium": { "code": "0326", "name": "전자부품, 컴퓨터, 영상, 음향 및 통신장비 제조업" },
    "small": { "code": "03261", "name": "반도체 제조업" }
  },
  "annual": [
    {
      "statementYearMonth": "202512",
      "balanceSheet": {
        "currentAssets": "...",
        "fixedAssets": "...",
        "totalAssets": "...",
        "currentLiabilities": "...",
        "fixedLiabilities": "...",
        "totalLiabilities": "...",
        "capitalStock": "...",
        "capitalSurplus": "...",
        "retainedEarnings": "...",
        "totalEquity": "..."
      },
      "incomeStatement": {
        "sales": "...",
        "operatingProfit": "...",
        "netIncome": "..."
      },
      "ratios": {
        "salesGrowthRate": "...",
        "operatingProfitGrowthRate": "...",
        "netIncomeGrowthRate": "...",
        "roe": "...",
        "eps": "...",
        "salesPerShare": "...",
        "bps": "...",
        "reserveRatio": "...",
        "debtRatio": "...",
        "netProfitMargin": "...",
        "operatingProfitMargin": "..."
      }
    }
  ],
  "quarterly": [],
  "syncedAt": {
    "industry": "2026-09-08T00:00:00Z",
    "annual": "2026-09-08T00:00:01Z",
    "quarterly": "2026-09-08T00:00:02Z"
  }
}
```

- **데이터 형식**: 모든 금액과 비율은 정밀도 보존을 위해 불필요한 후행 0이 없는 문자열(`FinancialDecimalFormatter.plain`)로 내려줍니다. `annual`, `quarterly` 배열은 결산연월(`statementYearMonth`) 내림차순 정렬입니다. null 필드는 백엔드 전역 `non_null` 정책에 따라 JSON에서 생략됩니다.
- **영업이익률 (`operatingProfitMargin`)**: 조회 시점에 `operatingProfit × 100 ÷ sales`로 계산하며 소수점 6자리 `HALF_UP`으로 반올림합니다. `sales`가 0 또는 null이면 null로 반환합니다.
- **`dataStatus`**:
  - `FRESH`: 세 그룹(산업 30일 / 30d, 재무 7일 / 7d)이 모두 TTL 안이거나 방금 갱신에 성공함. 정상 빈 응답으로 적재된 negative cache도 TTL 안이면 `FRESH`입니다.
  - `STALE`: 갱신이 필요하여 외부 KIS 호출을 시도했으나 실패하고 기존 캐시를 폴백으로 반환함.

| 에러 코드 | HTTP | 상황 |
|---|---|---|
| `INVALID_INPUT` | 400 | `marketCountry` 파라미터 누락 또는 형식 오류 |
| `STOCK_NOT_FOUND` | 404 | 존재하지 않는 종목 |
| `FINANCIALS_NOT_SUPPORTED` | 422 | 미국 주식, ETF, ETN 또는 6자리 숫자가 아닌 국내 종목코드 |
| `KIS_RATE_LIMITED` | 429 | KIS 호출 제한 발생 및 반환할 기존 캐시 없음 |
| `KIS_API_ERROR` | 502 | KIS 외부 통신/계약 오류 발생 및 반환할 기존 캐시 없음 |
| `KIS_API_UNAVAILABLE` | 503 | KIS 비활성화(`kis.enabled=false`) 상태이며 필요한 그룹의 캐시 없음 |
### `GET /stocks/{symbol}/candles`
일봉 · 분봉 차트

| 파라미터 | 필수 | 값 |
|---|---|---|
| `marketCountry` | O | 심볼이 속한 시장 — `KR` · `US` |
| `interval` | O | 봉 하나의 시간 단위 — `1m` · `5m` · `10m` · `1d` · `1w` |
| `range` | O | 조회 기간 — `1D` · `1W` · `1M` · `6M` · `1Y` |

**유효 조합 — 그 외는 400 으로 거절**

| interval | 허용 range | 봉 개수 | 데이터 출처 |
|---|---|---|---|
| `1m` | `1D` | 최근 200 | 상위 100: 1분 주기 스케줄러 · 그 외 종목: 토스 `/candles?interval=1m` 온디맨드 |
| `5m` | `1D` · `1W` | 78 / 390 | `candle_5m` (1분봉 연속 집계 뷰) |
| `10m` | `1W` | 195 | `candle_10m` (1분봉 연속 집계 뷰) |
| `1d` | `1M` · `6M` · `1Y` | 22 / 130 / 250 | `daily_candle` |
| `1w` | `6M` · `1Y` | 26 / 52 | `candle_1w` (일봉 연속 집계 뷰) |

**토스는 `1m` 과 `1d` 두 가지만 제공합니다.** 5m·10m·1w 는 우리가 만듭니다 — 자바로 묶지 않고 TimescaleDB 연속 집계 뷰에 맡깁니다. **`1m` + `1Y` 같은 조합은 반드시 막으세요** — 1분봉으로 1년이면 12만 개가 됩니다.

**데이터가 모자라면 있는 만큼만 돌려줍니다.** `5m+1W`(1분봉 1,950개 필요) 처럼 원본이 부족한 조합은 채워질 때까지 짧은 차트가 나옵니다. 분봉 백필이 토스 상한인 200개라 한 번에 메울 수 없습니다.

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
**우리 API의 1Y 응답 상한은 250봉이지만, 온디맨드 최초 백필은 외부 API 한 번으로 최신 200봉만 수집합니다.** 상세 또는 어떤 일봉 차트 요청으로 먼저 진입해도 같은 200봉을 저장하며, 이후 1M·6M·1Y 전환은 DB 데이터를 재사용합니다. 백필 완료 후에는 시장 캘린더의 장 마감 10분 뒤를 기준으로 최신 확정 거래일과 DB 최신 일봉을 비교하고, 유니버스 밖 종목도 오래된 경우에만 최신 200봉을 다시 UPSERT합니다. 같은 실행 중 같은 확정 거래일에 성공한 최신화 요청은 반복하지 않습니다. 따라서 저장 이력이 없는 종목의 1Y 응답은 최대 200봉이고, 스케줄러 등 별도 적재 이력이 더 있으면 최대 250봉을 반환합니다.

| 에러 코드 | 상황 |
|---|---|
| `INVALID_INTERVAL_RANGE` | 허용되지 않은 interval × range 조합 |
| `STOCK_NOT_FOUND` | 존재하지 않는 심볼 |

**지원 조합은 `1m+1D`, `5m+1D/1W`, `10m+1W`, `1d+1M/6M/1Y`, `1w+6M/1Y` 입니다.** 나머지는 `INVALID_INTERVAL_RANGE`로 거절합니다. 일봉은 `daily_candle`(스케줄러가 마감 후 적재)에서, 5m·10m·1w 는 연속 집계 뷰에서 제공합니다. 랭킹 상위 100종목의 분봉은 `MARKET_DATA_CHART` 별도 20 TPS 그룹에서 20종목 단위로 순차 호출해 1분마다 수집합니다. 상위 100 밖 종목과 장외 상세 차트는 `minute_candle` 60초 캐시를 사용하는 온디맨드 방식입니다.

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
상위 100종목 수집기는 별도 `MARKET_DATA_CHART` 20 TPS 그룹에서 20종목 단위로 순차 호출하며 1분마다 실행합니다. 상위 100 밖 상세 요청은 온디맨드로만 처리하고 60초 캐시를 재사용하므로 아무도 보지 않는 종목까지 계속 수집하지 않습니다. 2주차에는 지정가 체결 판정을 추가합니다.
**한 번에 받을 수 있는 봉은 200개.** 국내 정규장 09:00~15:30 은 330분이라 하루치를 다 받으려면 `before` 로 2회 호출해야 합니다. **1주차 차트를 "최근 200분"으로 잡으면 1콜로 끝납니다** — 기본은 1콜로 두고 전체 보기를 누를 때만 2콜을 쓰는 편이 단순합니다.
**실측 필요** — `before` 가 inclusive 인지, 마감 동시호가 봉(15:30)이 존재하는지. 15:30 봉이 없으면 330개가 아니라 329개입니다. 경계 봉이 중복돼도 `PRIMARY KEY (stock_id, candle_at)` 라 `ON CONFLICT DO NOTHING` 이 걸러줍니다.


### `GET /stocks/{symbol}/orderbook?marketCountry={KR|US}`
현재가 기반 가상 호가·가상 잔량 조회

모든 사용자가 동일한 가상 호가 스냅샷을 공유하며, 단일 요청으로 매도(ASK) 10개와 매수(BID)를 반환합니다. 국내 종목의 BID는 10개이고, 미국 종목은 가격이 허용하는 1~10개일 수 있습니다. 조회 요청은 호가를 새로 생성하지 않으며 DB에 저장된 활성 버전을 기준으로 단일 SQL 스냅샷으로 조회합니다.

| 항목 | 필수 | 설명 |
|---|---|---|
| `symbol` (경로 파라미터) | O | 종목 심볼 (예: `005930`, `NVDA`) |
| `marketCountry` (쿼리 파라미터) | O | 시장 국가 (`KR` / `US`, 대소문자 무관). 누락 또는 미지원 시 400 |
| 없음 | - | `depth`, `page`, `cursor` 파라미터는 받지 않으며, 서버는 ASK 10개와 시장별 가능한 BID 전부를 반환합니다 (KR: 10개, US: 1~10개) |

**Response 200** — `basePrice`와 레벨 `price`는 통화별 문자열입니다. KRW는 소수점 없는 원 단위, USD는 정확히 소수점 둘째 자리까지 표현합니다. 레벨 `quantity`는 `FinancialDecimalFormatter.plain()` 규칙의 문자열입니다. `initialQuantity`는 내부 감사용이며 공개 API에는 노출하지 않습니다.
```json
{
  "symbol": "005930",
  "marketCountry": "KR",
  "bookVersion": 1042,
  "revision": 3,
  "basePrice": "72000",
  "currency": "KRW",
  "quoteAt": "2026-09-03T01:15:30Z",
  "generatedAt": "2026-09-03T01:15:33Z",
  "virtual": true,
  "description": "현재가 기반 가상 호가·가상 잔량",
  "asks": [
    { "level": 1, "price": "72100", "quantity": "1500" },
    { "level": 2, "price": "72200", "quantity": "1440" },
    { "level": 3, "price": "72300", "quantity": "1380" },
    { "level": 4, "price": "72400", "quantity": "1290" },
    { "level": 5, "price": "72500", "quantity": "1200" },
    { "level": 6, "price": "72600", "quantity": "1080" },
    { "level": 7, "price": "72700", "quantity": "960" },
    { "level": 8, "price": "72800", "quantity": "840" },
    { "level": 9, "price": "72900", "quantity": "720" },
    { "level": 10, "price": "73000", "quantity": "600" }
  ],
  "bids": [
    { "level": 1, "price": "71900", "quantity": "1800" },
    { "level": 2, "price": "71800", "quantity": "1720" },
    { "level": 3, "price": "71700", "quantity": "1650" },
    { "level": 4, "price": "71600", "quantity": "1550" },
    { "level": 5, "price": "71500", "quantity": "1440" },
    { "level": 6, "price": "71400", "quantity": "1300" },
    { "level": 7, "price": "71300", "quantity": "1150" },
    { "level": 8, "price": "71200", "quantity": "1000" },
    { "level": 9, "price": "71100", "quantity": "860" },
    { "level": 10, "price": "71000", "quantity": "720" }
  ]
}
```

- `asks`는 매도 호가(최우선 매도 ASK 1부터 가격 오름차순 10개 레벨).
- `bids`는 매수 호가(최우선 매수 BID 1부터 가격 내림차순). 국내는 10개, 미국은 1~10개이며 저가 종목에서는 가능한 깊이까지만 반환합니다. 미국 BID가 10개 미만이면 마지막 레벨은 최저 유효 가격인 `$0.01`입니다.
- 응답의 `bookVersion`, `revision`, 레벨들은 단일 DB statement 스냅샷으로 일관성이 보장됩니다.

**Errors**

| 에러 코드 | HTTP | 발생 상황 |
|---|---|---|
| `INVALID_INPUT` | 400 | `marketCountry` 파라미터 누락 또는 미지원 (`KR`, `US` 외) |
| `STOCK_NOT_FOUND` | 404 | 존재하지 않는 종목 심볼 |
| `ORDER_BOOK_UNAVAILABLE` | 503 | 기능 비활성(`ORDERBOOK_ENABLED=false`), 거래 불가 종목(정지·정리매매·유니버스 이탈), 장 마감/세션 만료, 15초 초과 지연 시세, 미래 시세, 통화 불일치, 활성 버전 없음/불완전 |

GET 에러 응답은 주문 접수용 `retryPolicy`를 반환하지 않으며, 클라이언트는 일반 폴링 주기에 따라 재조회합니다.
---

## 거래

### `GET /orders/quote/market` 🔒
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

### `POST /orders/market` 🔒
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

시장가 멱등 응답은 주문 방향과 일치하고 `execution_id`가 있는 정상 원장을 조회합니다. 해당 원장이 없는 `FILLED` 주문은 `INTERNAL_ERROR`로 처리하며, 체결 연결 없는 과거 원장으로 응답하는 호환 처리는 지원하지 않습니다. 체결 존재·주문 연결은 DB 외래 키로 보장하므로 확인용 체결 추가 조회는 하지 않습니다.

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

**거래 대상 범위** — 시장가·지정가 주문과 두 견적 API는 비랭킹 종목도 지원합니다. 신규 주문 전 공통 상태 캐시로 상장 상태와 국내 거래정지·정리매매 정보를 확인하고, 원본 시세가 15초 이내인지 검증합니다. 재조회 성공만으로 오래된 원본 시세가 신선해지지 않습니다. 외부 준비는 계좌 락 전에 수행하고 금융 트랜잭션에서 DB 상태·시세 시각을 재검증합니다. 처리된 멱등 요청은 외부 호출 전에 반환합니다. 준비 실패는 거절 주문을 저장하지 않으며 같은 clientOrderId로 재시도할 수 있습니다(data.retryPolicy 확인).

주문 수량은 1회 최대 **1,000,000주**이며 지수 표기는 허용하지 않습니다. 상한은 `trading.max-order-quantity` 설정으로 관리합니다.

**에러**
| 코드 | HTTP | 기본 재시도 정책 | 화면 문구 |
|---|---|---|---|
| `MARKET_CLOSED` | 422 | `NEW_CLIENT_ORDER_ID` | 지금은 거래할 수 없는 시간이에요 |
| `MARKET_CONTEXT_EXPIRED` | 422 | `SAME_CLIENT_ORDER_ID` | 시장 정보를 다시 확인한 뒤 주문해주세요 |
| `STOCK_NOT_TRADABLE` | 422 | 처리 경로의 `data.retryPolicy` 확인 | 현재 거래를 지원하지 않는 종목이에요 |
| `STOCK_SUSPENDED` | 422 | 처리 경로의 `data.retryPolicy` 확인 | 거래정지 종목이에요 |
| `STOCK_LIQUIDATION` | 422 | 처리 경로의 `data.retryPolicy` 확인 | 정리매매 종목이에요 |
| `INSUFFICIENT_CASH` | 422 | `NEW_CLIENT_ORDER_ID` | 주문가능금액이 부족해요 |
| `INSUFFICIENT_QUANTITY` | 422 | `NEW_CLIENT_ORDER_ID` | 보유 수량이 부족해요 |
| `STALE_QUOTE` | 422 | `NEW_CLIENT_ORDER_ID` | 시세 정보가 오래되었어요. 다시 시도해주세요 |
| `FUTURE_QUOTE` | 422 | `NEW_CLIENT_ORDER_ID` | 시세 기준 시각이 올바르지 않아요. 다시 시도해주세요 |
| `INVALID_SETTLEMENT_AMOUNT` | 422 | 실제 경로의 `data.retryPolicy` 확인 | 정산 금액이 올바르지 않아요 |
| `QUOTE_CURRENCY_MISMATCH` | 502 | `SAME_CLIENT_ORDER_ID` | 시세 통화 정보가 올바르지 않아요 |
| `INVALID_QUANTITY` | 400 | `SAME_CLIENT_ORDER_ID` | 수량은 1주 이상의 정수로 입력해주세요 |
| `DUPLICATE_ORDER` | 409 | `NOT_RETRYABLE` | 이미 처리된 주문이에요 |
| `ACCOUNT_ROUND_CHANGED` | 409 | `NOT_RETRYABLE` | 포트폴리오가 초기화됐어요. 계좌 정보를 새로고침한 후 다시 주문해주세요 |

**`STALE_QUOTE`** 는 `trading.quote-max-staleness-seconds` 기준으로 `quote_at`이 오래되면 거절하고, **`FUTURE_QUOTE`** 는 서버 검증 시각보다 미래인 시세를 거절합니다. 외부 시장 데이터 준비 완료 후부터 계좌 락 획득까지의 컨텍스트 허용 시간은 별도 설정 `trading.execution-context-max-age-seconds`를 사용합니다.

미국 시장가도 환율 스냅샷의 수신 시각·`validFrom`·`validUntil`을 트랜잭션과 체결 생성에 전달합니다. 신규 주문은 계좌 잠금 후 컨텍스트 신선도와 별개로 원본 유효기간 및 수신 후 60초 TTL을 재검증하며, 체결 생성 시에도 같은 검증 시각과 실제 정산 환율의 일치를 확인합니다. 환율 근거가 없거나 만료/미래이면 `EXCHANGE_RATE_NOT_FOUND`(404, `SAME_CLIENT_ORDER_ID`)로 종료하며 주문·체결·원장을 저장하거나 트랜잭션 안에서 외부 재조회하지 않습니다. 국내는 외부 환율 조회 없이 1을 사용합니다. 기존 주문의 멱등 응답은 이 검사보다 먼저 저장 결과를 반환하며, DB에는 사용 환율만 저장합니다.

시장가 정산은 미국 단가를 센트 `HALF_UP`으로 반올림한 뒤 저장 범위를 검사합니다. 단가·정산 금액은 `NUMERIC(19,4)`, 수량·환율은 `NUMERIC(19,6)` 범위를 준수하며 환율 자체는 반올림하지 않습니다. 저장 범위 초과는 `INVALID_SETTLEMENT_AMOUNT` + `SAME_CLIENT_ORDER_ID`로 종료하고 주문·체결·원장을 남기지 않습니다. 계산 가능한 범위지만 최종 정산액이 0 이하인 경우는 기존대로 `REJECTED` 기록 후 `NEW_CLIENT_ORDER_ID`를 반환합니다.

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
  "exchangeRate": "1398.5",
  "asOf": "2026-08-11T12:36:59+09:00"
}
```
**1주차는 평가손익만 제공합니다.** 실현손익은 체결 내역이 쌓인 뒤 2주차에 분리합니다. `stockValue` 는 `holding × quote_snapshot.last_price` 로 계산하며, 해외 종목은 `exchangeRate` 로 원화 환산합니다. 종목별 평가손익의 취득원가는 수수료를 제외한 `holding.krw_purchase_amount`를 사용하며, 거래 수수료는 예수금에서 차감되므로 계좌 전체 수익에는 이미 반영됩니다.

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

**수수료·세금은 별도 항목으로 쪼개지 않고 각 체결의 매수·매도 금액에 포함합니다.** 정상 거래 원장 한 줄은 `execution_id`로 `trade_execution` 한 건에 연결됩니다. BUY는 `-net_amount_krw`, SELL은 `+net_amount_krw`를 기록하고, `balanceAfter`는 해당 체결 직후 계좌 잔액을 보존합니다. 시장가는 체결·원장 한 쌍을, 지정가는 소비한 호가 레벨마다 체결·원장 한 쌍을 생성하므로 동일 `orderId`의 원장이 여러 줄일 수 있습니다. `trade_order.net_amount`는 주문의 누적 정산액이지 각 원장 행의 금액이 아닙니다. 수수료 합계는 체결별 또는 주문별 단위에 맞춰 집계하며, 주문 누적 금액을 여러 원장 행에 조인한 뒤 합산하면 중복 집계됩니다. **`RESET` 항목도 두지 않습니다** — 초기화 시 새 계좌의 `INITIAL_DEPOSIT` 원장이 그 역할을 대신하며 체결 연결은 없습니다.
**`exchangeRate`** — 체결 시점 환율. 원화 종목은 1, 미국 종목은 그때의 USD/KRW. `amount` 는 이미 원화 환산값이라 계산에 쓰이지는 않습니다 — **"이 거래를 얼마짜리 환율로 했는가"를 원장만 보고 알 수 있게 하는 감사 항목**입니다. 1주차 화면에는 안 띄워도 되지만, **지금 안 남기면 과거 값은 복원할 수 없습니다.**
**커서는 `entryId` 로 잡으세요. `occurredAt` 은 안 됩니다.** 연속 주문이면 TIMESTAMPTZ 정밀도 안에서 시각이 겹칠 수 있고, 그 경계에서 항목이 누락되거나 무한 루프에 빠집니다. `entryId` 는 단조 증가라 **중복도 누락도 구조적으로 불가능**합니다. 최신순이므로 `WHERE account_id = ? AND entry_id < :cursor ORDER BY entry_id DESC LIMIT :size + 1` 로 조회하고, `size + 1` 번째 행의 존재 여부로 `hasNext` 를 판단합니다.
**거절된 주문이나 동결 자원만 변경하는 작업은 거래 원장을 생성하지 않습니다.** 실제 현금 이동이 없기 때문입니다. 지정가 접수는 자원을 동결하고, 취소·만료는 미체결 잔여분만 해제하며 기존 체결·원장 기록은 보존합니다. 주문 상태와 거절 사유는 `GET /accounts/me/orders`, 체결별 상세는 `GET /orders/{orderId}/executions`에서 조회합니다.

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
| 거래 패널 | `/orders/quote/market` · `POST /orders/market` |
| 마이페이지 | `/accounts/me` · `/accounts/me/holdings` · `/accounts/me/ledger` |
| 포트폴리오 초기화 | `POST /accounts/me/reset` |
| 이용 가이드 | 없음 (정적 콘텐츠) |
| 회원가입 유도 | `POST /auth/signup` · `POST /auth/login` |

## 폴링 정책

### 서버 수집 스케줄 확정

**프론트가 폴링하는 것은 우리 API 이고, 우리 서버가 토스를 호출하는 주기는 아래와 같습니다.** 둘은 완전히 분리되어 있습니다 — **사용자가 100명이 되어도 토스 호출량은 그대로입니다.**

| 시각 (KST) | 주기 | 하는 일                                                                              |
|---|---|--------------------------------------------------------------------------------------|
| 월 07:00 | 주 1회 | 전체 종목 마스터 갱신 — `/stocks/all` + `/stocks` 배치                               |
| 월 08:00 | 주 1회 | 국내 거래대금 상위 100 선정 — `/rankings?market=KR&duration=1w` · 1콜                |
| 월 21:00 | 주 1회 | 미국 거래대금 상위 100 선정 — 1콜. 미국장 시작 1시간 30분 전                         |
| 08:50 | 일 1회 | 국내 `prev_close` ← 전일 종가. 상하한가 동시 수집                                    |
| 국내 정규장(캘린더) | 5초 목표 | 랭킹·활성 지정가 주문 종목만 최대 200개씩 수집. |
| 09:00 ~ 15:30 | 1분 | 국내 상위 100 분봉 — 별도 `MARKET_DATA_CHART` 20 TPS 그룹에서 20종목 단위 순차 호출  |
| 15:40 ~ 17:10 | 30분 | 국내 일봉 적재 재시도 — 캘린더상 마감 10분 후부터, 당일 저장 완료 종목 제외          |
| 09:00 ET * | 일 1회 | 미국 `prev_close` 갱신 — 정규장 시작 30분 전 (KST 기준 서머타임 22:00, 표준시 23:00) |
| 미국 정규장(캘린더) | 5초 목표 | 랭킹·활성 지정가 주문 종목만 수집. 서머타임은 캘린더 적용. |
| 22:30 ~ 05:00 * | 1분 | 미국 상위 100 분봉 — 별도 `MARKET_DATA_CHART` 20 TPS 그룹에서 20종목 단위 순차 호출  |
| 미국 현지 16:10 ~ 17:10 * | 30분 | 미국 일봉 적재 재시도 — KST 서머타임 05:10~, 표준시 06:10~, 당일 저장 완료 종목 제외 |
| 매분 | 1분 | 환율 적재 — 하루 1,440회 예정. 공유 MARKET_INFO 제한 적용, 휴장일에도 실행. |

**국내장과 미국장은 시간대가 겹치지 않습니다.** 09:00~15:30 과 22:30~05:00 이라 **같은 순간에 도는 수집기는 언제나 하나**. 합산 부하를 걱정할 필요가 없습니다.
\* **미국 시각은 서머타임에 따라 1시간 이동** — 하드코딩하지 말고 `/market-calendar/US` 의 세션 시각을 그대로 쓰세요.
**온디맨드 보충** — 지속 수집 밖 종목은 상세 진입 시 공통 조정자로 조회하며 5초 수집 캐시를 재사용합니다. 일봉 백필·분봉 정책은 유지합니다.

### 클라이언트 폴링 정책

| 대상 | 주기 | 엔드포인트 |
|---|---|---|
| 랭킹 목록 | 5초 | `/stocks/rankings` |
| 종목 상세 | 5초 | `/stocks/{symbol}` |
| 마이페이지 | 10초 | `/accounts/me` + `/holdings` |
| 차트 | 60초 | `/stocks/{symbol}/candles` |
| 환율 배너 | 1분 | `/exchange-rates/latest` |

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
| 인증 | Stateless JWT access/refresh 토큰 · `Authorization: Bearer <accessToken>` |
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
| 소수점 자릿수 (2주차) | 미국 주식 최소 주문 단위 (0.1? 0.001?) |

---

## 2주차 이후 예정

### 확정 LIMIT 정산 계약 (#119)

계산기, 접수 API(#120), 공유 호가(#121), 엔진/워커/프리뷰(#122)를 연결합니다. MARKET은 가상 호가를 소비하지 않는 즉시 정산을 유지합니다.

- 정수 수량만 지원합니다. 지정가 × 전체 수량 × 접수 환율의 원화 반올림값에 수수료 반올림값을 합해 동결합니다. KR은 외부 환율 조회가 없으며 환율 상승 버퍼도 없습니다. 접수 환율은 체결 환율을 고정하지 않습니다.
- 매수는 해당 주문의 `reservedCash`에서 이번 실제 net만 차감합니다. 누적 net을 다시 빼거나 수량 비례로 동결을 재산정하지 않고 자유 예수금도 사용하지 않습니다. 엔진이 가능한 정수 수량으로 축소하며 1주도 불가능하면 보류합니다. 활성 매수 잔량이 남으면 동결액도 양수여야 합니다.
- 전량 체결은 결제 후 남은 동결까지 전부 해제합니다. 취소·만료는 기존 체결을 유지하며 잔여 동결만 해제합니다. 해제는 lockedCash 차감이지 cashBalance 증액이 아닙니다.
- 호가별 체결의 `netAmountKrw > 0`을 요구합니다. 0/음수 후보는 체결/원장 기록과 물량 소비 없이 보류하며 다음 호가 합산이나 건너뛰기로 이 조건을 우회하지 않습니다.
- 여러 유효 호가는 주문 누적 거래대금/수수료/세금의 순차 차액과 한 트랜잭션의 동일 환율을 사용합니다. 호가나 트랜잭션마다 SEC 최소액을 중복 부과하지 않으며 과거 체결에는 당시 환율을 유지합니다.
- `LimitOrderSettlementCalculator`는 주어진 후보를 계산하고 #122가 호가/수량 선택과 금융/물량 상태의 원자적 반영을 담당합니다. 환율은 60초 TTL과 원본 유효기간을 함께 적용하며 잠금 후 재검증합니다.

지정가 가격은 옵션 B(매수 지정가 이하 / 매도 지정가 이상 호가 체결), 만료는 접수한 정규 세션 종료 시각입니다. 모든 사용자가 동일한 가상 시장의 물량을 소비하지만, 사용자 주문끼리 직접 매칭하지는 않습니다. 한 사용자의 체결로 줄어든 공유 잔량은 다른 사용자의 체결에도 반영됩니다.

| 엔드포인트 | 내용 |
|---|---|
| `POST /orders/market` (소수점) | 미국 종목 소수점 주문 개방. 그때 `allowsFractional` 필드를 종목 상세 응답에 추가하고, 미국 종목에서만 입력 단위를 바꿉니다 |
| `GET /accounts/me/assets/history` | 자산 추이 그래프 (일별 스냅샷) |
| `GET /accounts/me/report` | 투자 습관 진단 |
| WebSocket | 실시간 시세 push (폴링 대체) |

**지금 만들지는 않지만 URL 설계가 충돌하지 않게 미리 자리를 잡아둔 것입니다.**

---
> 모의 주식 트레이딩 서비스 · 현재 API 명세서 · `erd.md` · `wireframe.md` 와 함께 보세요

## 지정가 주문 생애주기 (#120)

주문 상세·목록·지정가 접수·취소 응답에는 `symbol`, `name`, `marketCountry`를 제공합니다. 체결 목록 응답은 `{orderId, stock: {symbol, name, marketCountry}, items, nextCursor, hasNext}` 구조입니다. 종목 정보는 개별 체결 항목이 아닌 페이지 상위에 한 번 제공하며, 빈 목록에도 orderId와 stock을 반환합니다. 페이지네이션과 체결 정렬은 유지합니다. `currency` 필드는 반환하지 않으며 지정가·체결 단가는 KR이면 KRW, US이면 USD입니다. 원본 입력 통화는 `requestedLimitCurrency`로 구분합니다. 지정가 견적을 포함하여 가격 문자열은 KRW 원 단위, USD 소수점 두 자리로 통일합니다. 환율 정밀도와 정산 계산은 변경하지 않으며 gross/fee/tax/net/reservedCash/balanceAfter는 계속 KRW입니다. 경로·쿼리 파라미터 타입 변환 실패는 HTTP 400 `INVALID_INPUT` 및 문제 파라미터명을 담은 `data.field`로 응답하며 원본 입력값은 노출하지 않습니다.

주문 유형에 따라 엔드포인트를 분리합니다: 시장가는 `POST /orders/market` 및 `GET /orders/quote/market`, 지정가는 `POST /orders/limit` 및 `GET /orders/quote/limit`을 사용합니다. 요청 바디의 orderType은 받지 않으며 URL 경로로 주문 유형을 확정합니다. 시장가 요청의 미등록 필드는 무시하며 limitPrice/limitCurrency를 보내도 URL로 결정한 주문 유형은 바뀌지 않습니다. 기존 시장가 응답은 유지하며 프론트 요청 수정은 별도 담당 범위입니다.

LIMIT은 문자열 limitPrice와 limitCurrency를 받습니다. 국내는 KRW 원 단위, 미국은 KRW 원 단위 또는 USD 센트 단위를 허용합니다. 후행 0은 허용하지만 초과 자릿수·지수 표기는 거절합니다. 미국 원화 입력은 접수 환율로 나누어 HALF_UP 센트 반올림한 USD 지정가를 고정 저장합니다. 0달러가 되거나 저장 범위를 초과하면 거절합니다. 이후 원화 가격 한도를 계속 추적하는 주문은 아닙니다.

매수 동결액은 원본 입력을 기준으로 합니다. 원화는 입력 단가 × 전체 수량 + 원 단위 수수료, 달러는 단가 × 전체 수량 × 환율의 원 단위 반올림액 + 수수료입니다. 센트 환산 가격을 원화로 역산하지 않습니다. 매도는 수량만 동결합니다. 미국 접수는 매수/매도 모두 검증된 체결용 환율 스냅샷을 사용하여 접수 환율을 보존하고 원화 입력을 환산합니다. 실제 체결 환율은 별개입니다. 센트 환산 반올림만으로도 동결액이 부족해 일부 수량만 체결되거나 보류될 수 있습니다.

접수 성공은 `POST /orders/limit` 기준 201 OrderDetailResponse: orderId/accountId/stockId/orderType/side/status/quantity/filledQuantity/activeRemainingQuantity, requestedLimitPrice/requestedLimitCurrency/limitPrice/acceptanceExchangeRate, reservedCash, 누적 grossAmount/fee/tax/netAmount, rejectReason, orderedAt/expiresAt/closedAt입니다. activeRemainingQuantity는 활성 잔여 수량으로 종료 후 0입니다. 멱등 비교는 원본 가격의 수치와 입력 통화를 사용하고 새 환율로 재환산하지 않습니다. 현재 저장 상태를 반환하며 종료 주문을 재활성화하지 않습니다. 저장된 REJECTED는 같은 오류를 재생합니다. 기존 주문은 외부 조회 전에 확인하고, 잠금 후 회차 검사보다 먼저 재확인합니다.

견적은 acceptable/reason, availableCash/availableQuantity, expiresAt, 원본·환산 지정가, acceptanceExchangeRate, limitEstimate(grossAmount/fee/tax/netAmount/reservedCash), executionPreview(아래 #122 참조)를 제공합니다. 지정가 기준 견적은 환산 지정가에 전량 한 번 체결하는 가정이며 원화 원본으로 계산한 동결액과 다를 수 있습니다. 동결·물량 소비는 하지 않습니다. 예상 매도 순금액이 0 이하라는 이유만으로 접수를 막지는 않습니다.

- GET /orders/{orderId}: 본인 주문 상세, CLOSED 회차 포함.
- GET /accounts/me/orders: 현재 ACTIVE 회차, orderId 내림차순. size 기본 20/최대 100, 계좌 범위가 포함된 불투명 커서.
- GET /orders/{orderId}/executions: sequenceNo 오름차순, 동일 size 상한과 주문별 커서. 체결별 단가·환율·정산액·executedAt·원장 balanceAfter를 반환. 정상 원장 누락은 INTERNAL_ERROR.
- PATCH /orders/{orderId}: 정확히 {"status":"CANCELED"}만 허용. 추가 필드·다른 상태는 INVALID_INPUT. 반복 취소는 이중 해제 없이 성공. FILLED/REJECTED/EXPIRED는 orderId/status가 포함된 409 ORDER_STATE_CONFLICT. 마감 이후 취소는 EXPIRED와 동결 해제를 먼저 커밋한 후 409를 반환.
- 없는 주문과 타인 주문은 동일하게 404 ORDER_NOT_FOUND.

지정가(LIMIT) 주문 접수는 상시 활성화되어 동작합니다. 기존 주문 재생·조회·취소·만료가 지원됩니다. 정적 사전 검증 실패는 저장하지 않습니다. 트랜잭션에서 확정한 업무 거절은 원본 조건·환율과 LIMIT REJECTED를 저장하고 NEW_CLIENT_ORDER_ID를 반환하며 동결·체결·원장은 생성하지 않습니다. 저장 전 컨텍스트/환율/시세/통화/계산 오류는 SAME_CLIENT_ORDER_ID입니다.

만료는 전용 단일 스레드 스케줄러에서 저장된 expiresAt을 기준으로 이전 스캔 완료 30초 후 실행하며, 시작 시에도 비동기로 복구합니다. 만료 트랜잭션은 PostgreSQL SET LOCAL lock_timeout을 2초로 설정하고, 잠금 실패 건은 롤백 후 다음 스캔에서 재시도합니다. 계좌 우선 잠금의 주문별 트랜잭션이며 실패 건은 다음 주기에 재시도합니다. 외부 캘린더·환율 조회는 없습니다. 취소·만료는 동결만 해제하며 이미 체결된 금액·보유·원장을 되돌리지 않습니다.

주문 종료와 동결 해제는 원자적으로 커밋합니다. 어느 단계에서든 실패하면 전체 롤백하고 상태·동결을 보존하며, ID 커서로 다음 주문을 계속 처리합니다. 실패 주문을 강제 EXPIRED 처리하거나 재시도 대상에서 제외하지 않습니다. 데이터 정합성 오류는 ERROR 로그로 남기고 원인 복구 후 다음 스캔에서 다시 처리합니다.

지정가 입력 검증 실패는 기존 SAME_CLIENT_ORDER_ID 정책을 유지하고, data.field에 limitPrice 또는 limitCurrency를 제공합니다.

시장가·지정가 공통 입력 검증도 잘못된 side, marketCountry, quantity, clientOrderId를 data.field로 식별합니다. 기존 오류 코드·재시도 정책은 유지하며, 잘못된 clientOrderId는 NOT_RETRYABLE, 유효한 ID 이후 주문 조건 오류는 SAME_CLIENT_ORDER_ID입니다.

레거시 보정·데이터 백필은 제공하지 않습니다. 스키마 적용을 위한 DB 재생성 등은 별도 명시적 승인이 필요합니다.

### 비랭킹 주문과 호가 공급

지정가는 호가가 아직 없어도 PENDING으로 접수합니다. 커밋 후 랭킹 또는 활성 지정가 주문 종목만 스케줄러가 호가를 생성합니다. 마지막 활성 주문이 종료된 비랭킹 종목은 수집·호가 공급에서 제외되고 기존 활성 호가도 종료됩니다. 시장가 체결은 가상 호가를 소비하지 않습니다. 지정가 체결 워커는 상시 스케줄링하며 신선한 호가가 없으면 보류합니다.

### 지정가 체결 및 비구속성 미리보기 (#122)

- `executionPreview.status`: `AVAILABLE`은 유효 호가로 평가한 결과이며 예상 0주도 포함합니다. `UNAVAILABLE`은 사용 가능한 신선한 호가/컨텍스트 없음, `NOT_APPLICABLE`은 접수 불가 견적입니다. `acceptable=true`와 `UNAVAILABLE`은 함께 올 수 있습니다. `reason`은 FILLED, PRICE_LIMIT, NO_LIQUIDITY, INSUFFICIENT_RESERVED_CASH, NON_POSITIVE_SETTLEMENT 중 결과를 나타냅니다. 조회 불가에는 NO_USABLE_BOOK/CONTEXT_EXPIRED, 접수 불가에는 거절 코드를 제공합니다.
- AVAILABLE은 숫자 `bookVersion`, `revision`, UTC `quoteAt`, `generatedAt`, `evaluatedAt`, 문자열 `expectedFilledQuantity`, `remainingQuantity`, `avgExecutionPrice`, `grossAmountKrw`, `feeKrw`, `taxKrw`, `netAmountKrw`, `remainingReservedCash`, `releasedCash`를 제공합니다. 0주이면 평균가는 null입니다. 나머지 상태는 status/reason/evaluatedAt 이외 결과 필드가 null입니다.
- 평균 체결가는 수량 가중 평균을 **표시용으로만 KRW 0자리 / USD 2자리 HALF_UP** 합니다. 정산은 원본 호가와 누적 반올림 차액을 사용하며 표시 평균가×수량으로 재계산하지 않습니다. USD 99에 1주 + 100에 2주는 평균 `99.67`이지만 거래대금은 정확히 USD 299입니다.
- 미리보기는 누적 체결 0에서 시작하며 원본 입력 기준 동결액을 사용합니다. 기존 부분체결 주문의 미리보기가 아니며 물량 예약, revision 증가, 호가 생성, 후속 체결 보장을 하지 않습니다.
- 워커는 전용 단일 스레드에서 3초 fixed delay, 후보 페이지 50건, 틱당 최대 주문 100건, 새 주문 시작 예산 5초로 상시 실행합니다. 한 종목·방향은 방문당 최대 10건을 시도한 뒤 다음 그룹으로 이동하며 틱 경계에서도 그룹 순환 위치를 유지합니다. 활성화 플래그는 없으며 실행 중 금융 트랜잭션은 예산 초과 후에도 완료합니다. 페이지/시도 제한은 주문 수이지 호가 레벨 수나 주식 수량이 아니며, 주문 한 건은 여전히 해당 방향의 호가 전체를 검토합니다.
- 전역 orderId 상한은 없습니다. BUY 지정가 내림차순 / SELL 오름차순, 동일 가격은 orderedAt/orderId 오름차순입니다. 그룹별 가격·시간 커서는 bookVersion과 실제 적용 환율 값에 연결합니다. 새 버전이나 환율 변경 시 기존 부분 체결·보류 주문을 포함해 선순위부터 재평가합니다. 잔량 소비에 따른 revision 변경이나 환율의 후행 0 표현 차이만으로는 초기화하지 않습니다.
- 접수 커밋 후 알림은 종목·방향별 가장 선순위 한 건으로 합칩니다. 직전 시도의 진행 위치를 저장한 뒤 다음 한 건의 후보 선정 경계에서만 반영합니다. 알림이 해당 커서보다 선순위면 처음부터 재평가하고 후순위면 커서를 유지하며, 두 경우 모두 읽어 둔 페이지를 다시 조회합니다. 이미 선정한 시도를 중단하거나 그룹 방문을 종료하지 않습니다. 페이지 조회/체결 중 도착한 알림은 다음 선정에 반영하므로 접수가 계속되어도 조용해질 때까지 기다리지 않고 진행합니다. 가격·시간 우선순위는 선정 경계에 적용하며 페이지는 조회 캐시이지 한꺼번에 선정한 주문 묶음이 아닙니다. 롤백/거절/멱등 재요청은 알림을 발행하지 않으며 버전·환율 안전 검증은 유지합니다. 단일 인스턴스 커서와 대기 알림은 종목·방향별로만 유지하고 전체 순회 후 관찰되지 않은 그룹을 제거합니다. 재시작 시 선순위부터 안전하게 다시 시작합니다.
- 선정한 버전·환율은 준비와 재시도 동안 고정합니다. 각 주문은 금융 잠금 전에 체결 컨텍스트를 새로 준비합니다. 버전/환율이 달라졌다면 후순위 주문을 새 물량에 자동 체결하지 않고 PRIORITY_CHANGED를 반환하여 워커가 재선정합니다. 같은 버전의 revision 충돌 및 금융 락 경합은 최대 1회 재시도하며 잠금 후 버전/revision과 신선도를 재검증합니다.
- 후보 조회에서 만료/비활성 주문을 제외하고 주문별 실행에서도 환율 조회 전에 재확인합니다. 장외/호가 없음 준비는 환율을 조회하지 않습니다. 상태는 트랜잭션 밖의 공유 5분 캐시를 재사용합니다. 동결액 부족/순금액 0 이하의 정상 보류는 후순위를 진행하지만, 미해결 호가·락·컨텍스트·상태 실패는 해당 그룹을 초기화하고 다음 방문까지 보류하며 다른 그룹은 계속합니다. 그룹 준비가 진행되는 중 주문이 만료·종료되면 이미 시작된 그룹 준비에서는 환율을 조회할 수 있습니다.
- 금융 트랜잭션은 계좌 → 주문 → 호가 버전 → 해당 방향 레벨 → 보유 수량 순서로 잠그며 SET LOCAL lock_timeout=2s를 적용합니다. 잠금 후 원래 회차·주문 상태/체결횟수·버전/revision·장 운영·종목 상태·원본 시세 나이·환율 유효기간·만료를 재검증합니다. 락 안에서는 외부 API를 호출하지 않습니다. 기존 접수 흐름과 달리 워커는 세션/상태/환율 준비 시작 전에 checkedAt을 기록하여 준비 지연이 먼저 조회한 근거의 유효 시간을 늘리지 않도록 합니다.
- 선택한 모든 체결, 체결별 원장 balanceAfter, 주문 누적 금액, 예수금/동결/보유 수량, 공유 잔량을 한 번에 커밋합니다. 실제 체결이 있으면 revision은 트랜잭션당 1번만 증가합니다. 실패하면 전부 롤백하며 과거 이력 보정/재생을 만들지 않습니다.
- 취소 및 기존 30초 만료 스캔은 계좌 우선 잠금을 공유합니다. 이전 체결 이력은 보존하며 워커가 직접 만료 상태로 바꾸지는 않습니다.

`STOCK_STATUS_UNAVAILABLE`(503)은 상태 누락·알 수 없는 상태·국내 제약 정보 누락 또는 상태 조회 대기 실패입니다. 상태를 거래 가능으로 추정하지 않습니다. 상태 캐시 기본 TTL은 5분이며 미국 응답에는 국내 전용 거래정지·정리매매 정보가 없으므로 기존 값을 유지합니다.
