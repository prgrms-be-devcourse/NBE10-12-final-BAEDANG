# Mock Stock Trading Service — ERD (Week-1 MVP)

> **Version**: Week-1 MVP · 26.08.20 ~ 08.25 · **Auth is NOT implemented in week 1** — develop against a single seed user (`user_id = 1`)
>
> - **Badges**: Java 21 · Spring Boot 3.5.16 · PostgreSQL 18 · 12 tables · append-only ledger · round-based reset

## Contents
- [Overview](#overview)
- [Toss Securities API Mapping](#toss-securities-api-mapping)
- [Column Dictionary](#column-dictionary)
- [Stock Classification Model](#stock-classification-model)
- [Buy Processing — 2-Phase Model](#buy-processing--2-phase-model)
- [Design Rules](#design-rules)

---

## Overview

Blue tables are the **bookkeeping (accounting) side — user money**; white tables are **quotes & master data**. Money flows through exactly one path — `account → trade_order → ledger_entry → holding` — and the quote side touches only `trade_order` and `holding`.

**Legend**
- Blue = bookkeeping · White = quote/master · Purple = time-series (append only)
- `PK` primary key · `FK` foreign key · `UK` unique
- **class-①② tags** — columns used to classify stock type
- `TOSS` — tables filled from the Toss Securities API
- dashed arrow = data flow (not an FK)

**Relation summary**
| Relation | Type |
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
| daily_candle → quote_snapshot | data flow (`close_price` → `prev_close`) |

### Table Map (12)

| Group | Table | Note |
|---|---|---|
| **Bookkeeping** | `users` | member (auth added in week 2) |
| | `account` | mock account (round-based) · has `locked_cash` |
| | `trade_order` | order + fill (partly TOSS) |
| | `ledger_entry` | ledger · append only |
| | `holding` | holdings · has `locked_quantity` |
| | `daily_account_snapshot` | used on screen from week 2 |
| **Quote · Master** | `stock` | stock master (TOSS /stocks) |
| | `stock_external_id` | per-source symbol mapping |
| | `quote_snapshot` | current-price snapshot (TOSS /prices) |
| | `daily_candle` | daily candles · TimescaleDB (TOSS /candles) |
| | `minute_candle` | minute time-series · top-100 scheduler + off-universe on-demand |
| | `exchange_rate` | FX history · regular table · no FK relations |

### MVP Behavior Matrix (confirmed)

> **Read is always available for all stocks; trading is only allowed during "that market's regular session + top 100".**
> **The deciding factor is whether the stock's own market is open, not the viewer's viewpoint** — opening NVDA in the Korean daytime shows the prior close because the US market is closed.

| Time (KST) | KR top 100 | US top 100 | All other stocks |
|---|---|---|---|
| 09:00 ~ 15:30 | **5s realtime · trade O** · chart 1-min (on-demand) | prior close · trade X · chart last-session candles | prior close · trade X · chart last-session candles |
| 22:30 ~ 05:00 * | prior close · trade X · chart last-session candles | **5s realtime · trade O** · chart 1-min (on-demand) | prior close · trade X · chart last-session candles |
| Other times | prior close · trade X | prior close · trade X | prior close · trade X |

> **The chart is drawn in every cell.** During the regular session the 1-min candles continue with the 5s quotes; off-hours or a foreign-market stock shows the last session's candles as-is. An empty chart reads as "broken screen" — **always separate "not tradable" from "not viewable".**
> **Top-100 minute candles are collected once per minute by the scheduler.** Calls use the separate `MARKET_DATA_CHART` 20 TPS group and run sequentially in 20-stock groups. Off-universe and off-hours detail charts call Toss on demand and reuse the `minute_candle` rows for 60 seconds.

> ⚠️ * **US regular-session hours shift 1 hour with DST.** DST (2nd Sun of Mar ~ 1st Sun of Nov) **22:30 ~ 05:00** ← now (Aug) / Standard (1st Sun of Nov ~ 2nd Sun of Mar) **23:30 ~ 06:00**.
> **Never hardcode.** Use the `regularMarket` session times from `/market-calendar/US` — the response is already KST, so no conversion needed. Hardcoding would **block trading for an hour after open in the 1st week of Nov.**

> 📌 **How do off-universe stocks (outside top 100) get their prior close?**
> The scheduler only covers the top 100, so the other ~8,300 stocks have an empty `quote_snapshot`.
> **On entering the detail page, call `/prices` and `/candles` together, fill it, and UPSERT into `quote_snapshot`.** Once queried, the stock comes from the DB thereafter.
> To show prices in the search result list, fetch 20 rows in **one `/prices` batch call** — calling per-stock means 20 calls and hits the rate limit.

### Close-Price Data Pipeline

How the Toss candles API close becomes the screen's change rate:

```
GET /api/v1/candles?symbol=005930&interval=1d&count=200
     └ KR 15:40 / US 05:10 · 100 calls per market · ~20s
        ↓ store closePrice
daily_candle.close_price          today's close finalized
        ↓ copy right before the next session opens (KR 08:50 KST / US 09:00 ET)
quote_snapshot.prev_close
        ↓ together with last_price refreshed every 5s
change rate = (last_price − prev_close) / prev_close
        ↓
shown on rankings · detail · my page
```

> **The Toss current-price response has no change rate.** It only has `symbol · timestamp · lastPrice · currency`, so the prior close must be sourced separately and computed directly. The source of that prior close is `daily_candle`, copied into `quote_snapshot` to avoid joins.
> **Note the copy time is "right before the next session opens", not "right after close".** Copying right after close would make `prev_close = last_price`, showing 0% change throughout the off-hours.

> 📌 **Collection scope** — top **KR 100 + US 100 = 200 stocks**. The rankings API `count` max is 100, so **1 call per market completes the universe**. Selection: `duration=1w`, refreshed **every Monday KR 08:00 · US 21:00** (right before each market's open).
> **Handled only in memory cache** — `market_calendar` (session hours), current FX (1-min TTL). No reason to accumulate history.
> **When adding features later** — `wiki_term` (finance term wiki), `index_candle` (index daily candles — for benchmark return comparison).

---

## Toss Securities API Mapping

Which endpoint fills which column, and how often — **this table is the collector implementation spec**. Toss limits apply per API group, so different groups don't affect each other.

**Legend**: `TOSS` Toss Securities API · `EXTERNAL` needs another source · `OWN` generated by us

### Per-Endpoint Call Plan (as of 2026-08)

| Endpoint | Group / limit | Call cadence | Fills |
|---|---|---|---|
| `POST /oauth2/token` | AUTH · 5 TPS | once just before expiry | No DB storage. **Token must be memory-cached** — issuing on every request gets you rate-limited. |
| `GET /api/v1/stocks/all` | STOCK · **5 TPS** | **every Monday 07:00** | **Full stock list per market.** Returns in one shot without pagination (NASDAQ ~2,800 rows, 30KB gzipped). Calling each of the 7 `market`s (KOSPI·KOSDAQ·NYSE·NASDAQ·AMEX·KR_ETC·US_ETC) gives **all symbols in 7 calls**. Filters fit our design: `commonShare=true` (excl. preferred), `status=ACTIVE` (excl. delisted), `securityType` (STOCK·ETF·ETN·REIT…). |
| `GET /api/v1/stocks` | STOCK · 5 TPS | every Monday 07:00 | `stock` detail — name·currency·ISIN·`security_type`·`is_common_share`·`leverage_factor`·shares outstanding·list date, plus suspension/liquidation flags from `koreanMarketDetail`. Send the `/stocks/all` symbols **in batches of 200** — 8,500 stocks ≈ 43 calls, ~9s. |
| `GET /api/v1/rankings` | STOCK · 5 TPS | universe: Mon KR 08:00 · US 21:00 / screen rankings: 30s TTL | `stock.is_ranked`, `stock.rank_no`, `stock.trading_amount`, `prev_close` for newly included (`price.basePrice`). **100 per market, so 1 call each for KR·US completes it.** `type=MARKET_TRADING_AMOUNT`, `duration=1w`, `excludeInvestmentCaution=true`. On weekends there may be no aggregation — **keep last week's universe if empty**. |
| `GET /api/v1/prices` | MARKET_DATA · **15 TPS** | **5s** (regular session only) | `quote_snapshot.last_price`, `quote_at`, `currency`. **100 stocks per market in 1 batch call** — even at 5s this is **1.3% of the limit**. KR and US sessions don't overlap, so no concurrent load. **Stop the scheduler when the market closes** → the last value (= close) stays, naturally serving "prior close". Prices arrive as **strings — parse to BigDecimal**. |
| `GET /api/v1/price-limits` | MARKET_DATA · 15 TPS | once before session opens | `quote_snapshot.upper_limit`, `lower_limit`. **Set from prior close and fixed all day**, so no realtime polling needed. Single-item call: 100 KR stocks = 100 calls, ~7s. **US stocks have no price limits → NULL.** |
| `GET /api/v1/candles` (interval=1d) | MARKET_DATA_CHART · **20 TPS** | KR 15:40~17:10 / US-local 16:10~17:10, retry every 30m | `daily_candle`. Each retry skips stocks already stored for the expected date and fetches only missing rows. A response counts as success only when its candle date matches the calendar date. The finalized `close_price` is copied to `quote_snapshot.prev_close` before the next session. Convert `timestamp` to a KST date. |
| `GET /api/v1/candles` (interval=1m) | MARKET_DATA_CHART · **20 TPS** | **top 100: every minute, sequential 20-stock groups** / other stocks: detail-page on-demand | `minute_candle`. Top-100 calls are scheduled during each regular session. Off-hours or foreign-market stocks call on demand and reuse the last 60 seconds of cached rows. Week 2 adds limit-order fill determination and 5m/10m aggregation. |
| `GET /api/v1/stocks/{symbol}/warnings` | STOCK · 5 TPS | **not used in week 1** · 08:00 batch if needed | `stock.is_warned`. Reports liquidation·short-term overheating·investment warning/risk·VI. **Single-item call** — 100 stocks = 100 calls, ~20s. **Not in the confirmed schedule** — the rankings API's `excludeInvestmentCaution=true` already filters most of it. |
| `GET /api/v1/exchange-rate` | MARKET_INFO · 3 TPS | history: **every hour on the hour** / current: 1-min TTL cache | **Two separate paths.** Chart history is stored to `exchange_rate` hourly (24 calls/day); the current rate used for execution comes from a **1-min TTL memory cache**. Store the response `validFrom` as `rate_at` with `ON CONFLICT DO NOTHING` — **weekend duplicates filtered automatically**. |
| `GET /api/v1/market-calendar/KR·US` | MARKET_INFO · 3 TPS | app startup + once daily | **Memory cache is enough** (`schema.sql` lists `market_calendar` as optional if history is wanted). Used in three places — **① order-time validation**, **② quote scheduler on/off**, **③ screen "realtime/close" label**. **Never hardcode** because of DST·exam days·ad-hoc holidays. |
| `wss://openapi-ws/ws/v1` | 100 subs / 2 connections | **week-2 improvement** | **Realtime fill & order-book WebSocket.** 100 subs per connection, 2 connections per account → **KR 100 + US 100 = exactly 200 stocks**. Adopting it removes polling for true realtime. Requires reconnect/resubscribe, 60s PING, full-replace subscription management, and frames are **LOSSY** so loss must be tolerated. **Week 1 uses polling.** |
| `POST /api/v1/orders` etc. | — | not used | **Never call order APIs** — they'd place real orders on a real account. Pin the external market-data client's callable paths to a whitelist (currently `TossSecuritiesClient`). |

### Per-Table Data Source

| Table | Source | Notes |
|---|---|---|
| `stock` | TOSS | mostly `/stocks` + `/warnings` + `/rankings`. `stock_category` is **own** classification; `dividend_yield` and the dividend badge are **disabled in the MVP** because Toss does not provide dividend data. |
| `quote_snapshot` | TOSS | only `collected_at` is **own**. Rest from `/prices`, `/price-limits`, `/rankings`. |
| `daily_candle` | TOSS | all from `/candles?interval=1d`. 100 calls per market right after close. Decide the `adjusted` (adjusted-price) setting as a team and **pin it** — changing it later desyncs stored history. |
| `minute_candle` | TOSS | all from `/candles?interval=1m`. Top-100 rows are collected every minute in 20-stock sequential groups; off-universe and off-hours rows are fetched on detail entry and reused as a 60s cache. |
| `exchange_rate` | TOSS | all from `/exchange-rate`. Only `collected_at` is **own**. |
| `trade_order` | own + TOSS | order content is own; `executed_price`·`quote_at` copied from `quote_snapshot` (source `/prices`), `exchange_rate` from `/exchange-rate`. **Nothing is sent to Toss** — fills happen only inside our DB. |
| `holding` | own | derived from the ledger. Only `avg_exchange_rate` originates from Toss FX. |
| `ledger_entry` | own | all own. `exchange_rate` is **copied verbatim from `trade_order.exchange_rate`** (source: Toss `/exchange-rate`). The ledger is append-only, so however the order table changes later, this record survives. |
| `users` `account` `daily_account_snapshot` `stock_external_id` | own | unrelated to external APIs. **The bookkeeping side is entirely ours** — which is why this project is bookkeeping, not channel. |

### Batch Schedule (confirmed)

| Time (KST) | Cadence | Task                                                                                                                                                                                                                                       |
|---|---|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Mon **07:00** | weekly | **① Full stock-master refresh** — `/stocks/all` × 7 markets → all symbols, `/stocks` in batches of 200 → detail. New listings/delistings reflected here. ~50 calls, 15s.                                                                   |
| Mon **08:00** | weekly | **② KR top-100 by trading amount — 1 Toss call.** `/rankings?market=KR&duration=1w&count=100` → update `is_ranked`, `rank_no`, `trading_amount` · `prev_close`·daily backfill for newly included · **keep last week's universe if empty**. |
| Mon **21:00** | weekly | **③ US top-100 by trading amount — 1 Toss call.** Same as KR. 1.5h before the US open (22:30), so first quote collection starts on the fresh universe.                                                                                     |
| **08:50** | daily | KR `prev_close` ← prior `daily_candle.close_price` (10 min before open). Fetch price limits too. Not in the confirmed list but required for change rate (explained below).                                                                 |
| 09:00 ~ 15:30 | 5s | KR top-100 current-price collection — **`/prices` 1 batch call, 1.3% of limit**. Trading opens only in these hours.                                                                                                                        |
| 09:00 ~ 15:30 | 1m | KR top-100 minute-candle collection — sequential 20-stock groups in the separate `MARKET_DATA_CHART` 20 TPS group.                                                                                                                         |
| **15:40 ~ 17:10** | 30m | KR daily-candle retries — starts 10 min after calendar close, including delayed-close days; skips stocks already stored for the date.                                                                                                      |
| **09:00 ET** * | daily | US `prev_close` refresh — 30 min before regular open. 22:00 KST during DST, 23:00 KST during standard time.                                                                                                                                |
| 22:30 ~ 05:00 * | 5s | US top-100 current-price collection — 1 batch call. 23:30 ~ 06:00 in standard time (winter).                                                                                                                                               |
| 22:30 ~ 05:00 * | 1m | US top-100 minute-candle collection — sequential 20-stock groups in the separate `MARKET_DATA_CHART` 20 TPS group.                                                                                                                         |
| **US-local 16:10 ~ 17:10** * | 30m | US daily-candle retries — 05:10~06:10 KST in DST, 06:10~07:10 in standard time; skips stocks already stored for the date.                                                                                                                  |
| every hour on the hour | hourly | **FX storage** — 24 calls/day. Runs on weekends/holidays too (dupes blocked by UNIQUE).                                                                                                                                                    |
| other times | — | **Quote collection stopped.** Reads still work but show prior close; orders rejected.                                                                                                                                                      |

> ⚠️ **During the regular session, 1 batch call per 5s is all there is — 1.3% of the limit.** The batch API that fetches 200 stocks at once fully decouples user count from external-API call volume — the first problem this project had to solve, solved in one line.
> **KR and US sessions never overlap** — 09:00~15:30 and 22:30~05:00, so exactly one collector runs at any moment. No combined-load worry.

> 📌 **Why `prev_close` refresh is in the list**
> The Toss current-price response has no change rate (only `symbol·timestamp·lastPrice·currency`). The prior close must be sourced and computed: `(last_price − prev_close) / prev_close`.
> The copy time must be **"right before the next session opens", not "right after close"** — copying right after close makes `prev_close = last_price`, showing 0% change all off-hours. **Keep daily-candle storage (15:40) and the prev_close copy (next day 08:50) separate.**

> **Not in the scheduler — handled on-demand**
> · Off-hours minute candles — `/candles?interval=1m` on detail-page entry + 60s cache. Top-100 minute collection is already in the MVP scheduler; week 2 adds limit-order fill determination.
> · Off-universe quotes — fetch `/prices`·`/candles` on detail entry, UPSERT into `quote_snapshot`. No daily batch over all 8,500 stocks.
> · Buy cautions (`/warnings`) — 100 stocks ≈ 20s as single calls. Add to the 08:00 batch when needed.

> 💡 **The collector is the only point that talks to Toss.** Screens (channel side) and the ledger (bookkeeping side) only read our DB, never calling Toss directly. So switching quote providers later means swapping one `QuotePort` implementation — ledger and order code stay untouched.

---

## Column Dictionary

Every column and its intent — focused especially on **why each column exists**. It mixes columns you can add later with columns that are **unrecoverable if omitted now**.

### Bookkeeping — user money

#### `users` — member
> **Week 1 does NOT implement auth.** Signup/login screens are UX-only; the server runs against a **fixed seed user (`user_id = 1`)** (`AUTH_ENABLED=false` · `DEV_FIXED_USER_ID=1` in `.env`).
> **Create the table now anyway.** `account.user_id` references it, so retrofitting means touching the FK and data together. Leave `password_hash` **empty or dummy in week 1**; fill it in week 2 with login. Social login/OAuth is also reviewed then.
| Column | Type | Description |
|---|---|---|
| `user_id` | BIGINT PK | internal id. Auto-increment via IDENTITY. |
| `email` | VARCHAR(255) UK | doubles as login id. Normalize to lowercase before storing. |
| `password_hash` | VARCHAR(255) | **Never store plaintext.** Hash with BCrypt; default `BCryptPasswordEncoder` is enough. Empty/dummy in week 1. |
| `nickname` | VARCHAR(50) | display name. Avoids exposing email. |
| `status` | VARCHAR(20) | `ACTIVE` / `DORMANT` / `WITHDRAWN`. Withdrawal via physical delete breaks ledger FKs — **handle by status transition only**. |
| `created_at` `updated_at` | TIMESTAMPTZ | audit common columns. Recommended on all tables. |

#### `account` — mock investment account
The unit of portfolio reset. On reset, don't delete this row — **create a new row with an incremented round.**
| Column | Type | Description |
|---|---|---|
| `account_id` | BIGINT PK | orders·ledger·holdings all attach here. **Round changes the value, so the new round shows automatically.** |
| `user_id` | BIGINT FK | owner. One member can hold multiple round accounts. |
| `round_no` | INT | **portfolio-reset round.** Starts at 1, +1 per reset. Extensible to "3rd try" labels or per-round score comparisons. |
| `status` | VARCHAR(20) | `ACTIVE` / `CLOSED`. **Partial unique index** enforces one ACTIVE account per member (`WHERE status='ACTIVE'`). |
| `initial_cash` | NUMERIC(19,4) | funded amount (50M). Denominator of return rate. Stored per account so past rounds' baseline survives policy changes. |
| `cash_balance` | NUMERIC(19,4) | **total deposit.** **The row locked with `FOR UPDATE`** in buy transactions. `CHECK (cash_balance >= 0)` blocks negatives at DB level. |
| `locked_cash` | NUMERIC(19,4) | **cash tied up by unfilled orders.** Added on order acceptance, subtracted on fill/cancel.
  **Buying power is NOT stored — computed as `cash_balance − locked_cash`** — storing a derived value lets a one-sided-update bug persist quietly.
  Lock **`net_amount` including fee & tax**, not just `gross_amount` — locking only gross leaves you short by the fee at fill time.
  `CHECK (locked_cash <= cash_balance)` blocks over-locking at DB level. |
| `version` | BIGINT | JPA optimistic lock (`@Version`). A second safety net alongside pessimistic lock. |
| `opened_at` `closed_at` | TIMESTAMPTZ | round start/end times. Used for per-round operating period. |

#### `trade_order` — order + fill
MVP is immediate market fills, so order and fill are one row. **Rejected orders are kept too** — needed to explain "why it failed". When limit orders arrive, the fill part splits out into `trade_execution`.
| Column | Type | Description |
|---|---|---|
| `order_id` | BIGINT PK | order number. Also the sort key on the order-history screen. |
| `account_id` | BIGINT FK | which round's account. Only the new round's orders show after reset. |
| `stock_id` | BIGINT FK | traded stock. Uses internal id, not symbol (symbols can change and repeat across markets). |
| `client_order_id` | UUID | **Account-scoped idempotency key.** Generated by the frontend on entering the order screen. `UNIQUE(account_id, client_order_id)` blocks double-fills while allowing different users to generate the same UUID. |
| `side` | VARCHAR(4) | `BUY` / `SELL`. |
| `order_type` | VARCHAR(10) | `MARKET` in MVP. Reserving the column means no schema change when `LIMIT` arrives. |
| `quantity` | NUMERIC(19,6) | order quantity. KR is whole shares but US allows fractional — NUMERIC leaves room. |
| `status` | VARCHAR(12) | **The order lifecycle.** Market orders **do not pass through `PENDING`** — one transaction inserts them directly as `FILLED` or `REJECTED`. `PENDING` is used only by limit orders, whose acceptance/reservation and fill run in separate transactions.
  `PENDING` accepted, funds/qty locked · `FILLED` filled, unlocked + withdrawal confirmed · `REJECTED` rejected at validation (nothing locked) · `CANCELED` user cancel · `EXPIRED` timeout auto-release.
  Handle transitions with **conditional UPDATE** — if `WHERE order_id=? AND status='PENDING'` affects 0 rows, it was already canceled or taken by another worker. |
| `reject_reason` | VARCHAR(40) | `MARKET_CLOSED` · `NOT_IN_UNIVERSE` · `STOCK_SUSPENDED` · `STOCK_LIQUIDATION` · `INSUFFICIENT_CASH` · `INSUFFICIENT_QUANTITY` · `STALE_QUOTE` · `FUTURE_QUOTE` · `INVALID_SETTLEMENT_AMOUNT`. Basis for the screen message. |
| `reference_price` | NUMERIC(19,4) | Price in the stock currency used to evaluate a `REJECTED` order. Kept separate from `executed_price` because no fill occurred. |
| `executed_price` | NUMERIC(19,4) | fill price. **In the stock's currency** (USD for US stocks). KRW conversion stored separately in `gross_amount`. |
| `quote_at` | TIMESTAMPTZ | Quote timestamp used for either fill or rejection evaluation. Copied from `quote_snapshot.quote_at`. |
| `exchange_rate` | NUMERIC(19,6) | FX used for either fill or rejection evaluation. 1 for KRW stocks. |
| `gross_amount` | NUMERIC(19,4) | fill amount (in KRW). `executed_price × quantity × exchange_rate`. |
| `fee` | NUMERIC(19,4) | trading fee — **`gross_amount × 0.0001` (0.01%, buy & sell)**. **Store the applied amount, not the rate** — history must survive future fee-rate changes. |
| `tax` | NUMERIC(19,4) | market-specific sell charge. KR: **`gross_amount × 0.002` (0.2%)**. US: SEC Fee **`max(USD gross × 0.0000206, $0.01)`**, rounded to cents before KRW conversion. 0 on buy. Store the applied amount, not the rate; configure rates and minimums in `.env`. |
| `net_amount` | NUMERIC(19,4) | actual deposit change. **Buy: gross + fee (deducted) / Sell: gross − fee − tax (credited)**. Add a test that this equation always holds. |
| `ordered_at` | TIMESTAMPTZ | order acceptance time. The gap from `quote_at` is the quote latency. |

#### `ledger_entry` — ledger
**Records every event that moves the deposit. Not UPDATE-ing and not DELETE-ing is this table's reason to exist.** If recorded wrong, don't edit — add an opposite-sign entry to offset.
**Entries are only three — `INITIAL_DEPOSIT` · `BUY` · `SELL`.** Fees and taxes are **not split into separate rows — included in the buy/sell amounts**. A normal fill creates one line for its `trade_order.net_amount`, while append-only corrections and future partial fills may add later rows with the same `order_id`. Idempotent replay reads the earliest row through the `(order_id, entry_id)` index.
| Column | Type | Description |
|---|---|---|
| `entry_id` | BIGINT PK | ledger number. Increases in time order. |
| `account_id` | BIGINT FK | which account. |
| `order_id` | BIGINT FK, NULL | causing order. **NULL for initial funding and reset.** Indexed by `(order_id, entry_id)` for ordered lookup. |
| `entry_type` | VARCHAR(20) | **Only three.**
  `INITIAL_DEPOSIT` — mock funding 50M credited (+)
  `BUY` — gross + fee deducted (−)
  `SELL` — gross − fee − tax credited (+)
  **No `RESET` entry.** Reset creates a new account, and the new account's `INITIAL_DEPOSIT` row fills that role. The prior round's close time lives in `account.closed_at`. |
| `amount` | NUMERIC(19,4) | **signed deposit change, fee/tax included** — always equal in absolute value to `trade_order.net_amount`. Negative for buy, positive for sell. **The cumulative sum must equal `account.cash_balance`** — with only three entry types this invariant is very simple. |
| `balance_after` | NUMERIC(19,4) | balance right after this entry. Strictly derived, but **very useful for instantly finding where integrity broke**. |
| `exchange_rate` | NUMERIC(19,6) | **FX at fill time.** 1 for KRW stocks, the USD/KRW at that moment for US stocks. Not used in math (`amount` is already KRW-converted) — it's the audit field that answers "at what rate was this trade made" from the ledger alone. Same value as `trade_order.exchange_rate`, but the ledger is append-only, so this record survives however the order table changes. **Not shown on screen in week 1 — but unrecoverable if omitted now.** |
| `memo` | VARCHAR(200) | human-readable note. Since fees aren't split out, the breakdown lives here — "삼성전자 10주 @ 241,500 (수수료 포함)" style. Easier debugging and CS. |
| `occurred_at` | TIMESTAMPTZ | event time. `(account_id, occurred_at)` index serves period queries. |

#### `holding` — holdings
A derived aggregate from the ledger. Theoretically reconstructable by replaying the ledger, but kept separately for query performance. One row per account+stock.
| Column | Type | Description |
|---|---|---|
| `holding_id` | BIGINT PK | surrogate key. Unique on `(account_id, stock_id)`. |
| `account_id` | BIGINT FK | round account. Empty automatically after reset (new account). |
| `stock_id` | BIGINT FK | held stock. |
| `quantity` | NUMERIC(19,6) | held quantity. Decide keep-vs-delete at 0 — **keeping is recommended** (moving-average cost history continues on rebuy). |
| `locked_quantity` | NUMERIC(19,6) | **quantity tied up by unfilled sell orders.** Exactly the same principle as deposit lock — holding 10 shares and placing three 5-share sell orders would sell 15.
  **Sellable quantity = `quantity − locked_quantity`.** `CHECK (locked_quantity <= quantity)` blocks over-locking. |
| `avg_buy_price` | NUMERIC(19,4) | **moving-average cost** (stock currency). On buy: `(prev_qty×prev_price + new_qty×fill_price) ÷ total_qty`. **Not changed on sell** — it's the basis of unrealized P&L. |
| `avg_exchange_rate` | NUMERIC(19,6) | **average FX at buy time.** 1 for KRW stocks. This is what lets you later separate "how much of the profit is price gain vs FX gain". Unrecoverable if omitted now. |
| `updated_at` | TIMESTAMPTZ | last change time. |

#### `daily_account_snapshot` — daily asset snapshot
**Not used on screen in week 1, but add the batch now.** One row per day after close — a trivial job that, if skipped, leaves no historical data for the week-2 asset chart. Reconstructing from trades would need every past quote — unrealistic.
| Column | Type | Description |
|---|---|---|
| `account_id` + `snapshot_date` | composite PK | one row per account×date. |
| `cash_balance` | NUMERIC(19,4) | deposit at that day's close. |
| `stock_value` | NUMERIC(19,4) | holdings valuation (close × qty, in KRW). |
| `total_asset` | NUMERIC(19,4) | deposit + valuation. Y-axis of the asset chart. |
| `unrealized_pnl` | NUMERIC(19,4) | unrealized P&L. Realized is aggregated from the ledger, so no separate column. |

### Quote · Master

#### `stock` — stock master
Internal `stock_id` is the canonical identifier; external symbols are separated into a mapping table. Refreshed by a weekly batch (Monday 07:00).
| Column | Type | Description |
|---|---|---|
| `stock_id` | BIGINT PK | internal id. **Domain code only needs this value.** Survives source changes. |
| `symbol` | VARCHAR(20) | `005930` / `AAPL`. Unique with `market_country`. Dotted tickers (`BRK.B`) exist — **mind Spring URL path variables**. |
| `market_country` | VARCHAR(2) | `KR` / `US`. Drives the KR/US tabs and session-hours determination. |
| `market` | VARCHAR(20) | `KOSPI` / `KOSDAQ` / `NASDAQ` / `NYSE`. Screen badge. |
| `name` `english_name` | VARCHAR(200) | search targets. **`pg_trgm` GIN index** for Korean partial match. |
| `isin_code` | VARCHAR(12) | international standard id (`KR7005930003`). **Codes can change but ISIN is relatively stable** — real brokerages manage both. |
| `currency` | VARCHAR(3) | `KRW` / `USD`. Decides FX conversion need. |
| `security_type` | VARCHAR(20) | raw Toss value (`STOCK`/`ETF`/`ETN`…). **Stored unprocessed** so reclassification stays possible. |
| `stock_category` | VARCHAR(20) | **product type — exclusive, the frontend's primary branch key.** `INDIVIDUAL`/`PREFERRED`/`ETF`/`ETN`. Auto-classified in batch from `security_type` + `is_common_share`. |
| `leverage_factor` | NUMERIC(4,1) | **ETF/ETN leverage multiple — secondary branch key.** null=normal stock, 1.0=plain ETF, 2.0·3.0=leveraged, -1.0·-2.0=inverse. Basis for the volatility-warning banner. |
| `is_dividend` | BOOLEAN | **dividend attribute — a tag.** Independent of type (can tag both stocks and ETFs). KB금융 is dividend-paying AND an individual stock, so mixing type+dividend in one column can't express it. |
| `dividend_yield` | NUMERIC(6,4) | reserved annual dividend yield. Leave NULL in the MVP and disable `is_dividend`; adding a separate dividend-data source is future scope. |
| `is_common_share` | BOOLEAN | raw Toss value. `false` = preferred (삼성전자우 etc.). Preferred stocks enter the trading-amount top list — used for `stock_category = PREFERRED`. |
| `shares_outstanding` | NUMERIC(20,0) | shares outstanding. **market cap = price × this** (no separate market-cap column). |
| `list_date` `delist_date` | DATE | list / delist date. For fact sheets. |
| `is_suspended` | BOOLEAN | trading halt. **Immediate reject reason in order validation.** |
| `is_liquidation` | BOOLEAN | in liquidation — the stage right before delisting, needs a risk notice. |
| `is_warned` | BOOLEAN | investment-warning/risk designation. Doesn't block orders, just shows a **warning banner**. |
| `is_ranked` | BOOLEAN | whether in the top 100 by trading amount. **Used to decide quote collection targets.** |
| `rank_no` | INT | rank (1~100). **Display only. Don't use as a cursor** — the batch rewrites it wholesale, so right after refresh the same number points at a different stock. NULL outside the top 100. |
| `trading_amount` | NUMERIC(24,0) | **trailing 1-week cumulative trading amount (`duration=1w`).** The ranking sort key and the cursor's primary key. Showing the selection criterion as the displayed value lets users understand "why this order". **Cursor is a `(trading_amount, stock_id)` tuple** — when amounts tie, `stock_id` uniquely decides order. Index it as `(market_country, trading_amount DESC, stock_id DESC)` — same order, same direction, so it scans without an extra sort. |

#### `quote_snapshot` — current-price snapshot
**One row per stock — ~8,500 fixed rows.** Continuously UPDATEd, no history — not a time-series table.

| Target | Refresh | `quote_at` |
|---|---|---|
| **top 200** (KR 100 + US 100) | **every 5s** during that market's regular session | just now → **"12:36:59 · realtime"** |
| remaining ~8,300 | **on-demand** — `/prices`+`/candles` on detail-page entry, then UPSERT | query time → "realtime" or prior-close label per `quote_at` |

> **This unifies the screen logic.** Detail always queries only this table regardless of top-100 status, and just changes the label based on `quote_at`. **The screen never needs to know "is this stock top 100?".** Tradeability is separate — `stock.is_ranked` AND that market's regular session AND not suspended.

| Column | Type | Description |
|---|---|---|
| `stock_id` | BIGINT PK/FK | one row per stock, so PK is also FK (1:1). |
| `last_price` | NUMERIC(19,4) | current price (stock currency). Toss sends **strings — always parse to BigDecimal**. double would drift the balance. |
| `prev_close` | NUMERIC(19,4) | **prior close.** Toss current-price has no change rate, so compute `(last_price − prev_close)/prev_close`. **Copied from the prior `daily_candle.close_price`** (KR 08:50 / US 22:00, right before next open). Fallback: copy `last_price` if daily collection failed (the closing value is the close); newly included stocks initialize from the rankings `price.basePrice`. **Why "right before next open", not "right after close"** — refreshing right after close makes `prev_close = last_price`, showing 0% change all off-hours. |
| `upper_limit` `lower_limit` | NUMERIC(19,4) | price limits. **Set from prior close and fixed all day — fetch once before open.** Used in order-price validation. |
| `currency` | VARCHAR(3) | price currency. Duplicated from `stock` for join-free quote reads. |
| `quote_at` | TIMESTAMPTZ | **the quote timestamp Toss reports.** Two uses — the "12:36:59" label, and **order freshness validation** (reject as `STALE_QUOTE` if >15s old). |
| `collected_at` | TIMESTAMPTZ | when we collected it. Gap from `quote_at` monitors collection-latency. |

#### `daily_candle` — daily candles
Two purposes — **the daily chart** and **the `prev_close` source**. Collected right after close to finalize the day's candle; that `close_price` is copied to `quote_snapshot.prev_close` before the next session, becoming the change-rate baseline. Daily and minute candles are stored in **TimescaleDB** so both chart time series use the same retention and time-based query model. 200 stocks × 250 trading days = **50k rows/year, ~3MB**, so the daily hypertable remains small even while the minute hypertable grows.

| Column | Type | Description |
|---|---|---|
| `stock_id` + `trade_date` | composite PK | one row per stock×trading day. The response `timestamp` is a time — **convert to a KST date**. Slicing in UTC shifts US stocks by a day. |
| `open_price` | NUMERIC(19,4) | open. |
| `high_price` `low_price` | NUMERIC(19,4) | high/low. Draw the candle wicks. |
| `close_price` | NUMERIC(19,4) | close. Copied to the next trading day's `prev_close` — the change-rate denominator. |
| `volume` | NUMERIC(20,0) | volume. Bars at the chart bottom. |

> ⚠️ **Decide the adjusted-price setting now.** Splits/bonus issues retro-adjust past prices. Agree on Toss's `adjusted` param and **pin it**. **Long charts need pagination** — `count` max is 200, so 200 daily ≈ 10 months; a 3-year chart needs four `before` calls (storing it removes that burden). **Weekly/monthly candles aren't provided by the API** — only `1m`·`1d`; aggregate them from this table.

#### `minute_candle` — minute time-series
**Top-100 stocks are collected once per minute.** The collector uses the separate `MARKET_DATA_CHART` 20 TPS group and sequential 20-stock groups, then stores the received candles in this table. Off-universe and off-hours detail pages call `/candles?interval=1m` on demand and serve from the DB for the next 60s — **the table doubles as storage and cache**.
**The chart still renders off-hours.** Calling `/candles` on a closed market returns the last session's candles as-is — opening NVDA in the Korean daytime shows the prior close and last US session's minute chart. The screen just flips the "실시간/종가" label from `quote_at` + market calendar; the chart itself needs no branching.
**200 candles per call.** KR regular session 09:00~15:30 = 330 minutes, so a full day needs `before` × 2 calls. If the week-1 chart is "last 200 minutes", 1 call suffices — keep 1 call as the default and use 2 only when "view all" is pressed.
**Needs measurement** — whether `before` is inclusive, and whether the **closing auction (15:30) candle exists**. Without a 15:30 candle it's 329, not 330 — count-based validation breaks there. Overlapping boundary candles are filtered by `ON CONFLICT DO NOTHING` on `(stock_id, candle_at)`.
**Week 2 adds limit-order fill determination and aggregation.** A fill engine must query past candles whether or not a user is viewing a chart — "did the limit get touched within that minute" is `low <= limit`. **The table structure stays the same; the collection is extended with these consumers.**

| Column | Type | Description |
|---|---|---|
| `stock_id` + `candle_at` | composite PK | `candle_at` is the **candle start time** (response `timestamp`). TimescaleDB hyper-tables must include the partition key in the PK — this structure already satisfies that. |
| `open_price` | NUMERIC(19,4) | open of that minute. |
| `high_price` `low_price` | NUMERIC(19,4) | high/low. Draws the candle wicks, and later serves **limit-order fill determination** — `low <= limit` (buy) tells whether the limit was touched within the minute. |
| `close_price` | NUMERIC(19,4) | close. |
| `volume` | NUMERIC(20,0) | volume. |

> ⚠️ **Use TimescaleDB for both daily and minute candles.** Its `continuous aggregate` can derive 5m/15m candles from 1m as views without adding tables. Keep daily candles sourced from the API with `adjusted=true`; do not derive adjusted daily history from minute data. Also: **hyper-tables can't be referenced by FKs from other tables**, compression/continuous aggregates are **TSL-licensed**, and managed DBs (AWS RDS) mostly don't support them — affects deployment.

#### `exchange_rate` — FX history (regular table)
**A regular append-only table, not a TimescaleDB hypertable.** Quotes are UPDATEd in `quote_snapshot` (no history), but FX must be plotted, so it is stored per point. No FK links to other tables — **the FX the ledger needs is "that moment's value", not a reference**. Correcting FX later must never shake past fills.

| Column | Type | Description |
|---|---|---|
| `exchange_rate_id` | BIGINT PK | surrogate key. Actual identity is the `(base_currency, quote_currency, rate_at)` unique. |
| `base_currency` `quote_currency` | VARCHAR(3) | the pair. MVP has only USD → KRW, but keeping columns means no schema change if more currencies arrive. |
| `rate` | NUMERIC(19,6) | **buy rate** — what you actually pay when buying dollars. The gap from `mid_rate` is the conversion spread, itself a cost of trading — educational material in the same vein as fee/tax. |
| `mid_rate` | NUMERIC(19,6) | **interbank mid rate** — what people usually mean by "the exchange rate". Used for chart display and valuation conversion. |
| `rate_at` | TIMESTAMPTZ | the rate's point in time — **the response `validFrom` verbatim**. Toss refreshes per minute and gives a `validFrom~validUntil` window. Queried at 10:03:27, the rate's moment is 10:03:00 — the chart's X-axis must use this. |
| `collected_at` | TIMESTAMPTZ | when we received it. Gap from `rate_at` shows collection latency. |

> **Collection: every hour on the hour (confirmed).** FX moves only 0.3–0.5%/day, so per-minute is noise. Hourly storage lets you aggregate daily/weekly/monthly charts all from here. Conversely, storing only daily would leave no data when "hourly view" is asked later. **Run on weekends/holidays too** — Toss keeps returning the same `validFrom`, blocked by the `UNIQUE (base_currency, quote_currency, rate_at)`. With `ON CONFLICT DO NOTHING` the scheduler needs no weekend logic. Real volume is ~**6,000 rows/year**, weekdays mostly. **Skipping an hour is fine** — one missing point on the chart; don't retry, wait for the next hour. Alert only on consecutive failures.

#### `stock_external_id` — per-source symbol mapping
Toss calls 삼성전자 `005930`; future sources may use another identifier such as DART's `00126380`. **Creating it now means adding/swapping sources never touches domain code.** Nearly free now; retrofitting means touching everything later.

| Column | Type | Description |
|---|---|---|
| `stock_id` + `source` | composite PK | `source` is `TOSS` in the MVP; `DART` / `FINNHUB` are reserved for future integrations. |
| `external_id` | VARCHAR(50) | the identifier used by that source. Unique on `(source, external_id)` to **prevent one external id mapping to two stocks**. |

---

## Stock Classification Model

Expose leverage/inverse/preferred stocks **rather than hiding them**, with different guidance per type. Hiding products users don't know means they first meet them in real life; explaining in a zero-loss environment fits "a tool to help understand trading".

### Type (exclusive) × Attribute (tag) combinations
Dividend is an **attribute, not a type**. KB금융 is both dividend-paying and an individual stock, which one column can't express — hence the separation.

| Combination | Screen badge | Guidance text (frontend static) |
|---|---|---|
| `INDIVIDUAL` | 개별주 | Buying a stake in one company. It rises when the company does well, falls when it struggles. More volatile than ETFs that spread across companies — don't concentrate assets in one stock. |
| `INDIVIDUAL` + `is_dividend` | 개별주 · 배당주 | A company that regularly shares part of its profit with shareholders. Income can come from dividends even without big price gains. |
| `PREFERRED` | 우선주 | No voting rights, but dividends paid first. Moves differently from the same company's common shares, with thin volume. |
| `ETF` + leverage = 1.0 | ETF | A basket of many stocks. One company wobbling is diluted — less volatile than individual stocks. |
| `ETF` + leverage ≥ 2.0 | **레버리지 ETF** ⚠ warning banner | **If the index rises 1%, it rises ~2%; falls 1%, falls ~2%.** It also resets daily returns, so holding long-term can leave a loss even if the index returns to the start. |
| `ETF` + leverage < 0 | **인버스 ETF** ⚠ warning banner | **Rises when the index falls.** A reverse bet — loses when the market rises. Like leveraged, unfavorable long-term. |

> ⚠️ **Explain the "daily reset" of leverage — the biggest money-loser for beginners.** Even if the index goes +10% then −9.09% back to start, a 2× leveraged fund doesn't recover principal. Learning this in a safe environment is close to this service's reason to exist.

> 📌 **Batch classification rules**
> `stock_category` = ETN if ETN, ETF if ETF, `PREFERRED` if `is_common_share = false`, else `INDIVIDUAL`.
> `is_dividend` = `dividend_yield >= threshold`. Threshold is a team decision; week 1 may set all `false` and disable the badge (the Toss API has no dividend data).

---

## Order Processing — Market Now, Limit Later

### Market order — immediate fill in one transaction

Market orders have no committed intermediate state. Start by locking the account row, validate against `cash_balance − locked_cash` or `quantity − locked_quantity`, then update the account/holding, insert a `FILLED` order, and append one ledger entry in the same transaction. Do not increase and decrease locks that no other transaction can observe.

A structurally valid request rejected by static preflight validation, or by an expired market context, creates no order row and may be retried with the same `client_order_id`. Only a business rejection confirmed inside the transaction—including a stock state that changes after preflight while waiting for the account lock—commits a `REJECTED` order with `reject_reason` without changing cash, quantity, or the ledger. That stored rejection is final, so a new attempt uses a new `client_order_id`. Malformed requests that cannot satisfy the order FKs or quantity type are returned as request errors and are not order rows.

### Limit order — two separate transactions

Limit orders use the following two phases. Phase 1 commits `PENDING` with a reservation; a fill worker runs Phase 2 later. Cancellation, expiration, and recovery are mandatory when this flow is introduced.

### Phase 1 — Order acceptance [lock]
| Step | Action | Description |
|---|---|---|
| ① | `SELECT … FOR UPDATE` | Lock the account row. **Lock BEFORE validation** so values can't change in between. |
| ② | validate | market hours · `is_ranked` · suspension · quote freshness (15s) · **buying power = `cash_balance − locked_cash` ≥ `net_amount`** |
| ③ | `locked_cash += net_amount` | **Freeze funds.** Lock `net_amount` including fee & tax — locking only `gross_amount` leaves you short by the fee at fill. |
| ④ | `INSERT trade_order (PENDING)` | `(account_id, client_order_id)` unique violation = duplicate click → return the existing order result. |

### Phase 2 — Fill [confirm]
| Step | Action | Description |
|---|---|---|
| ① | `locked_cash −= ?` `cash_balance −= ?` | Re-lock the account → **apply unlock and actual withdrawal simultaneously**. |
| ② | `UPSERT holding` | Increase quantity + recompute moving-average cost & FX.
  **Lock order is always `account` → `holding`** — crossing orders deadlocks. |
| ③ | `UPDATE … WHERE status='PENDING'` | **Conditional UPDATE prevents races.** If 0 rows affected, it was already canceled or taken by another worker — roll back. |
| ④ | `INSERT ledger_entry` | ledger record. append only — **one `BUY` line with the fee included, plus `exchange_rate`**. |

> 💡 **Limit sells are symmetric.** Phase 1 increases `holding.locked_quantity`; Phase 2 decreases `quantity` and `locked_quantity` together and credits the deposit. **Don't touch `avg_buy_price`** — under moving-average accounting, selling reduces quantity and cost basis proportionally, so the remaining per-share average does not change.

> ⛔ **Required once limit orders arrive** — if a crash hits right after Phase 1 commits, the **locked funds never unlock**. Users see "I have money but can't order." Build a timeout auto-release batch:
> `UPDATE trade_order SET status='EXPIRED' WHERE status='PENDING' AND ordered_at < now() − INTERVAL '5 min'` → refund `locked_cash` by that amount.
> **Market orders need no such batch** — their immediate-fill transaction ends in a full rollback on technical failure.

> ✅ **One more invariant.** `locked_cash = SUM(trade_order.net_amount WHERE status='PENDING')` must always hold. An orphan PENDING breaks this equation and is caught instantly.

---

## Design Rules

1. **All amounts are `NUMERIC`** — `double`/`float` start subtly drifting the balance. Use `BigDecimal` in Java too — the same reason Toss returns prices as strings.
2. **All timestamps are `TIMESTAMPTZ`** — with KR/US sessions and DST mixed, store UTC and convert only for display.
3. **Buy transactions start by locking the account row** — begin with `SELECT … FROM account WHERE account_id = ? FOR UPDATE` to stop concurrent orders double-deducting the deposit. Locking per-stock can't prevent it.
4. **The ledger is append-only** — offset mistakes with an opposite-sign entry, never edit.
5. **Portfolio reset is not a delete** — lock the requested current `account_id` with `FOR UPDATE`, close it, then create the `round_no + 1` account and its `INITIAL_DEPOSIT` ledger entry in one transaction. Closing, opening, and ledger insertion share one UTC timestamp; prior ledgers/orders/holdings are preserved. An immediate retry with the same closed account ID returns the previously created account instead of opening another round.
6. **Five things are unrecoverable if omitted now** — `trade_order.quote_at`, `trade_order.exchange_rate`, `ledger_entry.exchange_rate`, `holding.avg_exchange_rate`, and the `daily_account_snapshot` batch. Columns can be added later, but **past values can't be restored**.

> 🧪 **Good verification tests** — after every trade, check `buy: net_amount = gross_amount + fee` and `sell: net_amount = gross_amount − fee − tax` always hold, and the cumulative sum of `ledger_entry.amount` (fee included) equals `account.cash_balance`. The surest proof you understand the ledger.

---
> Mock Stock Trading Service · Week-1 MVP ERD · see also `schema.sql`
