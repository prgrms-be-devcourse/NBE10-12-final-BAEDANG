# Mock Stock Trading Service — Week-1 MVP Wireframe

> **Scope**: 26.08.20 ~ 08.25 · Java 21 · Spring Boot 3.5.16 · Next.js 16.3 · PostgreSQL 18 + TimescaleDB · 6 screens
>
> **Screens**: Main / Stock Rankings / Stock Detail + Trading / Guide / My Page / Signup Funnel
>
> Contains **24 design annotations** (the numbered pins on the screens). ⚠️ **Red items are decisions that block week-1 work if not resolved.**

## Shared GNB (all screens)

- Logo: **모의주식 트레이딩**
- Nav: 메인 · 주식 종목 랭킹 · 이용 가이드 · 마이페이지
- Right: 로그인 / 회원가입 (logged out) or 홍길동님 (logged in)

---

## Main (`/`)

**Hero (trading practice)**
- Tag: 투자 연습장
- Headline: "잃어도 괜찮은 돈으로, 잃지 않는 법을 배웁니다"
- Copy: 실제 시장 시세로 국내·해외 주식을 사고팔며 투자 감각을 길러보세요. **모의 투자금 5,000만원**이 가입 즉시 지급됩니다.
- Buttons: [모의 투자금 받고 시작하기] · [이용가이드 보기]
- Note: 실제 돈이 오가지 않습니다 · 언제든 포트폴리오를 초기화할 수 있습니다
- Right preview box: 서비스 화면 미리보기 (거래 화면 · 보유 종목)

**How to use — 3 steps**
- STEP 1. **모의 투자금 5,000만원 받기** — 가입하면 자동 지급됩니다. 다 쓰면 포트폴리오를 초기화해 다시 시작할 수 있습니다.
- STEP 2. **랭킹에서 종목 고르기** — 거래대금 상위 200개 종목을 국내·해외로 나눠 보여드립니다.
- STEP 3. **실제 시세로 매수·매도** — 장 운영 시간에 시장가로 즉시 체결됩니다. 수수료와 세금도 그대로 반영됩니다.

**How is this different from a brokerage app?**
Brokerage apps are tools that **execute** trades; we are a tool that helps you **understand** them.

| | General brokerage app | Mock Stock Trading |
|---|---|---|
| Purpose | Execute trades | **Learn & practice** |
| When you make a mistake | Real loss, irreversible | **No loss, reset and retry** |
| Fees & taxes | Reflected only after the trade | **Previewed before the order** |
| Usage guidance | none | **Guide · term wiki provided** |

**CTA**: "첫 거래는 오늘, 첫 손실은 0원" — [시작하기]

**Footer note**: 본 서비스는 **투자 교육을 목적으로 하는 모의 투자 서비스**입니다. 실제 매매가 이루어지지 않으며, 특정 종목에 대한 투자 조언이나 매매 권유를 제공하지 않습니다. 시세는 토스증권 Open API를 통해 제공되며 실시간과 수 초의 차이가 있을 수 있습니다.

### Design annotations — Main
1. **Positioning & funding amount** — the first screen must read "trade with real quotes but lose nothing". 50M is larger than the real small-investor feel, so **fee/tax perception weakens**; for the educational goal 5M–10M is worth considering.
2. **3-step flow** — a summary of the guide. The path for someone who gets curious here to move to the guide.
3. **Brokerage comparison table** — the block that summarizes the project identity. Usable verbatim in the presentation.
4. **Disclaimer** — recommending buy/sell opinions is the investment-advisory domain. Make clear this is "investor education".

---

## Stock Rankings (`/rankings`)

- Headline: 주식 종목 랭킹
- Sub: 거래대금 기준 상위 100개 · 무엇을 살지 모르겠다면 여기서 시작하세요

**FX banner (top)**
```
USD / KRW  1,398.50  ▲ 2.30 (+0.16%)  15:00 기준  [환율 추이 그래프 →]
```

**Search (all stocks)**
- Placeholder: "삼성 ← 티커 또는 종목명으로 검색"
- Example results:
  - 삼성전자 `005930` `KOSPI` [개별주]
  - KODEX 삼성그룹 `102780` [ETF]
  - 삼성바이오로직스 `207940` [개별주]

**Rankings list**
- Tabs: 국내 주식 (on) / 해외 주식
- Info row: 최근 **1주 거래대금** 상위 100개 · 개별주 · 배당주 · ETF 모두 포함 · 매주 월요일 갱신 / 12:36:59 기준 · **5초마다 갱신**

| Rank | Name | Ticker | Type | Last price | Change | Trading amt | |
|---|---|---|---|---|---|---|---|
| 1 | 삼성전자 | `005930` | 개별주 | 241,500 | ▲ 5,450 (+2.31%) | 1.24조 | [거래] |
| 2 | SK하이닉스 | `000660` | 개별주 | 1,429,000 | ▲ 15,000 (+1.08%) | 9,840억 | [거래] |
| 3 | KODEX 200 | `069500` | ETF | 48,120 | ▲ 380 (+0.80%) | 3,510억 | [거래] |
| 4 | KB금융 | `105560` | 배당주 | 167,900 | ▼ 1,200 (-0.71%) | 2,180억 | [거래] |
| ⋯ | (more) | | | | | | |

**Load-more (3 button states)**
- [더 보기] — `hasNext: true` · **20 / 100개 표시 중**
- [불러오는 중…] — request in flight, blocks duplicate clicks
- "모든 종목을 불러왔어요" — `hasNext: false`, button hidden

**Cursor flow — trading-amount descending**
```
① GET /stocks/rankings?market=KR&size=20
   ← items[1~20] · nextCursor "eyJ0YSI6Ijk4NDAwMDAwMDAwMCIsImlkIjo0MTJ9" · hasNext true
② GET /stocks/rankings?market=KR&size=20&cursor=eyJ0YSI6...
   ← items[21~40] · nextCursor "eyJ0YSI6IjUxMjAw..." · hasNext true
   ⋮
⑤ ← items[81~100] · nextCursor null · hasNext false
```

### Design annotations — Rankings
5. **FX banner** — this service deals in foreign stocks, so FX is always a concern. A thin banner above the rankings informs without stealing focus. Value comes from the latest `exchange_rate` row; clicking moves to the trend chart. **Decide whether to keep showing it on the KR tab** — it's noise to KR-only users.
6. **Search scope** — search covers **all stocks (~8,500)**, not only the ranking top 100. The full master plus on-demand quotes supports Korean/English/ticker axes; Toss gives Korean names for US stocks, so "엔비디아" matches too. A result can open the detail page even when it is outside the ranked universe; show prior-close data and keep trading disabled there.
7. **KR/US tab split** — 100 by trading amount each. Sessions don't overlap, so the split is natural, and the rankings API is called once per `marketCountry`. Opening the KR tab during the US session needs a "market closed, close basis" notice.
8. **Ranking columns** — name · ticker · type · last price · change · **trading amount** (6). `tradingAmount` is used as-is from the rankings API, and **the selection criterion is the displayed value**, so users understand "why this order". With `duration=1w` it's **trailing one week** — label it "최근 1주 거래대금".
9. **Ranking display and cursor pagination** — request `size=20` and provide the 100 ranked stocks in five pages. Send `nextCursor` to the next request; hide the button when `hasNext: false`.
   **Why not OFFSET** — rankings reorder, so "21~40th" between requests can duplicate or drop a stock; a cursor points "from this point" and avoids that.
   **Encode on the trading-amount axis** — `{ "ta": tradingAmount, "id": stock_id }` wrapped in Base64URL. Since the sort axis is trading amount, the cursor must be on the same axis for "from this point" to hold.
   **Why include `stock_id`** — when two stocks share the same trading amount, order at that boundary changes every query, duplicating or dropping items. `stock_id` as the secondary sort key makes the order unique. The SQL is one tuple comparison: `(trading_amount, stock_id) < (:ta, :id)`.
   **Don't use `rank_no` as the cursor** — it's fully rewritten by the batch, so right after refresh the same number points at a different stock. Display only. Keep the cursor an **opaque server-encoded string** clients never interpret — swap internals later without touching the frontend.
   **Disable the button while a request is in flight** — spamming sends the same cursor repeatedly and piles up duplicates.

---

## Stock Detail + Trading (`/stocks/005930`)

- Breadcrumb: 주식 종목 랭킹 › 삼성전자

**Warning banner**: ⚠ 이 종목은 **투자경고 종목**으로 지정되어 있습니다. 매수 전 확인하세요.

**Stock summary**
- 삼성전자 `005930` `KOSPI` [개별주]
- Last price: **241,500** ▲ 5,450 (+2.31%)
- As of: 2026-08-11 12:36:59 기준 · **5초마다 갱신** / 조회는 장 시간과 무관하게 항상 가능

**Chart (segment control)**
- Candle unit: 일봉 (on) / 1분봉
- Period: 1개월 / 6개월 (on) / 1년
- Right: 일봉 · 최근 200봉 · 08/11 종가까지
- Chart area: (SVG line chart)

**What kind of stock is this?** [개별주]
개별주는 특정 기업 한 곳의 지분을 사는 것입니다. 그 회사가 잘되면 오르고 어려워지면 내립니다. 여러 기업에 나눠 담는 ETF보다 변동이 크기 때문에, 한 종목에 자산을 몰아넣지 않는 것이 중요합니다.
*For an ETF, **구성 종목 비중** (constituent weights) would appear here*

**Stock info**
| Field | Value | Field | Value |
|---|---|---|---|
| 상한가 | **313,500** | 하한가 | **169,500** |
| 시가총액 | 1,441조 | 상장주식수 | 5,969,782,550 |
| 거래 상태 | 정상 | 통화 | KRW |

**Trade panel (right)**
- Header: 거래하기 · 시장가 주문 · 즉시 체결
- Tabs: 매수 (on) / 매도
- Quantity: `10` [주]
- Note: 정수만 입력 · 최소 1주
- Est. price: 241,500원 (현재가)

| Item | Amount |
|---|---|
| 주문 금액 | 2,415,000 |
| 수수료 0.01% | 242 |
| 세금 | 0 |
| **총 차감액** | **2,415,242** |

- 주문가능금액: 48,240,000원
- [매수하기]
- Note: 비로그인 상태에서 누르면 회원가입으로 안내됩니다

**Order-disabled state examples**
- [장 마감 · 09:00~15:30 거래 가능]
- [거래정지 종목]
- [주문가능금액 부족]

### Design annotations — Detail + Trading
10. **Read anytime, trade only in session** — chart and quotes stay visible when closed; only the order button disables. Stopping the scheduler leaves `last_price` at the close, so **prior-close display is automatic**. The screen switches the "실시간/종가" label from `quote_at` + market calendar.
11. **Daily / 1-min toggle** — the two charts have **different data sources**. Daily = `daily_candle` (collected after close); ranked top-100 1-min candles are collected every minute in sequential 20-stock groups, while other stocks use on-demand `/candles` + 60s cache. Period selection (1M/6M/1Y) only means anything for daily, so **hide or switch the period toggle when you switch to 1-min**. ⚠️ **The candles API caps at 200**, so a 1-year chart needs `before` pagination.
12. **Stock classification intro** — choose text by `stock_category` × `leverage_factor` × `is_dividend`. Show a **volatility warning** for leverage/inverse. ⚠️ **Dividend determination is impossible via the Toss API** (no dividend data). Week 1: disable the dividend badge, go with the four categories individual·preferred·ETF·ETN.
13. **Trading is a panel inside detail, not a separate page** — navigating away lets seconds elapse, so the price the user saw ≠ the fill price.
14. **Quantity — whole shares only** — no fractional-order UI at all. **Don't render a disabled toggle** — it only invites "why won't it press?" and advertises a feature week 1 can't use. A feature that doesn't exist shouldn't be visible.
    **When opened later, the screen change is small** — the DB is already `NUMERIC(19,6)` so no schema change; only the input unit changes for US stocks. KR is whole-share-only anyway, so a toggle would have nothing to do.
15. **Fee/tax preview** — the educational core of the project. ⚠️ **Pull market-specific rates into config.** KR sell tax is 0.2%; US sell tax uses SEC Fee `0.0000206` with a `$0.01` minimum. For US orders, round in USD cents before KRW conversion, then round to whole won with **HALF_UP**. Keep all ledger amounts as integers so the `SUM(amount) = cash_balance` invariant holds exactly.
16. **Market-order button = immediate-fill single transaction** — deposit/quantity change + holding update + `FILLED` order + ledger record in one transaction. It does not pass through `PENDING` or touch reservation fields. Lock the account row with `SELECT … FOR UPDATE` to prevent double-deduction on concurrent orders; roll back entirely on technical failure. A valid business rejection is stored as `REJECTED` without balance or ledger changes. Only future limit orders use separate reservation and fill transactions. Design an idempotency key (`client_order_id`) for duplicate-click protection.
    **Tradable-universe transition** — today only the scheduled top 100 stocks per market can trade. With future on-demand quotes, requests outside the top 100 will fetch and cache price and tradability data from Toss before trading; the `is_ranked` order guard changes only when that infrastructure is introduced.
17. **Order-disable must show a reason** — market closed / suspension / insufficient deposit / insufficient holdings / stale quote. A disabled button alone leaves users guessing why.

---

## Guide (`/guide`)

- Tabs: 이용가이드 (on) / 금융 용어 위키 [부가기능]
- Headline: 이용가이드 — 이 서비스에서 거래가 어떻게 이루어지는지 안내합니다

**Left column**
1. **모의 투자금 받기** — 회원가입을 하면 **모의 투자금 5,000만원**이 자동으로 지급됩니다. 실제 돈이 아니므로 잃어도 아무 손해가 없습니다. 자금을 다 소진했거나 처음부터 다시 해보고 싶다면 마이페이지에서 **포트폴리오 초기화**를 누르면 5,000만원으로 되돌아갑니다.
2. **종목 고르기** — 주식 종목 랭킹에서 **거래대금 상위 100개** 종목을 국내·해외로 나눠 보여드립니다. 거래대금은 그 종목에 실제로 오간 돈의 규모로, 시장의 관심이 어디에 쏠려 있는지 보여주는 지표입니다. 종목명이나 티커로 직접 검색할 수도 있습니다.
3. **매수하기** — 종목 상세 페이지에서 수량을 입력하고 매수 버튼을 누르면 **현재가로 즉시 체결**됩니다. 이때 예수금에서 주문 금액과 수수료가 함께 빠져나가고, 보유 종목에 그 수량이 더해집니다. 주문 버튼을 누르기 전에 총 차감액을 미리 확인하실 수 있습니다.

**Right column**
4. **매도하기** — 보유한 종목을 팔면 매도 금액에서 **수수료와 시장별 매도 비용**이 빠진 금액이 예수금으로 들어옵니다. 국내는 0.2% 증권거래세, 미국은 SEC Fee와 달러 최소 금액을 적용합니다. 여기서 많은 초보자가 놀라는 지점이 있습니다 — **산 가격 그대로 팔면 본전이 아니라 손해**입니다. 사고팔 때마다 비용이 발생하기 때문입니다.
5. **거래 가능 시간** — 실제 주식시장과 동일하게 운영됩니다. 국내 주식은 **평일 09:00~15:30**, 미국 주식은 **한국 시간 기준 밤~새벽**에만 거래할 수 있습니다. 주말과 공휴일에는 거래가 불가능합니다. 다만 **시세 조회와 차트는 언제든 볼 수 있습니다.**
6. **거래할 수 없는 경우** — 거래정지·정리매매 종목이거나, 주문가능금액이 부족하거나, 보유 수량보다 많이 팔려고 하면 주문이 거절됩니다. 실제 시장의 규칙을 그대로 적용하고 있으며, 거절될 때는 이유를 함께 안내해 드립니다.

**Bottom note**: 참고 — 이 서비스의 시세는 실제 시장 데이터를 사용하지만 수 초의 지연이 있으며, 회원의 매수·매도는 실제 시장 가격에 영향을 주지 않습니다. 모의 투자 결과가 실제 투자 성과를 보장하지 않습니다.

### Design annotations — Guide
18. ⚠️ **Menu name is "위키" but the page is "이용가이드".** They're combined as tabs under one menu. Week 1: fill the guide only and leave the wiki tab inactive.
19. **State the "closed system"** — making clear member trades don't affect the real market prevents "why didn't my big buy move the price?" later.

---

## My Page (`/my`)

**내 계좌** — as of 12:36:59

| 총 자산 | 예수금 | 주식 평가금액 | 평가손익 |
|---|---|---|---|
| **50,412,300** | **48,240,000** | **2,172,300** | **+137,300 (+6.75%)** |

**Tabs**: 보유 종목 (on) / 체결 내역

**Holdings table**
| Stock | Qty | Avg cost | Last price | Valuation | Unrealized P&L | |
|---|---|---|---|---|---|---|
| 삼성전자 `005930` | 6 | 228,000 | 241,500 | 1,449,000 | +81,000 (+5.92%) | [거래] |
| 엔비디아 `NVDA` | 3 | $168.20 | $182.40 | 723,300 | +56,300 (+8.44%) | [거래] |

- Bottom note: 해외 종목 평가금액은 적용 환율(1,398.50 KRW/USD)로 환산

**Order history — ledger-based (체결 내역 tab)**
| Type | Description | Amount | Balance | Time |
|---|---|---|---|---|
| [매수] | 삼성전자 10주 @ 241,500 (수수료 포함) | **−2,415,242** | 47,584,758 | 08-11 12:37:02 |
| [매도] | 엔비디아 2주 @ $182.30 (수수료·세금 포함) | +509,412 | 48,094,170 | 08-11 09:14:33 |
| [초기지급] | 모의 투자금 지급 · 1회차 | +50,000,000 | 50,000,000 | 08-10 21:02:11 |

*Backing fields:* `entry_type` / `memo` / `amount` / `balance_after` / `exchange_rate` / `occurred_at`

**Portfolio reset (warning box)**
- Copy: 보유 종목과 체결 내역이 모두 정리되고 모의 투자금이 **5,000만원**으로 되돌아갑니다. 되돌릴 수 없습니다.
- [포트폴리오 초기화]

### Design annotations — My Page
20. **Week 1 = unrealized P&L only** — `(current − avg_buy) × qty`, FX-converted to KRW for foreign stocks. Realized P&L splits out in week 2. **Note** — per-stock unrealized P&L excludes fees, but **account total P&L (totalAsset − initialCash) includes them automatically** (deducted at buy). Showing both side by side lets users discover trading cost in the gap.
21. **Order history is ledger-based** — shows `ledger_entry`, not the order list. It becomes "**how the money moved**", not "what was bought" — initial funding and portfolio reset come in as single rows, the account's full history.
    **Entries are only three: buy · sell · initial funding.** Fees and taxes are **not split into separate rows — included in the buy/sell amounts**. One ledger line maps to one order, so the list is half as long. When the fee total is ever needed, `SUM(trade_order.fee)` retrieves it — nothing is lost.
    Descriptions like "삼성전자 10주 @ 241,500 (수수료 포함)" go in `memo`.
22. **FX policy** — ⚠️ **store the buy-time FX rate in `ledger_entry.exchange_rate`.** `amount` is already KRW-converted so it's not used in math — but "at what rate was this trade made" must be readable from the ledger alone to later separate FX gains from price gains. Unrecoverable if omitted now.
23. ⚠️ **Don't implement portfolio reset as a "delete".** DELETE-ing the ledger destroys the audit trail. Put a **round (round_no)** on the account and start a new round; prior ledgers are preserved, extensible to "past round scores". **A confirmation modal is mandatory.**
    **No `RESET` ledger entry** — reset is simply creating a new account, and the new account's `INITIAL_DEPOSIT` row fills that role. The prior round's close time lives in `account.closed_at`.

---

## Signup Funnel (`/stocks/005930` — blurred background)

- Background: the stock-detail screen blurred
- Modal: **거래하려면 회원가입이 필요해요**
  - 가입하면 **모의 투자금 5,000만원**을 바로 드립니다. 실제 돈이 오가지 않으니 부담 없이 시작하세요.
  - [회원가입하고 5,000만원 받기]
  - [이미 계정이 있어요 · 로그인]
  - [둘러보기만 할게요]

### Design annotations — Signup Funnel
24. ⚠️ **Week 1 does NOT implement auth.** This screen is **UX design only**; the server runs against a fixed seed user (`user_id = 1`) (`AUTH_ENABLED=false` in `.env`). Signup/login APIs arrive in week 2.
    **Still create `users`·`account` now** — a seed user + account makes everything else work.
    **Modal on trade/my-page for logged-out users** — rather than bouncing to a separate page, **show a modal over the current screen** (less drop-off). Let "둘러보기만 할게요" close it so browsing stays free. ⚠️ **Boundary: rankings·detail·guide are public; trading·my-page require login.**

---
> Week-1 MVP Wireframe · 26.08.20 ~ 08.25
