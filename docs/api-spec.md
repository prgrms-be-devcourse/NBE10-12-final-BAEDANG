# Mock Stock Trading Service — API Spec

> **Version**: Week-3 MVP · 26.09.03 ~ 09.09 · derived from the ERD and wireframe

> **Badges**: 17 endpoints · Java 21 · Spring Boot 3.5.16 · PostgreSQL 18 + TimescaleDB · REST · JSON

## Contents
- [Common Rules](#common-rules)
- [Auth & Member](#auth--member)
- [Market](#market)
- [Stocks](#stocks)
- [Stock Likes](#stock-likes)
- [Trading](#trading)
- [Accounts](#accounts)
- [Screen ↔ API Mapping](#screen--api-mapping)
- [Polling Policy](#polling-policy)
- [Open Decisions](#open-decisions)
- [Week 2+](#week-2)

---

## Common Rules

### Base URL
| | |
|---|---|
| dev | `http://localhost:8080/api` |
| prod | `https://{domain}/api` |

### Auth

Stateless JWT (access + refresh) tokens are used for authentication. Protected endpoints require the token in the header:
```http
Authorization: Bearer <accessToken>
```

- Access tokens are valid for 15 minutes by default (`JWT_ACCESS_TTL: 15m`).
- Refresh tokens are valid for 7 days by default (`JWT_REFRESH_TTL: 7d`).
- The `X-User-Id` header is unsupported and rejected with 401 `UNAUTHORIZED`.
- Expired tokens return 401 `TOKEN_EXPIRED`. Invalid or tampered tokens return 401 `INVALID_TOKEN`.

| Scope | Target |
|---|---|
| public (no login) | signup · login · refresh · rankings · search · stock detail · chart · FX · guide |
| 🔒 login required | logout · `/users/me` (GET/PATCH/DELETE) · `/users/me/password` (PUT) · orders · account · holdings · ledger · portfolio reset · `/stocks/likes` (POST/GET/DELETE) |
### Response Format

Successful responses return the data directly; collections carry a cursor alongside.
```json
{
  "items": [ ... ],
  "nextCursor": "eyJ0YSI6IjEyNDAwMDAwMDAwMDAiLCJpZCI6MTAyNH0",
  "hasNext": true
}
```

### Error Format

```json
{
  "code": "INSUFFICIENT_CASH",
  "message": "주문가능금액이 부족합니다.",
  "timestamp": "2026-08-23T14:02:11+09:00",
  "data": { "required": "2415242", "available": "1200000" }
}
```
`message` is written as a sentence that can be shown to the user verbatim.

### Representation Rules

| Item | Rule | Example |
|---|---|---|
| amounts · quantities | string | `"241500"`, `"0.5"` |
| timestamps | ISO 8601 + offset | `"2026-08-11T12:36:59+09:00"` |
| dates | YYYY-MM-DD | `"2026-08-11"` |
| change rate | decimal-ratio string | `"0.0231"` = +2.31% |
| currency | ISO 4217 | `"KRW"`, `"USD"` |

**Never send amounts as numbers.** JavaScript's `number` is binary floating point, so large amounts or fractional orders drift. It's the same reason Toss returns prices as strings. On the frontend, use Decimal.js or display the string as-is.

FX rates and ratios are not rounded; only insignificant trailing zeros are removed. A `1.000000` value loaded from a `NUMERIC(19,6)` column is therefore returned as `"1"`, keeping the first response and a later DB-backed idempotent response textually identical. `avgBuyPrice` is not rounded early to the currency display unit and is returned with up to the moving-average storage precision of four decimal places. The frontend applies whole-won `HALF_UP` rounding only at the final display boundary.

### Cursor Pagination

```
GET /stocks/rankings?market=KR&size=20
→ { "items": [...], "nextCursor": "abc", "hasNext": true }
GET /stocks/rankings?market=KR&size=20&cursor=abc
```
Rankings reorder, so OFFSET duplicates or drops items. **`cursor` is an opaque server-encoded string the client never interprets.**

**Cursor payload — carry the sort axis itself**
The only ranking sort is trading-amount descending, so the cursor carries that value.
```js
// what the cursor holds
{ "ta": "1240000000000", "id": 1024 }   tradingAmount · stockId
// Base64URL-encoded when sent down
"eyJ0YSI6IjEyNDAwMDAwMDAwMDAiLCJpZCI6MTAyNH0"
```
```sql
-- next page
SELECT ... FROM stock s JOIN quote_snapshot q USING (stock_id)
 WHERE s.is_ranked AND s.market_country = :market
   AND (s.trading_amount, s.stock_id) < (:ta, :id)   -- tuple comparison
 ORDER BY s.trading_amount DESC, s.stock_id DESC
 LIMIT :size + 1;
```
**Always include `stock_id`.** If two stocks share the exact trading amount, trading-amount alone makes order at that boundary change every query, duplicating or dropping items. `stock_id` as the secondary sort key makes the order unique. PostgreSQL's `(a, b) < (:a, :b)` tuple comparison saves writing `a < :a OR (a = :a AND b < :b)`, and it rides the `(trading_amount DESC, stock_id DESC)` composite index directly.

**What if the universe refreshes mid-cursor?** If the user flips to page 2 exactly as the Monday 08:00 batch runs, the new universe queries in and some stocks drop out or appear. Leave it in week 1 — it's a weekly refresh, so it virtually never hits, and it's not an error, just "ranks changed in between". To be strict, pack a universe version (refresh time) into the cursor and return 409 on mismatch to restart from page 1 — a week-2 task.

**Don't use `rank_no` as the cursor.** It's fully rewritten by the batch, so right after refresh the same number points at a different stock. Display rank only; anchor pagination on trading amount + stock_id.

---

## Auth & Member

### `POST /auth/signup`
Signup + account opening + mock-funding deposit

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
Signup opens an account and deposits 50M at once. **`users` INSERT → `account` INSERT → `ledger_entry(INITIAL_DEPOSIT)` INSERT must be one transaction.**

| Error code | When |
|---|---|
| `EMAIL_DUPLICATED` | email already registered |
| `NICKNAME_DUPLICATED` | nickname already registered |
| `INVALID_INPUT` | invalid email/password/nickname format |

### `POST /auth/login`
**Request**
```json
{
  "email": "user@example.com",
  "password": "Password123!"
}
```
Response has the same shape as signup (200 OK).

| Error code | When |
|---|---|
| `LOGIN_FAILED` | email or password mismatch, or user is inactive/withdrawn |

### `POST /auth/refresh`
Reissues access token using a valid refresh token.

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

| Error code | When |
|---|---|
| `TOKEN_EXPIRED` | refresh token expired |
| `INVALID_TOKEN` | refresh token invalid, tampered, or user inactive |

### `POST /auth/logout` 🔒
Stateless logout. The client discards local tokens.

**Response · 200**
Empty body.

| Error code | When |
|---|---|
| `UNAUTHORIZED` | missing authentication token |
| `TOKEN_EXPIRED` | access token expired |
| `INVALID_TOKEN` | access token invalid |

### `GET /users/me` 🔒
My info

**Response · 200**
```json
{
  "userId": 1,
  "email": "user@example.com",
  "nickname": "홍길동"
}
```

| Error code | When |
|---|---|
| `UNAUTHORIZED` | missing or invalid access token |
| `USER_NOT_FOUND` | user not found or inactive |

### `PATCH /users/me` 🔒
Change nickname.

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

| Error code | When |
|---|---|
| `UNAUTHORIZED` | missing or invalid access token |
| `USER_NOT_FOUND` | user not found or inactive |
| `NICKNAME_DUPLICATED` | nickname already in use by another user |
| `INVALID_INPUT` | nickname length not 2~20 chars |

### `PUT /users/me/password` 🔒
Change password.

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

| Error code | When |
|---|---|
| `UNAUTHORIZED` | missing or invalid access token |
| `USER_NOT_FOUND` | user not found or inactive |
| `INVALID_PASSWORD` | current password incorrect |
| `INVALID_INPUT` | new password format policy not met (8~64 chars) |

### `DELETE /users/me` 🔒
Withdraw membership (soft-delete: user status `WITHDRAWN`, active account `CLOSED`).

**Request**
```json
{
  "currentPassword": "NewPassword123!"
}
```

**Response · 200**
Empty body.

| Error code | When |
|---|---|
| `UNAUTHORIZED` | missing or invalid access token |
| `USER_NOT_FOUND` | user not found or inactive |
| `INVALID_PASSWORD` | current password incorrect |
| `ACCOUNT_NOT_FOUND` | active account not found |

---

## Market

### `GET /market/status`
Session status — decides whether the trade button is enabled

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
The frontend decides trade-button enablement and the "실시간 / 종가" label from this response. Computed from the Toss `/market-calendar` response cached once daily.
**US regular-session hours shift 1 hour with DST** — DST (2nd Sun of Mar ~ 1st Sun of Nov) 22:30 ~ 05:00 KST ← now (Aug) / Standard (1st Sun of Nov ~ 2nd Sun of Mar) 23:30 ~ 06:00 KST. Hardcoding would **block trading for an hour after open in the 1st week of Nov.**

### `GET /exchange-rates/latest`
FX banner on the rankings page

| Param | Req | Description |
|---|---|---|
| `base` | — | default USD |
| `quote` | — | default KRW |

```json
{
  "baseCurrency": "USD",
  "quoteCurrency": "KRW",
  "rate": "1398.5",
  "changeRate": "0.0016",
  "rateAt": "2026-08-11T15:00:00+09:00"
}
```
Served from the latest `exchange_rate` row. **Stored hourly, so hourly frontend polling is enough** — more frequent calls return the same value. FX moves only 0.3–0.5%/day.
**The execution rate is a different path.** Orders use a separate **1-min TTL memory cache** — never fill against a rate up to an hour old.

### `GET /exchange-rates/history`
FX trend chart

| Param | Value |
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
Aggregated from the `exchange_rate` table (stored every hour on the hour).

---

## Stocks

### `GET /stocks/rankings`
Top 100 by trading amount · cursor pagination

| Param | Req | Description |
|---|---|---|
| `market` | O | `KR` / `US` |
| `size` | — | default 20, max 100 |
| `cursor` | — | next-page cursor — trading amount + stockId encoded (see Common Rules) |

**The selected market provides 100 ranked stocks in five pages of 20 by default.** Send the opaque cursor from each response to request the next page; the cursor remains an opaque `(tradingAmount, stockId)` tuple.

**The only sort axis is trading-amount descending.** No `sort` param in week 1 — when sort axes multiply, the cursor payload must differ per axis, so fixing one axis keeps both implementation and docs simple. Change-rate/volume sorts come in week 2.

**Response**
```json
{
  "items": [
    {
      "rank": 1,
      "stockId": 5,
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
      "realtime": true,
      "stockLikeId": 42
    }
  ],
  "nextCursor": "eyJ0YSI6IjEyNDAwMDAwMDAwMDAiLCJpZCI6MTAyNH0",
  "hasNext": true
}
```
- `realtime` — `quoteAt` within the current regular session → `true`. The basis for the frontend's "12:36:59 기준 · 실시간" vs "8월 11일 종가" distinction.
- **Stock like flag (#169)** — this endpoint stays public, but if a valid `Authorization` header is sent the server fills `stockLikeId` for stocks the user has liked. One extra `(user_id, stock_id) IN (...)` lookup per page. `stockLikeId` is omitted when the user has not liked the stock or the request is anonymous, so a missing field means "not liked". Use `stockId` for `POST /stocks/likes` and `stockLikeId` for `DELETE /stocks/likes/{id}`. An expired token still returns 401 `TOKEN_EXPIRED`, as on every endpoint.
- **Screen column mapping** — name · symbol · category · lastPrice · (changeAmount, changeRate) · tradingAmount.
- `tradingAmount` is **trailing one week** (`duration=1w`). The selection criterion is the displayed value, so users understand "why this order" — label it "최근 1주 거래대금".

### `GET /stocks/search`
Partial match on Korean name · English name · ticker

| Param | Req | Description |
|---|---|---|
| `q` | O | query (2+ chars) |
| `size` | — | default 10 |

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
**Search scope is confirmed: all stocks (~8,500).** The entire `stock` table is in scope regardless of top-100 status, and clicking a result opens the detail page normally — the only difference is realtime vs prior close.
**Current-price collection covers ranked stocks and stocks with active limit orders only**, with a 5-second regular-session target. Other stocks use on-demand detail refresh with a 5-second collection cache. In-flight requests are shared; source quoteAt is never replaced by fetch time. Non-ranked orders and estimates use the same freshness checks as ranked stocks.
**Toss gives Korean names for US stocks, so "엔비디아" matches too.** English names are inconsistent (SamsungElec, HyundaiMtr, KIA CORP.) — strip whitespace + lowercase, then partial-match; a generated column for the search key is convenient.
**Week-1 implementation is `LIKE '%q%'`** — at 8,500 rows a full scan is milliseconds. But leading/trailing `%` skips indexes; when data grows, switch to **`pg_trgm` + GIN index** — same query, just add the index.
**Sort order: exact match → prefix match → partial match.** Typing "삼성" must put 삼성전자 above 미래에셋삼성...

| Error code | When |
|---|---|
| `INVALID_QUERY` | query under 2 chars |

### `GET /stocks/{symbol}?marketCountry={KR|US}`
Stock detail — all stocks in scope

`marketCountry` is required. Stocks are identified by `(UPPER(symbol), market_country)`, which disambiguates equal symbols across KR and US markets.

Where the price comes from depends on **whether that stock's own market is open** — not the viewer's viewpoint. Opening NVDA in the Korean daytime returns the prior close because the US market is closed.

| Situation | Price | Chart |
|---|---|---|
| that market's regular session + top 100 | **5s realtime** · `quote_snapshot` · `realtime: true` | 1-min scheduler collection |
| market closed · foreign-market stock · or outside top 100 | prior close · `realtime: false` | last session's minute candles (on-demand + 60s cache) |

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

**Key fields**
| Field | Meaning |
|---|---|
| `tradable` | whether this stock can be traded right now |
| `tradableReason` | reason code when `tradable=false` |

**`tradableReason` values**
| Code | Screen text |
|---|---|
| `MARKET_CLOSED` | 장 마감 · 09:00~15:30 거래 가능 |
| `STOCK_NOT_TRADABLE` | 현재 거래를 지원하지 않는 종목이에요 |
| `SUSPENDED` | 거래정지 종목 |
| `LIQUIDATION` | 정리매매 종목 |
| `QUOTE_NOT_FOUND` | no quote has been loaded yet |

Quote loading is owned by the separate market-data ingestion work. When no quote exists yet, the endpoint still returns stock metadata with null price fields, `realtime: false`, `tradable: false`, and `tradableReason: "QUOTE_NOT_FOUND"`.

| Error code | When |
|---|---|
| `STOCK_NOT_FOUND` | symbol doesn't exist |
| `INVALID_INPUT` | missing `marketCountry` or a value other than KR/US |

### `GET /stocks/{symbol}/financials?marketCountry=KR`
Korean stock industry classification & financial statements — cache-first

`GET /stocks/{symbol}` does not make external financial calls to preserve quote latency and availability. Financial information is queried separately through this dedicated endpoint.

| Param | Req | Value |
|---|---|---|
| `marketCountry` | O | market identifier — `KR` only |

**Response · 200**
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

- **Data format**: All financial amounts and ratios are serialized as plain strings without exponent notation or trailing zeros (`FinancialDecimalFormatter.plain`). `annual` and `quarterly` arrays are ordered by `statementYearMonth` descending. Null values are omitted from JSON per global `non_null` inclusion policy.
- **Operating profit margin**: Derived at query time as `operatingProfit × 100 ÷ sales` with scale 6 `HALF_UP` rounding. If `sales` is 0 or null, `operatingProfitMargin` is returned as null.
- **`dataStatus`**:
  - `FRESH`: All three groups are within TTL (financials 7 days / 7d, industry 30 days / 30d) or were refreshed successfully. Normal empty KIS response is stored as a negative cache and also returns `FRESH`.
  - `STALE`: A refresh was needed and attempted, but external KIS failed, and previously cached data was returned as fallback.

| Error code | HTTP | When |
|---|---|---|
| `INVALID_INPUT` | 400 | missing `marketCountry` parameter or invalid format |
| `STOCK_NOT_FOUND` | 404 | symbol does not exist |
| `FINANCIALS_NOT_SUPPORTED` | 422 | US stocks, ETFs, ETNs, or non-6-digit Korean symbols |
| `KIS_RATE_LIMITED` | 429 | KIS request rate limit reached and no previous cache exists |
| `KIS_API_ERROR` | 502 | KIS external communication error and no previous cache exists |
| `KIS_API_UNAVAILABLE` | 503 | KIS is disabled (`kis.enabled=false`) and no cached data exists for a required group |

### `GET /stocks/{symbol}/candles`
Daily & minute chart

| Param | Req | Value |
|---|---|---|
| `marketCountry` | O | symbol market — `KR` · `US` |
| `interval` | O | time unit per candle — `1m` · `5m` · `10m` · `1d` · `1w` |
| `range` | O | period — `1D` · `1W` · `1M` · `6M` · `1Y` · `3Y` |

**Valid combinations — anything else rejected with 400**

| interval | allowed ranges | # candles | data source |
|---|---|---|---|
| `1m` | `1D` | latest 200 | top 100: 1-minute scheduler · other stocks: on-demand Toss `/candles?interval=1m` |
| `5m` | `1D` · `1W` | 78 / 390 | aggregate 1-min |
| `10m` | `1W` | 195 | aggregate 1-min |
| `1d` | `1M` · `6M` · `1Y` | 22 / 130 / 250 | `daily_candle` |
| `1w` | `3Y` | 156 | aggregate daily |

**Toss provides only `1m` and `1d`** — 5m·10m·1w must be aggregated by us (group 1-min candles in fives → 40 five-min candles). **Must block combos like `1m` + `1Y`** — a year of 1-min candles is 120k rows.

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

For financial-data integrity, the MVP daily chart returns only finalized rows stored in `daily_candle`. A current price alone cannot supply today's open, high, and low, so the API does not fabricate today's OHLC. Display the current price separately from `GET /stocks/{symbol}`.
**Our API keeps a 250-candle response ceiling for 1Y, while the initial on-demand backfill makes one external call for the latest 200 candles.** Entering through either detail or any daily-chart range stores the same 200 rows, and later 1M·6M·1Y switches reuse the database. After backfill, the latest stored candle is compared with the latest finalized trading day derived from the market calendar (regular close plus 10 minutes); stale off-universe stocks refresh the latest 200 rows only when needed. A successful refresh for the same finalized trading day is not repeated during the same process. A stock with no stored history therefore returns at most 200 candles for 1Y; if scheduled or other stored history exists, the response can contain up to 250.

| Error code | When |
|---|---|
| `INVALID_INTERVAL_RANGE` | disallowed interval × range combination |
| `STOCK_NOT_FOUND` | symbol doesn't exist |

**MVP scope is `1d` and `1m` only.** Supported combinations are `1m+1D` and `1d+1M/6M/1Y`; all others return `INVALID_INTERVAL_RANGE`. Daily comes from `daily_candle` (scheduler stores it after close). For the ranked top 100, minute candles are collected once per minute through sequential 20-stock groups within the `MARKET_DATA_CHART` 20 TPS group. Off-universe and off-hours detail charts use on-demand `minute_candle` caching. 5m·10m·1w aggregation moves to week 2.

**Minute candles: scheduled for the ranked universe + on-demand cache elsewhere**
```
// week-1 minute-candle flow
GET /stocks/NVDA/candles?marketCountry=US&interval=1m&range=1D
   ↓
is there data within 60s in minute_candle?
   ├ yes → return from the DB directly                  no Toss call
   └ no  → call Toss /candles?interval=1m&count=200 (off-universe or off-hours detail)
             ↓  UPSERT with ON CONFLICT DO NOTHING
             return from the DB
```
**Off-hours or foreign-market stocks behave the same.** Calling `/candles` on a closed market returns the last session's candles as-is — opening NVDA in the Korean daytime shows the prior close + the last US session's minute chart. The frontend just flips the "실시간/종가" label from `realtime`; the chart itself needs no branching. An empty chart reads as "broken screen" — **always separate "not tradable" from "not viewable"**.
The ranked-universe collector runs once per minute, sequentially in 20-stock groups under the separate 20 TPS chart limit. Off-universe detail requests remain on-demand and reuse a 60-second cache, so stocks nobody watches are not collected continuously. Week 2 adds limit-order fill determination and 5m/10m aggregation.
**200 candles per call.** KR regular session 09:00~15:30 = 330 minutes, so a full day needs `before` × 2. With the week-1 chart as "last 200 minutes", 1 call suffices — keep 1 call as the default and use 2 only when "view all" is pressed.
**Needs measurement** — whether `before` is inclusive, and whether the closing-auction (15:30) candle exists. Without a 15:30 candle it's 329, not 330. Overlapping boundary candles are filtered by `ON CONFLICT DO NOTHING` on `(stock_id, candle_at)`.


### `GET /stocks/{symbol}/orderbook?marketCountry={KR|US}`
Synthetic order book and depth query based on latest market price

All users share the same synthetic order book snapshot. A single request returns 10 asks and all available bids: KR stocks always have 10 bids, while US stocks may have 1 to 10 bids for low-priced symbols. Query requests never generate new order books; they read from the database using a single SQL snapshot of the current active version.

| Field | Required | Description |
|---|---|---|
| `symbol` (path parameter) | Y | Stock symbol (e.g., `005930`, `NVDA`) |
| `marketCountry` (query parameter) | Y | Market country (`KR` / `US`, case-insensitive). Returns 400 if missing or unsupported |
| none | - | `depth`, `page`, and `cursor` parameters are not accepted; the server returns 10 asks and all available bids (10 for KR, 1–10 for US) |

**Response 200** — `basePrice` and level `price` values are strings formatted by currency: KRW uses whole won with no decimal places, and USD uses exactly two decimal places. Level `quantity` is a string formatted via `FinancialDecimalFormatter.plain()`. `initialQuantity` is an internal audit value and is not exposed in the public API.
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

- `asks`: sell quotes (10 levels in ascending price order, starting with best ask ASK 1).
- `bids`: buy quotes in descending price order, starting with best bid BID 1. KR returns 10 levels; US returns 1–10 available levels, and a partial US depth must end at the minimum valid price of `$0.01`.
- `bookVersion`, `revision`, and levels come from a single database statement snapshot, guaranteeing consistency.

**Errors**

| Error code | HTTP | When |
|---|---|---|
| `INVALID_INPUT` | 400 | `marketCountry` parameter missing or unsupported (anything other than `KR`, `US`) |
| `STOCK_NOT_FOUND` | 404 | Symbol does not exist |
| `ORDER_BOOK_UNAVAILABLE` | 503 | Feature disabled (`ORDERBOOK_ENABLED=false`), untradable stock (suspended, liquidation, off-universe), market closed or session expired, quote older than 15s, future quote, currency mismatch, or missing/incomplete active version |

GET error responses do not include an order submission `retryPolicy`; clients re-query based on their normal polling interval.
---

## Stock Likes

Stock like (관심 종목) endpoints (#169). Each user and stock pair has at most one row, enforced by the `stock_like` unique constraint `(user_id, stock_id)`. **Register and delete never error on duplicates.** Registering a stock already on the list, or deleting an id that does not exist, returns 200 without changing rows. Concurrent duplicate requests also never produce a 500.

### `POST /stocks/likes` 🔒
Like a stock

**Request**
```json
{ "stockId": 5 }
```

**Response · 200**
```json
{ "stockLikeId": 42 }
```
The same `stockLikeId` is returned whether the row was just created or already existed. The client can therefore unlike immediately, even off-hours when ranking polling is paused. Server: `INSERT ... ON CONFLICT (user_id, stock_id) DO NOTHING`, then select the row by `(user_id, stock_id)`. Under READ COMMITTED the select sees a row committed by a concurrent request.

| Error | Condition |
|---|---|
| 400 `INVALID_INPUT` | `stockId` is missing |
| 404 `STOCK_NOT_FOUND` | no stock with that `stockId` |

### `GET /stocks/likes` 🔒
Stock likes, newest first, with cursor pagination

| Param | Req | Description |
|---|---|---|
| `cursor` | — | `nextCursor` from the previous response |
| `size` | — | default 20, max 50 |

**Response**
```json
{
  "items": [
    {
      "stockLikeId": 42,
      "stockId": 5,
      "symbol": "005930",
      "name": "삼성전자",
      "marketCountry": "KR",
      "prevClose": "236050",
      "lastPrice": "241500",
      "changeRate": "0.023089"
    }
  ],
  "nextCursor": "NDI",
  "hasNext": false
}
```
- **Cursor**: an opaque `stock_like_id`, following the same pattern as the ledger. The query is `WHERE user_id = ? AND stock_like_id < :cursor ORDER BY stock_like_id DESC LIMIT :size + 1`. `nextCursor` is filled even on the last page; use `hasNext` to decide whether to continue. A malformed cursor returns 400 `INVALID_CURSOR`.
- **Prices**: prices are formatted in the stock's currency, the same way as rankings. They are read from `quote_snapshot`.
  - A stock that has never been collected is filled on demand through the stock-detail path (`StockOnDemandQuoteService.ensureQuote`). This happens at most once per stock.
  - If Toss fails, the three price fields are omitted and the list still returns 200.
  - An existing but stale snapshot is not refreshed here. Off-universe stocks may therefore show a frozen price until they are collected elsewhere.
- **Polling**: poll this one endpoint for the stock likes screen, not `GET /stocks/{symbol}` per stock. Per-stock detail polling multiplies requests and can trigger Toss on-demand calls.
- **Transaction**: the service runs without a surrounding transaction (`propagation = NEVER`). Each repository read and the on-demand quote write use their own short transaction, so no DB connection is held during Toss calls.

### `DELETE /stocks/likes/{id}` 🔒
Unlike a stock

`{id}` is the `stockLikeId` from the register response, a ranking item, or a stock likes list item. The server deletes with `WHERE user_id = :currentUser AND stock_like_id = :id`, so another user's id deletes nothing.

**Response · 200**: empty body. It is also 200 when the id does not exist or belongs to another user; the response does not reveal whether someone else's row exists. A non-numeric `id` returns 400 `INVALID_INPUT`.

---

## Trading

### `GET /orders/quote/market` 🔒
Fee & tax preview

```
?symbol=005930&marketCountry=KR&side=BUY&quantity=10
```

`marketCountry` is required and must be `KR` or `US`. Symbols may overlap across markets, so the server identifies a stock by `(symbol, marketCountry)`.

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

**Calculation rules**
```
buy   netAmount = grossAmount + fee           (deducted from deposit)
sell  netAmount = grossAmount − fee − tax     (credited to deposit)
KR grossAmount = round(executedPriceKrw × quantity, 0)
US priceUsd    = round(executedPriceUsd, 2)
US grossAmount = round(priceUsd × quantity × exchangeRate, 0)
fee            = round(grossAmount × 0.0001, 0)  trading fee 0.01% (buy & sell)
KR tax         = round(grossAmount × 0.002, 0)   (KR sell only)
US secFeeUsd   = round(max(priceUsd × quantity × 0.0000206, $0.01), 2)
US tax         = round(secFeeUsd × exchangeRate, 0) (US sell only)
```
**Example — 삼성전자 10주 @ 241,500**
```
buy   gross 2,415,000 + fee   242              = 2,415,242 deducted
sell  gross 2,415,000 − fee   242 − tax 4,830  = 2,409,928 credited
```
- **Market-specific rates are config (`.env`). Never hardcode.** KR sell tax is 0.2%; US sell tax is replaced by the SEC fee rate `0.0000206` with a USD `0.01` minimum. The trading fee remains 0.01% for both markets.
- **Round at the currency boundary with `HALF_UP`.** For US orders, first round the per-share USD price to cents. Calculate KRW gross from that `priceUsd × quantity × exchangeRate` and round to whole won. Calculate `secFeeUsd` separately from `priceUsd × quantity`, apply the `$0.01` minimum, round it to cents, then convert it to KRW and round to whole won. For KR orders, round the KRW gross amount first, calculate fee/tax from that value, then round again. Keep final ledger amounts as integers so the invariant holds exactly.
- **Price can move between quote and fill.** The quote is a reference; the server recomputes at fill time.

### `POST /orders/market` 🔒
Buy / sell (market immediate fill)

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
`accountId` pins the order intent to the account round where it started. If that account becomes `CLOSED` during processing, the order is rejected with `ACCOUNT_ROUND_CHANGED` instead of being carried over to the new active account. After refreshing account information, an intentional new order must use the latest `accountId` and a new `clientOrderId`. An exact retry of an already processed order still returns its stored result even if the account was closed later.

`clientOrderId` is generated by the frontend with UUID v4 and identifies one intentional order within an account. Keep the same value for double-clicks and network retries; generate a new value when the user intentionally places another order. **Re-sending the same value and payload returns the stored result without calling market APIs. Reusing it with a different payload is a conflict.**

The failure response's `data.retryPolicy` defines how to handle `clientOrderId`. `SAME_CLIENT_ORDER_ID` means no order row was created and the same ID can be retried safely. `NEW_CLIENT_ORDER_ID` means a `REJECTED` row was stored as the final result, so a new attempt after conditions change needs a new ID. `NOT_RETRYABLE` means resending the request unchanged cannot succeed, such as reusing an ID with a different payload.

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

Clients must follow `data.retryPolicy` instead of inferring ID reuse from the HTTP status or error code alone. If malformed JSON or another failure has no `retryPolicy`, do not automatically resend the unchanged request.

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
`cashBalanceAfter` is the balance recorded by the first fill ledger entry, not the current balance at retry time. Idempotent responses replay that same audit value. Portfolio valuation and current account state remain outside the fill path; call `GET /accounts/me` for the latest values.

Market-order replay selects a normal ledger entry matching the order side with a non-null execution_id. A FILLED order without this entry returns INTERNAL_ERROR; legacy replay from an unlinked ledger is not supported. Database foreign keys guarantee execution existence and order ownership, so no additional execution lookup is made solely for that check.

**Server processing order**
```
① Verify accountId ownership and read clientOrderId; return the stored result on an exact retry even for a closed round
② Read the stock and preflight static rules — universe → suspension → liquidation
③ Only after preflight passes, read market session and US execution FX (KR uses rate 1), then record checkedAt when external data preparation finishes
④ SELECT the exact account by accountId and userId FOR UPDATE; reject a CLOSED account instead of carrying the order to a new round
⑤ Recheck clientOrderId; if the same order completed while waiting for the lock, return the stored result
⑥ For a new order only, reject an expired market context, read the quote, validate its currency, and lock holding for a sell (lock order: account → holding)
⑦ Validate — universe → suspension → liquidation → session → quote time → settlement → cash/quantity
⑧ INSERT trade_order FILLED (or REJECTED for a business rejection confirmed in the transaction)
⑨ UPDATE account.cash_balance and lock/upsert holding
⑩ INSERT ledger_entry (append only; FILLED only)
```

Market orders never write `PENDING` and never modify `locked_cash` or `locked_quantity`; those reservations belong to the future limit-order flow. A rejected market order changes no balance/holding and creates no ledger entry. Field validation failures after a valid `clientOrderId` is parsed, external-market-data failures, static preflight failures, an expired market context, and quote-currency mismatches create no order row and return `SAME_CLIENT_ORDER_ID`. Only failures confirmed inside the transaction store a final `REJECTED` row and return `NEW_CLIENT_ORDER_ID`. A stock state can change between preflight and lock acquisition, so clients must follow `data.retryPolicy` rather than infer retry behavior from the error code alone. Unreadable JSON and an invalid `clientOrderId` have no valid ID to reuse and are outside this rule.

The market-order use case is a top-level transaction boundary. It must not be invoked inside another transaction; the application entry point enforces this with `Propagation.NEVER`, while the DB mutation service starts its own `REQUIRED` transaction. This keeps a committed `REJECTED` record from being rolled back by an unrelated outer workflow.

**Trading eligibility** — market/limit orders and both quote APIs support non-ranked stocks. Before a new order, refresh listing status (and KR suspension/liquidation flags) through the shared status cache, and require a source quote no older than 15 seconds. Re-fetching a stale source price does not make it fresh. External preparation occurs before account locks; financial transactions revalidate database state and quote time. Idempotent completed requests return before external calls. Preparation failure stores no rejected order and permits the same clientOrderId retry; follow data.retryPolicy.

One order may contain at most **1,000,000 shares**, configured by `trading.max-order-quantity`; scientific notation is not accepted.

**Errors**
| Code | HTTP | Default retry policy | Screen text |
|---|---|---|---|
| `MARKET_CLOSED` | 422 | `NEW_CLIENT_ORDER_ID` | 지금은 거래할 수 없는 시간이에요 |
| `MARKET_CONTEXT_EXPIRED` | 422 | `SAME_CLIENT_ORDER_ID` | 시장 정보를 다시 확인한 뒤 주문해주세요 |
| `STOCK_NOT_TRADABLE` | 422 | read `data.retryPolicy` for the actual path | 현재 거래를 지원하지 않는 종목이에요 |
| `STOCK_SUSPENDED` | 422 | read `data.retryPolicy` for the actual path | 거래정지 종목이에요 |
| `STOCK_LIQUIDATION` | 422 | read `data.retryPolicy` for the actual path | 정리매매 종목이에요 |
| `INSUFFICIENT_CASH` | 422 | `NEW_CLIENT_ORDER_ID` | 주문가능금액이 부족해요 |
| `INSUFFICIENT_QUANTITY` | 422 | `NEW_CLIENT_ORDER_ID` | 보유 수량이 부족해요 |
| `STALE_QUOTE` | 422 | `NEW_CLIENT_ORDER_ID` | 시세 정보가 오래되었어요. 다시 시도해주세요 |
| `FUTURE_QUOTE` | 422 | `NEW_CLIENT_ORDER_ID` | 시세 기준 시각이 올바르지 않아요. 다시 시도해주세요 |
| `INVALID_SETTLEMENT_AMOUNT` | 422 | read `data.retryPolicy` for the actual path | 정산 금액이 올바르지 않아요 |
| `QUOTE_CURRENCY_MISMATCH` | 502 | `SAME_CLIENT_ORDER_ID` | 시세 통화 정보가 올바르지 않아요 |
| `INVALID_QUANTITY` | 400 | `SAME_CLIENT_ORDER_ID` | 수량은 1주 이상의 정수로 입력해주세요 |
| `DUPLICATE_ORDER` | 409 | `NOT_RETRYABLE` | 이미 처리된 주문이에요 |
| `ACCOUNT_ROUND_CHANGED` | 409 | `NOT_RETRYABLE` | 포트폴리오가 초기화됐어요. 계좌 정보를 새로고침한 후 다시 주문해주세요 |

`STALE_QUOTE` uses `trading.quote-max-staleness-seconds`; `FUTURE_QUOTE` rejects any quote timestamp later than the server's validation time. The separate `trading.execution-context-max-age-seconds` setting limits account-lock wait time after external market data preparation finishes.

US market orders also carry FX receipt time, `validFrom` and `validUntil` into the transaction and execution factory. After acquiring the account lock, new orders revalidate source validity and the 60-second receipt TTL independently of context freshness; execution creation also checks the same validation time and agreement with the settlement rate. Missing, expired or future FX evidence results in EXCHANGE_RATE_NOT_FOUND (404, SAME_CLIENT_ORDER_ID), without saving an order/execution/ledger or making an external call inside the transaction. KR uses 1 without an FX lookup. Existing-order idempotent responses return stored results before this check; only the applied rate is persisted.

Market settlement rounds US unit prices to cents using HALF_UP before checking storage bounds. Prices and settlement amounts must fit NUMERIC(19,4), and quantities/FX rates must fit NUMERIC(19,6); FX rates are not rounded. Storage overflow returns INVALID_SETTLEMENT_AMOUNT with SAME_CLIENT_ORDER_ID without saving an order/execution/ledger. A representable calculation whose net settlement is non-positive still saves a REJECTED order and returns NEW_CLIENT_ORDER_ID.

---

## Accounts · My Page

### `GET /accounts/me` 🔒
Account summary

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
**Week 1 offers unrealized P&L only.** Realized P&L splits out in week 2 once fills accumulate. `stockValue` = holding × `quote_snapshot.last_price`, FX-converted to KRW for foreign stocks. The holding-level cost used for unrealized P&L is the fee-exclusive `holding.krw_purchase_amount`; transaction fees remain reflected in cash and therefore in total-account return.

### `GET /accounts/me/holdings` 🔒
Holdings

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
**A held stock must keep collecting quotes even after dropping out of the rankings** — otherwise its valuation freezes at that point, reading as an obvious bug.

### `GET /accounts/me/ledger` 🔒
Order history — ledger-based · cursor pagination

Shows the ledger (`ledger_entry`), not the order list — "**how the money moved**", not "what was bought". Initial funding and portfolio reset come in as single rows, making it the account's full history; printing `balanceAfter` lets the user follow the balance change by eye.

| Param | Req | Description |
|---|---|---|
| `cursor` | — | `nextCursor` from the previous response |
| `size` | — | default 20 |
| `entryType` | — | filter; omit for all |

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

**`entryType` — only three**
| Code | Sign | Screen text | amount |
|---|---|---|---|
| `INITIAL_DEPOSIT` | + | 모의투자금 지급 | `initial_cash` |
| `BUY` | − | 매수 | `−(gross + fee)` |
| `SELL` | + | 매도 | `+(gross − fee − tax)` |

**Fees and taxes are not split into separate rows — included in each fill's buy/sell amount.** Each normal trade ledger entry is linked by `execution_id` to one `trade_execution`: BUY stores `-net_amount_krw`, SELL stores `+net_amount_krw`, and `balanceAfter` preserves the account balance immediately after that fill. MARKET creates one fill/ledger pair; LIMIT creates a pair for each consumed book level, so one order can have multiple ledger entries with the same `orderId`. `trade_order.net_amount` is the cumulative settled amount, not the amount of each ledger row. Sum per-execution fees or order-level fees at their respective granularity; joining order totals to multiple ledger rows and summing them double-counts fees. **No `RESET` entry either** — reset creates a new account, and the new account's `INITIAL_DEPOSIT` row fills that role without an execution link.
**`exchangeRate`** — FX at fill time. 1 for KRW stocks, that moment's USD/KRW for US stocks. Not used in math (`amount` is already KRW-converted) — it's the audit field that answers "at what rate was this trade made" from the ledger alone. Not shown in the week-1 screen, but **unrecoverable if omitted now**.
**Cursor on `entryId` — not `occurredAt`.** Consecutive orders can share a timestamp within TIMESTAMPTZ precision, and at that boundary items duplicate or loop infinitely. `entryId` increases monotonically, so both are structurally impossible. Newest first — query with `WHERE account_id = ? AND entry_id < :cursor ORDER BY entry_id DESC LIMIT :size + 1`, and decide `hasNext` from the existence of the `size + 1`-th row.
**Rejected orders and reservation-only changes do not create trade ledger entries** — cash has not moved. LIMIT acceptance reserves resources; cancellation/expiration only releases the unfilled remainder and preserves all previous fill/ledger records. Use `GET /accounts/me/orders` for order states and rejection reasons, and `GET /orders/{orderId}/executions` for per-fill details.

### `POST /accounts/me/reset` 🔒
Portfolio reset

**Request**
```json
{
  "accountId": 1
}
```

`accountId` is the current active account ID returned by `GET /accounts/me`. Reuse it for duplicate clicks and network retries. Retrying a successful reset with the same ID returns the account created by that reset without opening another round. Its `cashBalance` is the original post-reset `initialCash`, not the account's current balance at retry time. To intentionally reset again, first read and send the new active `accountId`.

**Response · 200**
```json
{
  "accountId": 2,
  "roundNo": 2,
  "initialCash": "50000000",
  "cashBalance": "50000000"
}
```

**Server processing**
```
SELECT ... FROM account WHERE account_id = requested AND user_id = current_user FOR UPDATE;
UPDATE account SET status='CLOSED', closed_at=:resetAt WHERE account_id = requested;
INSERT INTO account (user_id, round_no, ...) VALUES (?, prev+1, 50000000, 50000000);
INSERT INTO ledger_entry (entry_type='INITIAL_DEPOSIT', occurred_at=:resetAt, ...);
```
**Not a delete — a new-round account opening.** The prior ledger, fills, and holdings stay preserved; queries run against the new `account_id`, so the screen clears automatically. Extensible to "past round scores" later. **The frontend must show a confirmation modal.**

Closing the old account, opening the new account, and inserting its initial-deposit ledger entry are one transaction and share one UTC `resetAt`. The account-row lock serializes reset with market orders. Future limit-order locks cause `ACCOUNT_HAS_PENDING_ORDERS` (409); a stale ID whose next round is no longer active causes `ACCOUNT_RESET_CONFLICT` (409).

---

## Reports

### `GET /reports/me` 🔒
Investment personality report (investment MBTI) for the current active account (round).

```json
{
  "accountId": 10,
  "roundNo": 1,
  "initialCash": "50000000",
  "cashBalance": "20000000",
  "stockValue": "33000000",
  "totalAsset": "53000000",
  "totalPnl": "3000000",
  "returnRate": "0.06",
  "classified": true,
  "typeCode": "CKSB",
  "typeLabel": "집중·국내·개별주·안정형",
  "shares": {
    "concentration": "0.6364",
    "domestic": "0.6364",
    "individual": "0.6364",
    "aggressive": "0"
  },
  "holdingCount": 2,
  "holdingPeriodWeeks": 4,
  "longHeldStocks": [
    {
      "symbol": "005930",
      "name": "삼성전자",
      "currency": "KRW",
      "avgBuyPrice": "228000",
      "lastPrice": "241500",
      "returnRate": "0.0592",
      "heldSince": "2026-08-01T00:00:00Z"
    }
  ],
  "asOf": "2026-09-09T00:00:00Z"
}
```
**Return rate is total P&L over the round's starting capital** — `returnRate = (cashBalance + stockValue − initialCash) / initialCash`, so it already includes realized (in cash) and unrealized (in holdings). This differs from `/accounts/me`'s `unrealizedPnlRate` (unrealized / cost). Valuation reuses the single account-valuation pass (same quotes·FX·rounding as `/accounts/me`).

**Investment MBTI — 4 binary axes → 16 types**, value-weighted by evaluation amount: concentration (집중 C / 분산 D, by top-1 weight), market (국내 K / 해외 G), instrument (개별주 S / ETF E), risk (공격 A / 안정 B). `shares` are the deciding 0~1 ratios. **With fewer than 2 holdings the portfolio is `"classified": false`** ("미분류/신규") and `typeCode`·`typeLabel` are null. Axis 4 (risk) is a Phase-1 proxy using leverage/inverse weight only; holding-volatility is deferred.

**`longHeldStocks`** — stocks held at least `holdingPeriodWeeks` (default 4, `report.holding-period-weeks`). "Held since" is the current lot's first buy, reconstructed by replaying filled `trade_order`s (a full sell to zero closes the lot; a later re-buy opens a new one). `returnRate` here is the **per-holding return in the stock's own currency**, `(lastPrice − avgBuyPrice) / avgBuyPrice` (null if no quote). Sorted oldest-held first. Empty until holdings accumulate 4 weeks.

---

## Screen ↔ API Mapping

| Screen | APIs called |
|---|---|
| Main | `/market/status` (optional) |
| Stock Rankings | `/exchange-rates/latest` · `/stocks/rankings` · `/stocks/search` |
| Stock Detail | `/stocks/{symbol}` · `/stocks/{symbol}/candles` |
| Trade Panel | `/orders/quote/market` · `POST /orders/market` |
| My Page | `/accounts/me` · `/accounts/me/holdings` · `/accounts/me/ledger` |
| Portfolio Reset | `POST /accounts/me/reset` |
| Guide | none (static content) |
| Signup Funnel | `POST /auth/signup` · `POST /auth/login` |

## Polling Policy

### Server collection schedule (confirmed)

The frontend polls **our** API; our server calls Toss on the cadence below. **The two are fully decoupled — even 100 users leave Toss call volume unchanged.**

| Time (KST) | Cadence | Task                                                                                                        |
|---|---|-------------------------------------------------------------------------------------------------------------|
| Mon 07:00 | weekly | full stock-master refresh — `/stocks/all` + `/stocks` batches                                               |
| Mon 08:00 | weekly | KR top-100 by trading amount — `/rankings?market=KR&duration=1w` · 1 call                                   |
| Mon 21:00 | weekly | US top-100 by trading amount — 1 call. 1.5h before US open                                                  |
| 08:50 | daily | KR `prev_close` ← prior close. Price limits fetched together                                                |
| KR regular session (calendar) | 5s target | Ranked + active-limit-order stocks only, up to 200 per request. |
| 09:00 ~ 15:30 | 1m | KR top-100 minute candles — sequential 20-stock groups in the separate `MARKET_DATA_CHART` 20 TPS group     |
| 15:40 ~ 17:10 | 30m | KR daily-candle retries — after calendar close + 10m, excluding stocks already stored for the date          |
| 09:00 ET * | daily | US `prev_close` refresh — 30 min before regular open (22:00 KST during DST, 23:00 KST during standard time) |
| US regular session (calendar) | 5s target | Ranked + active-limit-order stocks only, calendar-based session times. |
| 22:30 ~ 05:00 * | 1m | US top-100 minute candles — sequential 20-stock groups in the separate `MARKET_DATA_CHART` 20 TPS group      |
| US-local 16:10 ~ 17:10 * | 30m | US daily-candle retries — from 05:10 KST in DST or 06:10 in standard time, excluding completed stocks       |
| every hour on the hour | hourly | FX storage — 24 calls/day                                                                                   |

**KR and US sessions never overlap** — 09:00~15:30 and 22:30~05:00, so exactly one collector runs at any moment. No combined-load worry.
\* **US times shift 1 hour with DST** — don't hardcode; use `/market-calendar/US` session times.
**On-demand supplementation** — stocks outside scheduled collection refresh through the shared coordinator on detail entry, reusing a 5-second collection cache. Daily backfill and minute-candle policies remain unchanged.

### Client polling policy

| Target | Cadence | Endpoint |
|---|---|---|
| rankings list | 5s | `/stocks/rankings` |
| stock detail | 5s | `/stocks/{symbol}` |
| my page | 10s | `/accounts/me` + `/holdings` |
| chart | 60s | `/stocks/{symbol}/candles` |
| FX banner | 1h | `/exchange-rates/latest` |

**Three must-haves.**
① **Pause polling in background tabs** — checking `document.visibilityState` alone cuts real traffic nearly in half.
② **Stop polling at market close** — if `/market/status` `open` is `false`, there's nothing to refresh. The collector stops too, so `quote_snapshot.last_price` keeps the close and serves as the prior close automatically. The chart just shows the last session's candles stored in `minute_candle`.
③ **Return a next-update hint** — when the server's collection cadence (5s) and the client's polling cadence misalign, latency accumulates. Include `nextUpdateAt` and re-request right after it to pin the latency.
```json
{ "asOf": "...", "nextUpdateAt": "2026-08-11T12:37:04+09:00", "items": [...] }
```

---

## Open Decisions

Decide these in one team meeting before starting — it avoids mid-implementation stalls. **The confirmed items below were resolved and removed from this list.**

**Confirmed**
| Item | Decision |
|---|---|
| search scope | all stocks (~8,500) · `LIKE '%q%'` |
| fee & tax rates | fee 0.01% (buy & sell) · securities transaction tax 0.2% (sell only) |
| auth | stateless JWT access/refresh tokens · `Authorization: Bearer <accessToken>` |
| fractional trading | week 2 — whole shares only in week 1. **The toggle was removed from the screen entirely** |
| order history | ledger-based `GET /accounts/me/ledger` |
| ledger entries | buy · sell · initial-funding only. Fees/taxes included in the amounts (one line) |
| ranking sort & cursor | trading-amount descending · cursor is `(tradingAmount, stockId)` |
| minute-candle collection | top 100 every minute via sequential 20-stock groups at 5 TPS; off-universe detail is on-demand + 60s cache |
| universe refresh | Monday KR 08:00 · US 21:00 |
| dividend determination | disabled in week 1 |

**Still to decide**
| Item | Options |
|---|---|
| `before` boundary | measure whether Toss `/candles` `before` is inclusive and whether the closing-auction (15:30) candle exists |
| `STALE_QUOTE` threshold | whether 15s is appropriate |
| fractional digits (week 2) | US minimum order unit (0.1? 0.001?) |
| stock like flag on detail/search (#169) | rankings already carry `stockLikeId`. Decide whether stock detail and search responses carry it too, or the client uses a separate lookup |

---

## Week 2+

### Confirmed LIMIT settlement contract (#119)

The calculators, acceptance APIs (#120), shared book (#121), and engine/worker/preview (#122) are integrated. MARKET remains instant settlement without consuming virtual liquidity.

- Whole-share orders only. Reserve rounded KRW gross at limit price × full quantity × acceptance FX, plus rounded fee; KR needs no external FX. No exchange-rate buffer. Acceptance FX does not fix execution FX.
- BUY fills use only their own `reservedCash`, deducting this fill's actual net, not cumulative net or a quantity-proportional reserve. Do not use free cash. The engine reduces to an affordable integer quantity; defer if not even one share is affordable. Active buy remainder requires positive reserve.
- Full fill releases all unused reserve after settlement; cancellation/expiration releases the remainder without reverting previous fills. Release reduces locked cash, not increases cash balance.
- Require `netAmountKrw > 0` per book-level execution. Defer a zero/negative candidate without recording a fill/ledger or consuming liquidity. Do not combine the next level or skip levels to bypass this rule.
- Multiple valid levels use sequential order-cumulative gross/fee/tax deltas and one FX snapshot per transaction. The SEC minimum is not charged again for each level or transaction. Earlier fills retain their own FX.
- `LimitOrderSettlementCalculator` calculates supplied candidates; #122 selects levels/quantities and writes financial/liquidity state atomically. Snapshot TTL is 60 seconds intersected with source validity and must be rechecked after locking.

LIMIT uses option B (buy at asks <= limit, sell at bids >= limit) and expires at the accepted regular-session close. All users consume liquidity from the same synthetic market, but user orders are never directly matched against each other. Liquidity consumed by one user reduces the shared remainder available to others.

| Endpoint | Content |
|---|---|
| `POST /orders/market` (fractional) | open US fractional orders. Add `allowsFractional` to the detail response; change the input unit for US stocks only |
| `GET /accounts/me/assets/history` | asset trend chart (daily snapshots) |
| `GET /accounts/me/report` | investment-habit diagnosis |
| WebSocket | realtime quote push (replaces polling) |

Not built yet, but the URL design reserves the slots so nothing collides.

---
> Mock Stock Trading Service · Current API Spec · see also `erd.md` · `wireframe.md`

## LIMIT order lifecycle (#120)

Order detail, list, LIMIT acceptance and cancellation responses include `symbol`, `name`, and `marketCountry`. Execution list responses provide `{orderId, stock: {symbol, name, marketCountry}, items, nextCursor, hasNext}`. Stock fields appear once at page level, not in each execution item; orderId and stock are also returned for empty pages. Pagination and execution ordering are unchanged. No `currency` field is returned: limit/execution prices use KRW for KR and USD for US; `requestedLimitCurrency` identifies the original input currency. Price strings use whole won for KRW and exactly two decimal places for USD, including LIMIT quotes. FX precision and settlement calculations are unchanged; gross/fee/tax/net/reservedCash/balanceAfter remain KRW. Invalid path/query parameter types return HTTP 400 `INVALID_INPUT` with `data.field` identifying the parameter (e.g. `orderId` or `size`), without echoing the input value.

Endpoints are separated by order type: `POST /orders/market` & `GET /orders/quote/market` for market orders, `POST /orders/limit` & `GET /orders/quote/limit` for limit orders. Neither request body accepts `orderType`; the URL path defines the order type. Unknown MARKET request fields are ignored; limitPrice/limitCurrency do not change the URL-selected order type. Existing MARKET response fields are unchanged. Frontend request updates are owned separately.

LIMIT requires string limitPrice and limitCurrency. KR accepts KRW whole-won prices only. US accepts KRW whole-won or USD cent prices. Trailing zeros are allowed; excess precision and exponent notation are rejected, never silently rounded. For US KRW input, the backend stores HALF_UP(requestedLimitPrice / acceptanceExchangeRate, 2) as the fixed USD limitPrice. A result of zero or a storage overflow is rejected. The order does not continue tracking a KRW price ceiling/floor as FX changes.

BUY reserve is computed from the original input: KRW input × full quantity + rounded trading fee; USD input × full quantity × acceptance FX rounded to KRW, plus rounded trading fee. Do not reconvert the cent-rounded USD limit back to KRW to calculate reserve. SELL reserves quantity only. US acceptance uses the validated execution FX snapshot for both directions; this also preserves acceptance FX and supports KRW input conversion. Actual execution rates remain independent. Partial fills may need to reduce quantity or defer even when only cent conversion rounding causes reserve insufficiency.

POST /orders/limit returns 201 with OrderDetailResponse for accepted LIMIT orders: orderId/accountId/stockId/orderType/side/status/quantity/filledQuantity/activeRemainingQuantity, requestedLimitPrice/requestedLimitCurrency/limitPrice/acceptanceExchangeRate, reservedCash, cumulative grossAmount/fee/tax/netAmount, rejectReason and orderedAt/expiresAt/closedAt. activeRemainingQuantity is active remainder (zero after closure). Repeated requests compare original price numerically and input currency, never reconvert with new FX, and return current stored state without reactivation. Stored REJECTED results replay the same error. Existing orders are checked before external lookups and rechecked after locking before the round check.

LIMIT quote returns acceptable/reason, availableCash/availableQuantity, expiresAt, original and converted prices, acceptanceExchangeRate, limitEstimate {grossAmount, fee, tax, netAmount, reservedCash}, and executionPreview (see #122 below). The limit estimate assumes one fill at the converted limit; reserve can differ because KRW input is preserved. No resources are reserved or consumed. Non-positive estimated sell proceeds do not by themselves prevent acceptance.

- GET /orders/{orderId}: owning user's order, including closed rounds.
- GET /accounts/me/orders: current ACTIVE round only, descending orderId; size defaults to 20, range 1..100; opaque account-scoped cursor.
- GET /orders/{orderId}/executions: ascending sequenceNo, same size limits; opaque order-scoped cursor. Each execution includes its own price, FX, settlement amounts, executedAt and ledger balanceAfter. Missing normal ledger evidence is INTERNAL_ERROR.
- PATCH /orders/{orderId}: exactly {"status":"CANCELED"}; extra fields and other values are INVALID_INPUT. Repeated CANCELED is successful without another release. FILLED/REJECTED/EXPIRED produce 409 ORDER_STATE_CONFLICT with orderId/status. At or after expiresAt an active order is expired and released in a committed transaction before the 409 response.
- Missing or other-user order IDs return 404 ORDER_NOT_FOUND.

LIMIT order acceptance is always enabled. Existing-order replay, queries, cancellation and expiration are supported. Static preflight failures are not persisted. Validated transactional business rejections are stored as LIMIT REJECTED with original terms and FX, no reservation, execution or ledger, and return NEW_CLIENT_ORDER_ID. Pre-insert context/FX/quote/currency/calculation failures keep SAME_CLIENT_ORDER_ID.

Expiration scans stored expiresAt on a dedicated single-thread scheduler, 30 seconds after the previous scan completes, plus asynchronous startup recovery. Each expiration transaction sets PostgreSQL SET LOCAL lock_timeout to 2 seconds; lock failures roll back and are retried on the next scan. Each order closes in its own account-first transaction. Failures are logged and retried on later scans; no calendar/FX calls are made. Cancellation/expiration only release locked resources; settled cash/holdings/ledger are not reversed.

Order closure and reservation release commit atomically. Any failure rolls back both, preserving state and reservations while the ID cursor advances to subsequent orders. Failed orders are never forced to EXPIRED or excluded from retry. Data-integrity failures are logged at ERROR and retried on subsequent scans after the cause is repaired.

Invalid limit-order input preserves SAME_CLIENT_ORDER_ID and includes data.field as limitPrice or limitCurrency.

Shared MARKET/LIMIT input validation also identifies malformed side, marketCountry, quantity and clientOrderId via data.field. Existing error codes and retry policies are unchanged: malformed clientOrderId is NOT_RETRYABLE; invalid terms after a valid ID are SAME_CLIENT_ORDER_ID.

No historical data backfill or legacy correction is provided. Schema changes require an explicitly authorized database recreation or deployment schema procedure.

### Non-ranked orders and book supply

A limit order may enter PENDING without an existing book. After commit, the scheduler supplies books only for ranked stocks or stocks with active limit orders. When the last active order ends, a non-ranked stock leaves collection/book supply and its active book is closed. Market executions do not consume virtual liquidity. The limit execution worker is always scheduled; without a fresh book it defers the order.

### LIMIT execution and nonbinding preview (#122)

- `executionPreview.status`: `AVAILABLE` means a valid book was evaluated, including zero possible fills; `UNAVAILABLE` means no usable fresh book/context; `NOT_APPLICABLE` means the quote is not acceptable. `acceptable=true` with `UNAVAILABLE` is valid. `reason` identifies the result: FILLED, PRICE_LIMIT, NO_LIQUIDITY, INSUFFICIENT_RESERVED_CASH, NON_POSITIVE_SETTLEMENT; unavailable uses NO_USABLE_BOOK/CONTEXT_EXPIRED; not-applicable uses the acceptance rejection code.
- AVAILABLE includes numeric `bookVersion`, `revision`; UTC `quoteAt`, `generatedAt`, `evaluatedAt`; string `expectedFilledQuantity`, `remainingQuantity`, `avgExecutionPrice`, `grossAmountKrw`, `feeKrw`, `taxKrw`, `netAmountKrw`, `remainingReservedCash`, `releasedCash`. Average is null for zero fills. Unavailable/not-applicable results contain only status/reason/evaluatedAt and null result fields.
- Average execution price is quantity-weighted and rounded HALF_UP to **KRW 0 / USD 2 decimals for display only**. Settlement uses original level prices and cumulative rounding deltas, never the displayed average multiplied by quantity. Example: 1 share at USD 99 + 2 at USD 100 shows `99.67`, but USD gross is exactly 299, not 299.01.
- Preview starts with an empty cumulative state and the reserve computed from original user input. It does not preview an existing partially filled order, reserve liquidity, advance revision, create a book, or guarantee subsequent execution.
- Worker uses a dedicated single thread, fixed delay 3s, candidate pages of 50, up to 100 order attempts per tick and a 5s budget for starting new orders. Each stock/side gets at most 10 attempts per visit before moving to the next group; tick boundaries preserve the group rotation. No enable/disable flag. A running financial transaction completes even after the budget. Page/attempt limits apply to orders, not book levels or shares; one order still evaluates all levels on its side.
- There is no global orderId upper bound. Candidates are ordered by BUY limit descending / SELL ascending, then orderedAt/orderId ascending. A group's price/time cursor is bound to its bookVersion and numeric execution FX rate: a new version or changed rate restarts from the highest priority, including earlier partial fills/deferrals. A revision-only change or trailing-zero-only FX formatting change does not restart the cursor.
- Acceptance publishes an after-commit notification, coalesced to the earliest-priority arrival per stock/side. Notifications are applied only at the next single-candidate selection boundary, after saving the previous attempt's progress. An arrival ahead of that cursor restarts at the head; one behind it preserves the cursor. Either invalidates the cached page, but neither aborts the selected attempt or ends the group visit. Arrivals during the page query or execution are deferred to the next selection, so continuous admissions do not require a quiet period to make progress. Price/time priority applies at selection; the page is a read cache, not a batch of irrevocably selected orders. Rollbacks/rejections/idempotent replays produce no notification. Version/FX safety checks remain unchanged. Single-instance cursors and pending notifications are bounded per stock/side and pruned after a complete group rotation; restart safely begins at the head.
- The selected version/FX is pinned through preparation and retry. Each order refreshes its execution context before financial locks. If the version/rate changed, PRIORITY_CHANGED returns control to the worker for reselection, rather than filling the same lower-priority order on new liquidity. Same-version revision conflicts and financial lock contention allow at most one retry; book version/revision and freshness are rechecked under locks.
- Candidate enumeration excludes expired/inactive orders; per-order execution also checks them before fetching FX. Off-session/no-book preparation does not fetch FX. Status uses the shared 5-minute cache outside the transaction. A normal reserve/non-positive-net deferral allows the next order; unresolved book/lock/context/status failures reset and defer that stock/side until its next visit, without blocking other groups. A group-level preparation already in flight can still read FX if its orders expire or close concurrently.
- Financial transaction locks account → order → book version → side levels → holding, with transaction-local 2s lock_timeout. It rechecks original account round, order state/execution count, book version/revision, market, status, original quote age, FX validity and expiry after locks. No external API runs under these locks. Unlike the existing admission flow, the worker records context checkedAt before session/status/FX preparation, so preparation latency cannot extend the freshness window of earlier inputs.
- One transaction writes all selected fills, per-fill append-only executions and ledger balanceAfter, cumulative order amounts, cash/reserves/holdings and shared level consumption. It advances book revision exactly once if at least one fill commits. Any failure rolls everything back; no legacy repair/replay is synthesized.
- Cancellation and the existing 30s expiration scan share the account-first boundary; previously settled fills remain historical records. The worker does not mark expired orders itself.

`STOCK_STATUS_UNAVAILABLE` (503) covers missing/unknown status, missing KR restrictions, or status-refresh lock timeout. Unknown data is never treated as tradable. The default status cache TTL is 5 minutes. US responses lack KR-specific suspension/liquidation data, so existing flags are preserved.
