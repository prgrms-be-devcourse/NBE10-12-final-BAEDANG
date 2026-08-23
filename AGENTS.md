# Mock Stock Trading Service

A mock stock trading service for beginners, built on the Toss Securities Open API.
A tool to help users **understand** trading, not just execute it.

- MVP: 2026-08-20 ~ 08-25 · AGILE (weekly sprints) · GitFlow (Main←Develop←Feature)

## Technology and Build / Test

- Java 21 LTS · Spring Boot 3.5.16 · Next.js 16.3 · PostgreSQL 18 + TimescaleDB + Testcontainers
- External market data: Toss Securities Open API only for the MVP. Do not add the Korea Investment API.
- Preserve the existing project name and repository/package names when applying design-document updates.

```bash
cd backend && ./gradlew test      # Java 21 · Spring Boot 3.5.16 · PostgreSQL 18 + TimescaleDB + Testcontainers
cd frontend && npm run dev        # Next.js 16.3
docker compose up -d              # local PostgreSQL/TimescaleDB + Redis
```

## Project Structure

```text
backend/src/main/java/com/baedangminjok/{common,auth,market,stock,quote,trade,account}
frontend/app/{page,rankings,stocks/[symbol],guide,my} + components + lib
```

## Rules — violations break the project or risk real orders

- **`QuoteClient` must whitelist allowed call paths. NEVER call order APIs (e.g. `POST /orders`) — real-money order risk.**
- Amounts/quantities: use `NUMERIC`/`BigDecimal`. API responses return amounts as **strings**. Timestamps: `TIMESTAMPTZ` (store UTC, convert only for display).
- `ledger_entry` is append-only — no UPDATE/DELETE; offset mistakes with an opposite-sign entry.
- Trading/balance transactions start by locking the `account` row with `FOR UPDATE` (prevents double-deduction on concurrent orders).
- Buying power = `cash_balance − locked_cash`; sellable qty = `quantity − locked_quantity`. Lock `net_amount` (incl. fee & tax), never `gross_amount`.
- Fee/tax: KR sell tax is 0.2%; US sell charge is SEC Fee `0.0000206` with a `$0.01` minimum. Configure rates in `.env`; round US amounts to cents before KRW conversion and final whole-won `HALF_UP` rounding.
- Rankings provide 100 items per selected market in five pages of 20; use the opaque cursor for each next page.
- Top-100 minute candles are collected every minute in sequential 20-stock groups under the separate `MARKET_DATA_CHART` 5 TPS group. Other/off-hours charts use on-demand caching.
- Portfolio reset is NOT a delete — close the account with `CLOSED` and open a new one with `round_no + 1`.
- Never hardcode US regular-session hours — derive from the `/market-calendar` cache (DST shifts 1 hour).
- Errors use a single `ServiceException` + error-code table; return both the code and a user-facing message.

## Reference — open `docs/` when implementing

- Endpoints & response shapes → `docs/api-spec.md`
- Tables, columns, batch schedule → `docs/erd.md`
- Screens & polling intervals → `docs/wireframe.md`
