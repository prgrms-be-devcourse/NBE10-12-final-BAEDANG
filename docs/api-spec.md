# Mock Stock Trading Service — API Spec

> **Version**: Week-3 MVP · 26.09.03 ~ 09.09 · derived from the ERD and wireframe

> **Badges**: 17 endpoints · Java 21 · Spring Boot 3.5.16 · PostgreSQL 18 + TimescaleDB · REST · JSON

## Contents
- [Common Rules](#common-rules)
- [Auth & Member](#auth--member)
- [Market](#market)
- [Stocks](#stocks)
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
| 🔒 login required | logout · `/users/me` (GET/PATCH/DELETE) · `/users/me/password` (PUT) · orders · account · holdings · ledger · portfolio reset |
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
- `realtime` — `quoteAt` within the current regular session → `true`. The basis for the frontend's "12:36:59 기준 · 실시간" vs "8월 11일 종가" distinction.
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
**Off-universe stocks have an empty `quote_snapshot`** — the scheduler only covers the top 100. On entering the detail page, call `/prices` and `/candles` together, fill it, and UPSERT into `quote_snapshot`. Once queried, the stock comes from the DB thereafter. To show prices in the result list, fetch 20 rows in **one `/prices` batch call** (per-stock = 20 calls = rate limit). For week 1, showing the name first and filling the price after click is simpler.
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
| `NOT_IN_UNIVERSE` | 이 종목은 아직 거래를 지원하지 않아요 |
| `SUSPENDED` | 거래정지 종목 |
| `LIQUIDATION` | 정리매매 종목 |
| `QUOTE_NOT_FOUND` | no quote has been loaded yet |

Quote loading is owned by the separate market-data ingestion work. When no quote exists yet, the endpoint still returns stock metadata with null price fields, `realtime: false`, `tradable: false`, and `tradableReason: "QUOTE_NOT_FOUND"`.

| Error code | When |
|---|---|
| `STOCK_NOT_FOUND` | symbol doesn't exist |
| `INVALID_INPUT` | missing `marketCountry` or a value other than KR/US |

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

---

## Trading

### `GET /orders/quote` 🔒
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

### `POST /orders` 🔒
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

**Tradable universe** — the MVP trades only the top 100 stocks per market whose quotes are collected on schedule, so the `is_ranked` guard remains active. When on-demand quotes are introduced, a quote or order for a stock outside the top 100 will first fetch current price and tradability data from Toss and cache it. At that point `is_ranked` becomes only the scheduled-collection flag and the order policy must change with it. Until that infrastructure exists, orders outside the top 100 stay blocked.

One order may contain at most **1,000,000 shares**, configured by `trading.max-order-quantity`; scientific notation is not accepted.

**Errors**
| Code | HTTP | Default retry policy | Screen text |
|---|---|---|---|
| `MARKET_CLOSED` | 422 | `NEW_CLIENT_ORDER_ID` | 지금은 거래할 수 없는 시간이에요 |
| `MARKET_CONTEXT_EXPIRED` | 422 | `SAME_CLIENT_ORDER_ID` | 시장 정보를 다시 확인한 뒤 주문해주세요 |
| `NOT_IN_UNIVERSE` | 422 | read `data.retryPolicy` for the actual path | 이 종목은 아직 거래를 지원하지 않아요 |
| `STOCK_SUSPENDED` | 422 | read `data.retryPolicy` for the actual path | 거래정지 종목이에요 |
| `STOCK_LIQUIDATION` | 422 | read `data.retryPolicy` for the actual path | 정리매매 종목이에요 |
| `INSUFFICIENT_CASH` | 422 | `NEW_CLIENT_ORDER_ID` | 주문가능금액이 부족해요 |
| `INSUFFICIENT_QUANTITY` | 422 | `NEW_CLIENT_ORDER_ID` | 보유 수량이 부족해요 |
| `STALE_QUOTE` | 422 | `NEW_CLIENT_ORDER_ID` | 시세 정보가 오래되었어요. 다시 시도해주세요 |
| `FUTURE_QUOTE` | 422 | `NEW_CLIENT_ORDER_ID` | 시세 기준 시각이 올바르지 않아요. 다시 시도해주세요 |
| `INVALID_SETTLEMENT_AMOUNT` | 422 | `NEW_CLIENT_ORDER_ID` | 정산 금액이 올바르지 않아요 |
| `QUOTE_CURRENCY_MISMATCH` | 502 | `SAME_CLIENT_ORDER_ID` | 시세 통화 정보가 올바르지 않아요 |
| `INVALID_QUANTITY` | 400 | `SAME_CLIENT_ORDER_ID` | 수량은 1주 이상의 정수로 입력해주세요 |
| `DUPLICATE_ORDER` | 409 | `NOT_RETRYABLE` | 이미 처리된 주문이에요 |
| `ACCOUNT_ROUND_CHANGED` | 409 | `NOT_RETRYABLE` | 포트폴리오가 초기화됐어요. 계좌 정보를 새로고침한 후 다시 주문해주세요 |

`STALE_QUOTE` uses `trading.quote-max-staleness-seconds`; `FUTURE_QUOTE` rejects any quote timestamp later than the server's validation time. The separate `trading.execution-context-max-age-seconds` setting limits account-lock wait time after external market data preparation finishes.

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

**Fees and taxes are not split into separate rows — included in the buy/sell amounts.** One ledger line corresponds to one `trade_order.net_amount`, so the list is half as long and cursor handling is simpler. When the fee total is ever needed, `SUM(trade_order.fee)` retrieves it. **No `RESET` entry either** — reset creates a new account, and the new account's `INITIAL_DEPOSIT` row fills that role.
**`exchangeRate`** — FX at fill time. 1 for KRW stocks, that moment's USD/KRW for US stocks. Not used in math (`amount` is already KRW-converted) — it's the audit field that answers "at what rate was this trade made" from the ledger alone. Not shown in the week-1 screen, but **unrecoverable if omitted now**.
**Cursor on `entryId` — not `occurredAt`.** Consecutive orders can share a timestamp within TIMESTAMPTZ precision, and at that boundary items duplicate or loop infinitely. `entryId` increases monotonically, so both are structurally impossible. Newest first — query with `WHERE account_id = ? AND entry_id < :cursor ORDER BY entry_id DESC LIMIT :size + 1`, and decide `hasNext` from the existence of the `size + 1`-th row.
**Rejected orders don't land in the ledger** — money didn't move. To explain "why it failed", either add a `trade_order`-based order-history tab or defer to week 2. Week 1's screen only needs the ledger.

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

## Screen ↔ API Mapping

| Screen | APIs called |
|---|---|
| Main | `/market/status` (optional) |
| Stock Rankings | `/exchange-rates/latest` · `/stocks/rankings` · `/stocks/search` |
| Stock Detail | `/stocks/{symbol}` · `/stocks/{symbol}/candles` |
| Trade Panel | `/orders/quote` · `POST /orders` |
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
| 09:00 ~ 15:30 | 5s | KR top-100 current price — `/prices` 1 batch call (1.3% of limit)                                           |
| 09:00 ~ 15:30 | 1m | KR top-100 minute candles — sequential 20-stock groups in the separate `MARKET_DATA_CHART` 20 TPS group     |
| 15:40 ~ 17:10 | 30m | KR daily-candle retries — after calendar close + 10m, excluding stocks already stored for the date          |
| 09:00 ET * | daily | US `prev_close` refresh — 30 min before regular open (22:00 KST during DST, 23:00 KST during standard time) |
| 22:30 ~ 05:00 * | 5s | US top-100 current price — 1 batch call. 23:30 ~ 06:00 in winter                                            |
| 22:30 ~ 05:00 * | 1m | US top-100 minute candles — sequential 20-stock groups in the separate `MARKET_DATA_CHART` 20 TPS group      |
| US-local 16:10 ~ 17:10 * | 30m | US daily-candle retries — from 05:10 KST in DST or 06:10 in standard time, excluding completed stocks       |
| every hour on the hour | hourly | FX storage — 24 calls/day                                                                                   |

**KR and US sessions never overlap** — 09:00~15:30 and 22:30~05:00, so exactly one collector runs at any moment. No combined-load worry.
\* **US times shift 1 hour with DST** — don't hardcode; use `/market-calendar/US` session times.
**Not in the scheduler** — off-universe quotes and off-hours minute charts are filled on-demand when the user opens a detail page. Top-100 minute-candle collection is part of the MVP scheduler; week 2 adds limit-order fill determination and aggregation.

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

---

## Week 2+

LIMIT uses option B (buy at asks <= limit, sell at bids >= limit) and expires at the accepted regular-session close. All users consume liquidity from the same synthetic market, but user orders are never directly matched against each other. Liquidity consumed by one user reduces the shared remainder available to others.

| Endpoint | Content |
|---|---|
| `POST /orders` | limit orders (`limitPrice`, `PENDING` status) |
| `POST /orders` (fractional) | open US fractional orders. Add `allowsFractional` to the detail response; change the input unit for US stocks only |
| `GET /accounts/me/orders` | order-history tab — includes rejected orders (they don't land in the ledger) |
| `PATCH /orders/{orderId}` | Planned: only `{ "status": "CANCELED" }`, cancel active unfilled remainder; retain completed fills and ledger |
| `GET /accounts/me/assets/history` | asset trend chart (daily snapshots) |
| `GET /accounts/me/report` | investment-habit diagnosis |
| `GET /stocks/{symbol}/orderbook` | order book |
| WebSocket | realtime quote push (replaces polling) |

Not built yet, but the URL design reserves the slots so nothing collides.

---
> Mock Stock Trading Service · Current API Spec · see also `erd.md` · `wireframe.md`
