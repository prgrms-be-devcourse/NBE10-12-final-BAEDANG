# Shared Components Guide

English | [한국어](shared-components.ko.md)

> Synchronization rule: update both `shared-components.md` and `shared-components.ko.md` whenever public methods, examples, or policies change.

This is an index of global configuration, shared utilities and calculators, domain-shared services and policies, and frontend modules reused across the project. Check existing components before implementing a new feature.
Shared does not mean purely functional. Distinguish each component's role, invocation or injection method, and side effects; private service methods and trivial getters are not enumerated.

This document describes existing behavior. Also consult the [API specification](api-spec.md) for API contracts and the [ERD](erd.md) for storage policies. Update this guide when a shared component's public methods or policies change.

## 1. Quick Selection

| Task | Feature to use | Invocation |
| --- | --- | --- |
| Normalize symbols, currencies, emails, or search keys | `DomainNormalizer` | Static methods |
| Convert BigDecimal values to API response strings | `FinancialDecimalFormatter` | Static methods |
| Parse country codes; obtain market time zones and default currencies | `MarketCountry` | Static / enum methods |
| Calculate account or holding return ratios | `ReturnRateCalculator` | Static method |
| Calculate order gross amounts, fees, and taxes | `MarketOrderSettlementCalculator` | Inject a Spring bean |
| Calculate limit-order cumulative settlement deltas | `LimitOrderSettlementCalculator` | Inject a Spring bean |
| Value holdings | `HoldingValuator` | Inject a Spring bean |
| Obtain the current time or market-local date | `Clock` from `TimeConfig` | Inject Clock |
| Validate chart combinations, resolve trading days, or classify realtime quotes | Domain Policy / Resolver | Inject a Spring bean |
| Calculate and display frontend amounts | `D`, `format.ts`, `order-amount.ts` | Import modules |

## 2. Global Configuration and Infrastructure

The `global` package provides foundations shared across domains; not every file is a static helper. Normally, inject the beans registered by configuration classes or rely on automatic framework integration rather than calling configuration methods directly.

### Configuration and Shared Entities

| Source | Role / usage | Caveat |
| --- | --- | --- |
| [TimeConfig](../back/src/main/java/com/baedang/global/config/TimeConfig.java) | Provides a UTC `Clock` bean. Inject `Clock` through the constructor and use `clock.instant()` | Replace it with a fixed Clock in tests. See the market information section for local-date and UTC conversion examples |
| [PasswordConfig](../back/src/main/java/com/baedang/global/config/PasswordConfig.java) | Inject `PasswordEncoder`; call `encode(raw)` and `matches(raw, encoded)` | Currently uses BCrypt. Do not implement separate hashing or repeatedly construct encoders |
| [JpaConfig](../back/src/main/java/com/baedang/global/config/JpaConfig.java) | Automatically enables JPA Auditing and `auditingDateTimeProvider` | The current provider directly uses `OffsetDateTime.now(ZoneOffset.UTC)`; fixing the injected Clock does not fix auditing timestamps |
| [BaseEntity](../back/src/main/java/com/baedang/global/entity/BaseEntity.java) | Inherit to populate `createdAt` and `updatedAt` automatically | Only for tables with both `created_at` and `updated_at`. Does not replace account `openedAt` or ledger `occurredAt` |
| [SchedulingConfig](../back/src/main/java/com/baedang/global/config/SchedulingConfig.java) | Provides common `taskScheduler`, dedicated `limitOrderTaskScheduler` and `orderBookTaskScheduler`, plus `dailyCandleTaskExecutor`. Inject that executor with `@Qualifier("dailyCandleTaskExecutor")` | Order-book refresh and retention are serialized on the one-thread order-book scheduler, separate from common batches, and their DB transactions use a local 2-second lock timeout. Daily candles use one thread, queue capacity 10, and up to 30 seconds for shutdown. Do not indiscriminately share these executors; each scheduler owns its activation conditions |
| [CorsConfig](../back/src/main/java/com/baedang/global/config/CorsConfig.java) | Automatically applies to `/api/**`; configure origins via `cors.allowed-origins` / `CORS_ALLOWED_ORIGINS` | No direct invocation needed. CORS does not replace authentication or authorization |

### Error Handling and External Communication

| Source | Role / usage | Caveat |
| --- | --- | --- |
| [BusinessException](../back/src/main/java/com/baedang/global/error/BusinessException.java), [ErrorCode](../back/src/main/java/com/baedang/global/error/ErrorCode.java) | Signal business errors with `throw new BusinessException(ErrorCode.…)`; optionally supply detail or data | Detail is for developer diagnostics; data is for client decisions. Preserve existing error-code and retry contracts |
| [GlobalExceptionHandler](../back/src/main/java/com/baedang/global/error/GlobalExceptionHandler.java), [ErrorResponse](../back/src/main/java/com/baedang/global/error/ErrorResponse.java) | Automatically converts exceptions to HTTP error responses | Do not duplicate the same try/catch and response construction in each controller |
| [TossSecuritiesClient](../back/src/main/java/com/baedang/global/clients/toss/TossSecuritiesClient.java) | Inject into Toss adapters and call `get(path, queryParams, responseType)` | Business services use existing Ports. Do not bypass allowed-path validation or the global RateLimiter; never call real-money order APIs |
| [TossRateLimiterRegistry](../back/src/main/java/com/baedang/global/clients/toss/TossRateLimiterRegistry.java), [TossApiGroup](../back/src/main/java/com/baedang/global/clients/toss/TossApiGroup.java), [Whitelist](../back/src/main/java/com/baedang/global/clients/toss/Whitelist.java) | Shared per-group request limits and path mapping; the registry exposes `acquire(group)` and `tryAcquire(group)` | The Toss client already applies limits to ordinary calls, so do not acquire another permit for the same request in an upstream service. Limits are shared within an application instance |
| [FixedIntervalGate](../back/src/main/java/com/baedang/global/clients/FixedIntervalGate.java) | Shared token-bucket gate algorithm in `com.baedang.global.clients` | Reused across broker integrations. Toss and KIS maintain separate gate instances; permits are never cross-borrowed between brokers |
| [KisSecuritiesClient](../back/src/main/java/com/baedang/global/clients/kis/KisSecuritiesClient.java), [KisTokenProvider](../back/src/main/java/com/baedang/global/clients/kis/KisTokenProvider.java), [KisRateLimiter](../back/src/main/java/com/baedang/global/clients/kis/KisRateLimiter.java), [KisWhitelist](../back/src/main/java/com/baedang/global/clients/kis/KisWhitelist.java) | Whitelisted client for KIS Developers APIs with token double-check locking and 65s failure cooldown | Restricted strictly to OAuth token generation and 5 whitelisted GET paths. Rate-limited (3 TPS before 2026-09-10 KST / 18 TPS from 2026-09-10 KST). Never call real order, amend, cancel, or account APIs |
| [KisProperties](../back/src/main/java/com/baedang/global/clients/kis/KisProperties.java) | Validated KIS configuration record (`kis.*`) | Empty credentials allowed only when `kis.enabled=false`. Enforces 1..18 TPS and positive timeouts/TTLs at startup |

## 3. String Normalization — DomainNormalizer

Source: [DomainNormalizer.java](../back/src/main/java/com/baedang/global/normalizer/DomainNormalizer.java)

Package: `com.baedang.global.normalizer`

| Method | Transformation | Example |
| --- | --- | --- |
| `symbol(value)` | Trim surrounding whitespace + uppercase | `" intc "` → `"INTC"` |
| `currency(value)` | Trim surrounding whitespace + uppercase | `" usd "` → `"USD"` |
| `email(value)` | Trim surrounding whitespace + lowercase | `" User@Example.COM "` → `"user@example.com"` |
| `searchKey(value)` | Remove internal and surrounding whitespace matched by `\s+` + lowercase | `" 삼 성 Elec "` → `"삼성elec"` |
| `upperCode(value)` | Trim a general code + uppercase | `" buy "` → `"BUY"` |
| `lowerCode(value)` | Trim a general code + lowercase | `" 1D "` → `"1d"` |

- All case conversion uses `Locale.ROOT`, not the server's default locale.
- Every method preserves `null`. Whitespace-only input may become an empty string.
- Normalization is not validation. `currency("xxx")` returns `"XXX"`; it does not determine whether the currency is supported.
- Required-field checks and the error code, detail, and structured data of `BusinessException` remain with the caller. For example, do not assume orders and charts use the same error for a missing symbol.
- The search service separately treats null stock fields as empty strings. Do not extend this domain policy to every helper's null handling.
- `symbol()` does not remove internal whitespace. Do not substitute `searchKey()` for symbol normalization.
- Do not apply these transformations indiscriminately to passwords, tokens, opaque cursors, UUIDs, or quantity strings.

```java
import com.baedang.global.normalizer.DomainNormalizer;

String symbol = DomainNormalizer.symbol(rawSymbol);
String currency = DomainNormalizer.currency(rawCurrency);
// Apply required-field and allowed-value checks according to the service's existing rules.
```

## 4. Response Number Formatting — FinancialDecimalFormatter

Source: [FinancialDecimalFormatter.java](../back/src/main/java/com/baedang/global/formatter/FinancialDecimalFormatter.java)

Package: `com.baedang.global.formatter`. Input is `BigDecimal`; output is `String`.
This converts completed calculations into response or explanatory strings, not numbers for calculation or storage.

| Method | Policy | Example |
| --- | --- | --- |
| `plain(value)` | Remove trailing zeros without rounding; no exponent notation | `1.2300` → `"1.23"`, `0.0000` → `"0"` |
| `rate(value)` | For exchange rates; same as `plain()`, no rounding | `1383.600000` → `"1383.6"` |
| `averagePrice(value)` | For average purchase prices; same as `plain()`, preserving fractional precision | `71166.6667` → `"71166.6667"` |
| `usd(value)` | `HALF_UP` to two decimal places, always displaying two places | `88.335` → `"88.34"`, `90` → `"90.00"` |
| `krw(value)` | `HALF_UP` to zero decimal places | `122199.552` → `"122200"` |
| `currency(value, currency)` | Normalize the currency code, then select the KRW / USD policy | `(90, " usd ")` → `"90.00"` |

- Every method returns null for a null value. Guarantee non-null required response fields before DTO mapping.
- For a non-null value, `currency()` throws `IllegalArgumentException` if the currency is null, blank, or unsupported. A null value returns null before currency validation.
- `rate()` does not round or pad an exchange rate to six decimal places. Calculating a stored average exchange rate and formatting its string representation are separate policies.
- Use `averagePrice()`, not `krw()` / `currency()`, for `avgBuyPrice`, even for KR stocks. Removing the fractional part of an average unit price before multiplying by quantity changes the reconstructed cost.
- Do not parse formatted strings back into BigDecimal for further calculations.

```java
import static com.baedang.global.formatter.FinancialDecimalFormatter.*;

String quantityText = plain(quantity);
String rateText = rate(exchangeRate);
String averagePriceText = averagePrice(avgBuyPrice);
String cashText = krw(cashBalance);
String priceText = currency(lastPrice, stock.getCurrency());
```

## 5. Market Information — MarketCountry

Source: [MarketCountry.java](../back/src/main/java/com/baedang/stock/entity/MarketCountry.java)

| Method | Result / purpose | Caveat |
| --- | --- | --- |
| `parse(raw)` | `Optional<MarketCountry>`; `" kr "` → KR | Empty for null, blank, or unsupported codes; the caller chooses the error response |
| `zoneId()` | KR: `Asia/Seoul`, US: `America/New_York` | For market-local dates; accounts for US DST |
| `defaultCurrency()` | KR: `"KRW"`, US: `"USD"` | Does not validate the actual stock or quote currency |
| `fromMarket(market)` | Exchange code → country | Separate from country-code parsing; pass an uppercase exchange code |
| `marketsNameMap()` | Exchange-to-country mapping | Unmodifiable Map |

`fromMarket()` maps KOSPI, KOSDAQ, and KR_ETC to KR; NYSE, NASDAQ, AMEX, and US_ETC to US.
Unsupported non-null exchange codes produce `TOSS_API_ERROR`. The current implementation looks up a `Map.of` map, so it is not a null-safe parser. Validate nullable input before calling it.

```java
MarketCountry country = MarketCountry.parse(rawCountry)
        .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_INPUT));
// This example uses only a basic error. Preserve existing field/retryPolicy contracts for orders, etc.
```

### Current Time and Fixed-KST Policies

Inject the UTC `Clock` provided by [TimeConfig.java](../back/src/main/java/com/baedang/global/config/TimeConfig.java).

```java
Instant now = clock.instant();
OffsetDateTime utcNow = now.atOffset(ZoneOffset.UTC);
LocalDate marketDate = now.atZone(country.zoneId()).toLocalDate();
```

- Start new time-dependent logic from `clock.instant()`. Inject `Clock.fixed(...)` in tests.
- Do not blindly replace KST daily-candle storage, KST chart timestamps, KST exchange-rate reference dates, or KST cache dates with `zoneId()`. Market-local dates and fixed-KST dates serve different policies.
- Do not infer opening, closing, or holidays from the time zone alone. Use the existing calendar / session Ports for market sessions.

## 6. Return Ratios and Order Amounts

### ReturnRateCalculator — Account Return Ratios

Source: [ReturnRateCalculator.java](../back/src/main/java/com/baedang/account/support/ReturnRateCalculator.java)

Package: `com.baedang.account.support`

```java
BigDecimal pnlRate = ReturnRateCalculator.calculate(unrealizedPnl, costBasis);
String pnlRateText = FinancialDecimalFormatter.plain(pnlRate);
```

- Argument order is **profit/loss, cost basis**. Supply completed, non-null amounts for both.
- For a positive cost basis, calculate `profit/loss / cost basis` with four decimal places and `HALF_UP`.
- A non-positive cost basis returns null. Zero profit/loss with a positive cost basis returns `0.0000`.
- No percentage conversion is performed. `1 / 8` returns `0.1250`, not 12.5.
- Current callers are `AccountService` and `HoldingsResponse`. Do not replace six-decimal exchange-rate / stock-price change ratios or average purchase price / exchange-rate calculations with this method.

### MarketOrderSettlementCalculator — Order Calculations

Source: [MarketOrderSettlementCalculator.java](../back/src/main/java/com/baedang/trading/service/MarketOrderSettlementCalculator.java)

`calculate(marketCountry, side, executedPrice, quantity, exchangeRate)` → `MarketOrderAmount`.
Inject this Spring bean so it uses the configured fee and tax rates. The order policy validates input and tradability.

Rates and the SEC minimum are project-fixed `.env` settings, not per-order snapshots. Keep them unchanged across restarts/deployments while orders are active.

- KR orders do not use the supplied exchange rate in the calculation; the result's exchange rate is 1.
- US orders round the unit price to cents with `HALF_UP`, multiply by quantity, apply the exchange rate, then round to whole won with `HALF_UP`.
- The trading fee is calculated from the gross amount already rounded to whole won, then rounded to whole won itself.
- For US sells, round `max(USD gross amount × configured rate, configured minimum)` to cents, apply the exchange rate, then round to whole won for the SEC Fee.
- Buy net amount is gross amount + fee. Sell net amount is gross amount − fee − tax.
- The result also contains the KRW gross amount before rounding. Do not confuse cost-basis calculation values with settlement amounts.
- The private `krw()` / `usd()` methods in this class return BigDecimal for calculations. Do not replace them with the identically named string Formatter methods.

### LimitOrderSettlementCalculator — Limit-Order Cumulative Settlement

Source: [LimitOrderSettlementCalculator.java](../back/src/main/java/com/baedang/trading/service/LimitOrderSettlementCalculator.java)

Calculates settlement deltas for one book level against the order's cumulative state. Inject this Spring bean; it shares fee/tax rates with `MarketOrderSettlementCalculator` via the same `.env` settings. The calculator performs pure computation — level selection, quantity search, external lookups, and DB writes are the caller's (engine/worker) responsibility.

#### `calculate(country, side, price, quantity, exchangeRate, previous)` → `LimitOrderSettlementResult`

Computes this level's gross/fee/tax/net deltas by subtracting the previous cumulative totals from the new cumulative totals. This eliminates rounding accumulation errors that arise when computing each fill independently.

- Input `previous` is a `CumulativeSettlementState` reconstructed from stored executions via `TradeExecutionRepository.summarizeByOrderId()`, or `CumulativeSettlementState.empty()` for the first fill.
- KR price must be a whole number; US price must be representable at two decimal places (cents). Trailing zeros are allowed.
- Quantity must be a positive integer not exceeding `maxOrderQuantity`, and cumulative quantity (`previous.quantity + quantity`) is also validated.
- KR ignores the supplied exchange rate and uses 1. US requires a positive rate representable at six decimal places.
- `validateState()` re-derives cumulative amounts from stored values and the current fixed rates. If the stored state is inconsistent with the fixed rates, it throws `INTERNAL_ERROR` instead of silently overwriting past amounts.
- Returns `isExecutable() == false` (amounts = null, previous state unchanged) when `netAmountKrw <= 0`. The caller must defer the candidate without recording a fill, ledger entry, or consuming liquidity.
- SEC Fee uses the order-cumulative USD gross, not per-fill gross. The minimum ($0.01) applies once across all fills. Only the SEC delta for this fill is converted to KRW at the current exchange rate; earlier fills retain their original rate.
- Money validation (`MONEY_LIMIT`) checks cumulative totals, not just deltas.

#### `initialReservedCash(country, limitPrice, quantity, exchangeRate)` → `BigDecimal`

Computes the initial KRW buy reserve for order acceptance (Phase 1). Internally calls `calculate()` with `CumulativeSettlementState.empty()` and `OrderSide.BUY`, returning the net amount. No exchange-rate buffer is applied; KR uses 1 without an external lookup.

#### `reserveAfterBuy(reservedCash, remainingQuantity, fillQuantity, netAmountKrw)` → `BuyReservationResult`

Determines whether a buy fill is affordable within the order's current `reservedCash`, without accessing free cash.

- Full fill (`fillQuantity == remainingQuantity`): executable, releases unused reserve (`reservedCash − netAmountKrw`).
- Partial fill with remaining reserve: executable, carries forward reduced reserve.
- Partial fill exhausting reserve (`remainingCash == 0`): not executable — active remainder requires a positive reserve.
- `netAmountKrw <= 0` or `remainingCash < 0`: not executable; reserve unchanged.
- Does not search for affordable quantities. The caller (engine) reduces quantity before calling this method.

#### Related model types

| Type | Role |
| --- | --- |
| [`CumulativeSettlementState`](../back/src/main/java/com/baedang/trading/model/CumulativeSettlementState.java) | 8-field record of cumulative totals (quantity, native gross, unrounded KRW gross, SEC USD, unrounded tax KRW, rounded gross/fee/tax KRW). Reconstructed from DB via `summarizeByOrderId()` |
| [`LimitOrderSettlementResult`](../back/src/main/java/com/baedang/trading/model/LimitOrderSettlementResult.java) | Wraps net amount, nullable `ExecutionAmounts`, and next `CumulativeSettlementState`. `isExecutable()` checks amounts != null; `requireExecutionAmounts()` throws on non-executable results |
| [`ExecutionAmounts`](../back/src/main/java/com/baedang/trading/model/ExecutionAmounts.java) | Per-fill settlement deltas (USD gross, unrounded KRW gross, SEC USD delta, rounded gross/fee/tax/net KRW). Shared with `TradeExecution.create()` |
| [`BuyReservationResult`](../back/src/main/java/com/baedang/trading/model/BuyReservationResult.java) | `executable`, `reservedCashAfter`, `releasedCash`. Used by the engine to update `TradeOrder.reservedCash` and `Account.lockedCash` |

## 7. Domain-Shared Components

Even when reused across use cases, these components retain domain-specific contracts. Calculators and parsers process inputs; the ledger service writes to the database, while session policies and trading-day resolvers may perform external lookups. Inject Spring beans through constructors; the calling use case manages the required transactions and locks.

| Source | Public functionality | Scope / caveat |
| --- | --- | --- |
| [LedgerService](../back/src/main/java/com/baedang/trading/service/LedgerService.java) | `recordInitialDeposit(accountId, initialCash, roundNo, occurredAt)`, `recordBuy/recordSell(order, savedExecution, balanceAfter, stock)` | MANDATORY. Only memos/signs/INSERT for initial funding and fills; caller owns settlement/balance mutation. Pass the owning order, saved execution and immediate balance. Account/stock/side come from the order; execution/order ownership is validated. Duplicate normal execution ledger entries fail at DB level and roll back the transaction. |
| [HoldingValuator](../back/src/main/java/com/baedang/account/service/HoldingValuator.java) | `valuate(holdings, quoteByStockId, usdKrwRate)` | KRW valuation for account summaries and holdings lists; reuse the existing round-per-stock-before-summing policy |
| [LedgerCursor](../back/src/main/java/com/baedang/account/support/LedgerCursor.java) | Static `encode(entryId)`, `decode(cursor)` | Ledger-only Base64URL cursor; decoding errors produce `INVALID_CURSOR`. Different format from ranking cursors |
| [StockCategory](../back/src/main/java/com/baedang/stock/entity/StockCategory.java) | Static `from(securityType, isCommonShare)` | ETF / ETN take precedence; false common-share flag yields PREFERRED, otherwise INDIVIDUAL |
| [CandleQueryPolicy](../back/src/main/java/com/baedang/stock/service/CandleQueryPolicy.java) | `parse(interval, range)`, `parseMarketCountry(value)` | Currently requests `1m/1D=200`, `1d/1M=22`, `1d/6M=130`, `1d/1Y=250`. Not a guarantee of returned count or a backfill count setting |
| [OrderPolicy](../back/src/main/java/com/baedang/trading/service/OrderPolicy.java) | `parseInput`, `parseTerms`, `determineStaticRejection`, `validateQuoteTime`, `validateExecutionContextFresh`, `hasValidCurrencyForMarket` | Shared MARKET/LIMIT input, stock, quote/context freshness and market-currency validation; preserve validation order and retry error data |
| [MarketOrderPolicy](../back/src/main/java/com/baedang/trading/service/MarketOrderPolicy.java) | `determineRejection` | Injects OrderPolicy for shared validation, then checks market-order net settlement, buying power and sellable quantity in rejection-priority order; not used for LIMIT acceptance |
| [OrderInput](../back/src/main/java/com/baedang/trading/model/OrderInput.java) | `accountId`, `clientOrderId`, `terms` | Shared parseInput result; each use case directly creates MarketOrderCommand or LimitOrderCommand |
| [OrderMarketContext](../back/src/main/java/com/baedang/trading/model/OrderMarketContext.java) | `executionRate()`, `isMarketOpenAt(now)` | Shared external market snapshot for MARKET execution and LIMIT acceptance; prepare before the transaction and revalidate after acquiring the account lock |
| [ClientOrderRetryPolicy](../back/src/main/java/com/baedang/trading/model/ClientOrderRetryPolicy.java) | `asData()` | Map containing `retryPolicy`; SAME_CLIENT_ORDER_ID / NEW_CLIENT_ORDER_ID / NOT_RETRYABLE contract |
| [QuoteRealtimePolicy](../back/src/main/java/com/baedang/stock/service/QuoteRealtimePolicy.java) | `isRealtime(country, quote)`, `isMarketOpen(country)` | Uses sessions at the current and quote times; may query the calendar, so it is not a pure calculation |
| [LatestCompletedTradingDayResolver](../back/src/main/java/com/baedang/market/service/LatestCompletedTradingDayResolver.java) | `resolve(country)` → `Optional<LocalDate>` | Resolves the latest completed trading day using local dates and the calendar; currently a 10-minute finalization delay and up to 14 days of lookback. Returns empty on lookup failure, response mismatch, or no result |

| [TickSizePolicy](../back/src/main/java/com/baedang/orderbook/service/TickSizePolicy.java) | `nextValidPriceAbove`, `previousValidPriceBelow`, `isValidPrice`, `tickSizeAt` | Price bands and tick size calculations across market/category boundaries within NUMERIC(19,4) max bound (999999999999999.9999) |
| [OrderBookGenerator](../back/src/main/java/com/baedang/orderbook/service/OrderBookGenerator.java) | `generate(policy, stock, basePrice, quoteAt, generatedAt, seed)` | Pure synthetic order-book generator using a fixed seed and properties. Applies V1 depth multipliers, integer noise, and tick-relative round-number boosts; generates 10 asks and market-specific bid depth (10 for KR, 1–10 for US) |
| [OrderBookExecutionStore](../back/src/main/java/com/baedang/orderbook/port/OrderBookExecutionStore.java) | `lockForExecution(stockId, expectedBookVersion, expectedRevision, side)` | MANDATORY. Partial execution engine (#122) pessimistically locks the active version and the actual levels for the requested side in the same transaction: 10 asks, 10 KR bids, or 1–10 US bids. A partial US depth must end at `$0.01`. BUY→ASK, SELL→BID |
| [OrderBookProperties](../back/src/main/java/com/baedang/orderbook/config/OrderBookProperties.java) | `enabled()`, `policyVersion()`, `krBaseNotional()`, `minQuantity()`, etc. | `trading.orderbook` validated runtime properties record. V1 defaults: enabled=false, 3s refresh, 15s maxQuoteAge, 1m retention |

| [StockFinancialInfoPort](../back/src/main/java/com/baedang/stock/port/StockFinancialInfoPort.java), [KisStockFinancialInfoAdapter](../back/src/main/java/com/baedang/stock/client/kis/KisStockFinancialInfoAdapter.java) | Domain port and KIS adapter for industry and financial statement periods | Port returns pure domain records (`IndustryData`, `PeriodData`). Adapter bean is conditional on `kis.enabled=true` |
| [StockFinancialSyncService](../back/src/main/java/com/baedang/stock/service/StockFinancialSyncService.java) | `ensureFresh(stock, trigger)`, `refresh(stock, trigger)`, `refreshRankedTargets(trigger)` | Orchestrates TTL evaluation (financial 7d / 7 days, industry 30d / 30 days), per-stock `CompletableFuture` single-flight, and weekly batch execution. Injects `Optional<StockFinancialInfoPort>` to remain bootable when disabled |
| [StockFinancialQueryService](../back/src/main/java/com/baedang/stock/service/StockFinancialQueryService.java) | `getFinancials(symbol, marketCountry)` | Cache-first financial query service. Validates supported KR non-ETF/ETN stocks, computes operating profit margin at query time, and manages FRESH/STALE resolution with graceful fallback |
The current implementation uses these configurable V1 virtual-order-book defaults: `enabled=false`, `policyVersion=V1`, `refreshInterval=3s`, `refreshInitialDelay=0s`, `maxQuoteAge=15s`, `krBaseNotional=20000000`, `usBaseNotional=15000`, `minQuantity=1`, `maxQuantity=1000000`, `noiseMinBps=8000`, `noiseMaxBps=12000`, `closedVersionRetention=1m`, and `retentionInitialDelay=0s`. V1 book shape is a code invariant, not runtime configuration: 10 levels per side with one valid tick between neighboring levels (US bids may stop at `$0.01`). Closed versions and levels are deleted after retention regardless of consumption; execution price, quantity, and settlement amounts remain permanent in `trade_execution`. A different shape requires a new policy version. These synthetic supply values require rationale and agreement in the #121 PR; implementation alone proves neither policy approval nor empirical market-depth fidelity. The two initial delays control scheduler startup timing only and must be nonnegative.

`trading.orderbook.enabled` controls only virtual order-book publication and query. LIMIT admission, cancellation, expiration and execution are separate use cases, not toggled together with the book flag. Admission and the #122 execution worker are always enabled. Without a usable book, the worker defers orders rather than fabricating liquidity.

For calendar-dependent logic, inject the existing [MarketCalendarPort](../back/src/main/java/com/baedang/market/port/MarketCalendarPort.java) and [MarketSessionProvider](../back/src/main/java/com/baedang/market/port/MarketSessionProvider.java). Do not duplicate external calls or caches.

Striped locks are not yet a shared helper. Keep the separate implementations in `CandleQueryService` and `StockOnDemandQuoteService`; do not share one global lock.

### DecimalScaleValidator — Trading Input Scale Validation

Call `com.baedang.trading.support.DecimalScaleValidator.isRepresentableAtScale(value, scale)` statically. Null returns false; trailing zeros are ignored when checking lossless representability at the requested scale. The original value/scale is unchanged. This does not validate total NUMERIC precision, positivity or currency settlement calculations. Combine it with existing order/execution/settlement input conditions; callers choose the exception.

### Virtual Order Book & Locking Contracts

The virtual order book module (`com.baedang.orderbook`) supplies a shared synthetic 10-bid/10-ask order book based on Toss current price and provides the locking store contract for the #122 matching engine.

#### 1. Lock Ordering Rules (Deadlock Prevention)
- **Publisher (Order Book Publication/Close)**: `stock → order_book_version` (never acquires account, trade_order, or holding locks)
- **Consumer (#122 Limit Execution Engine)**: `account → trade_order → order_book_version → order_book_level (in fill-price order) → holding` (never acquires stock lock)
- The lock hierarchies are strictly separated to prevent mutual blocking between publishers and consumers.

#### 2. Matching Engine (#122) Integration Contracts
- **Port Invocation**: `OrderBookExecutionStore.lockForExecution(...)` requires `Propagation.MANDATORY` and must be invoked within the existing transaction where #122 already acquired `account → trade_order` locks.
- **Side Mapping**: Order BUY consumes synthetic ASK liquidity, and order SELL consumes synthetic BID liquidity (`BUY → ASK`, `SELL → BID`).
- **Level Order**: Locks are acquired via `FOR UPDATE` in price order: ASK uses `price ASC, levelDepth ASC`, and BID uses `price DESC, levelDepth ASC`. The store rejects a locked side whose depths are non-sequential or whose prices are not strictly ascending (ASK) or descending (BID).
- **Mismatch Handling**: If the expected `bookVersion` or `revision` does not match, or if the version is already closed, it returns `Optional.empty()`. Handling this result via retry or holding is #122's responsibility; do not blanket-map it to HTTP `SAME_CLIENT_ORDER_ID`.
- **State Transition Primitive**: Even when consuming multiple levels in a single transaction, call `OrderBookVersion.advanceRevision()` exactly once per transaction.

#### 3. Quantity and Precision Policies
- **Integer Quantity Policy**: Although the database maintains `NUMERIC(19,6)` for future fractional compatibility, V1 synthetic order book generation and fill consumption operate strictly in **whole integer shares** (`minQuantity=1`, `maxQuantity=1000000`).
- **Nature of Quantity Distribution**: Depth multipliers and round-number boosts represent mock market V1 supply policies rather than an empirical replication of real market depth. Never treat upper-level volume dominance as an invariant.


### KIS Financial Data Integration Contracts

#### 1. Rate Limiting and Credential Protection
- **Shared Gate Location**: The rate limiter uses `com.baedang.global.clients.FixedIntervalGate` shared between brokers, but `TossRateLimiterRegistry` and `KisRateLimiter` maintain strictly separate gate instances.
- **Rate Limit Policy**: Configured via `kis.requests-per-second` (1..18). In production, 3 TPS before 2026-09-10 KST / 18 TPS from 2026-09-10 KST is used. Token issuance uses single-flight deduplication and a 65-second cooldown on failures.
- **Credential Protection**: KIS `app-key`, `app-secret`, and access tokens are injected only through environment variables/secrets. They are never written to source, fixtures, logs, or exception messages. `.env.example` provides variable names with empty defaults.

#### 2. Architecture and Port-Adapter Boundaries
- **Domain Port Isolation**: `StockFinancialSyncService` depends on `StockFinancialInfoPort`; `StockFinancialQueryService` depends on repositories and delegates cache refresh to `StockFinancialSyncService`. Neither service directly references KIS clients or adapters.
- **Optional Port Injection**: `StockFinancialSyncService` injects `Optional<StockFinancialInfoPort>` so that the service remains bootable and operational from local cache when `kis.enabled=false`.
- **Transaction Boundaries**: External KIS HTTP calls MUST execute outside active database transactions. Short transactions are opened only by `StockFinancialPersistenceService` for persisting parsed periods and updating sync timestamps.

#### 3. TTL and Scheduling Policies
- **TTL Rules**: Financial statements (annual and quarterly) have a 7-day (7d / 7 days) TTL. Industry classification has a 30-day (30d / 30 days) TTL.
- **Single-Flight Concurrency**: Concurrent requests for the same stock share a `CompletableFuture` through a `ConcurrentHashMap` owner/waiter pattern. After completing its future, the owner compare-removes only its own map entry in `finally`; a force-refresh waiter that joined a non-forced flight rechecks the outcome and starts a forced flight if the financial groups were not updated.
- **Weekly Batch Budget**: Scheduled at Monday 08:10 KST for ranked KR non-ETF/ETN stocks (~100 stocks). Calls 4 annual + 4 quarterly endpoints per stock; industry is queried only if missing or expired (max 800 / 900 calls).
- **Single-Replica Limitation**: In-memory single-flight and rate limiting apply within a single JVM instance. Scaling to multiple replicas requires distributed locks, shared token caches, and central rate limiting before deployment.

#### 4. Fallback and Negative Caching
- **Negative Cache**: A normal empty response from KIS persists as an empty record with updated sync timestamps, preventing repeated requests throughout the TTL window.
- **STALE Fallback**: If an external refresh fails but previously cached data exists, the query service returns existing data with `dataStatus="STALE"`. If no prior cache exists, the original failure (`KIS_RATE_LIMITED` prioritized, else `KIS_API_ERROR`) is propagated to the client.
- **Group Isolation**: Industry, annual, and quarterly refresh groups are attempted independently. An internal persistence failure is deferred until the remaining groups run; the first failure is then rethrown with any later failures attached as suppressed exceptions.

## 8. Frontend Shared Modules

### Amount Calculation and Display

| Module | Export | Purpose / caveat |
| --- | --- | --- |
| [decimal.ts](../front/src/lib/decimal.ts) | `D` | Decimal class configured with `ROUND_HALF_UP`; import this module for monetary calculations |
| [format.ts](../front/src/lib/format.ts) | `toDecimal(value)` | Convert string, number, or Decimal inputs to Decimal; null, empty, non-finite, and other invalid values return null |
| [format.ts](../front/src/lib/format.ts) | `toKrw(nativeValue, currency, exchangeRate)` | For USD, return Decimal multiplied by the rate; otherwise return the original value. No whole-won rounding at this stage |
| [format.ts](../front/src/lib/format.ts) | `formatNumber`, `formatSigned`, `formatKoreanAmount` | Integer, signed, and Korean 억/조 unit display |
| [format.ts](../front/src/lib/format.ts) | `formatPercent(rate)` | Multiply a ratio by 100 and display a percentage with two decimal places |
| [format.ts](../front/src/lib/format.ts) | `formatAbsolute(value, decimalPlaces)`, `formatUsd(value)` | Absolute value at the requested precision / two-decimal display with a dollar sign |
| [order-amount.ts](../front/src/lib/order-amount.ts) | `calculateOrderAmount(params)` | Decimal-based order preview; the backend determines the final execution amount |

String display functions in `format.ts` return the default `"-"` or the supplied fallback for invalid input.
`toKrw()` returns null for USD when the rate is missing or non-positive. It currently checks USD case-sensitively and passes other currencies through, so do not use it to validate or normalize supported currencies.

```ts
import { D } from "@/lib/decimal";
import { formatNumber, formatPercent, toKrw } from "@/lib/format";

const nativePrice = new D("88.33");
const won = toKrw(nativePrice, "USD", "1383.6");
const displayPrice = formatNumber(won); // Whole-won HALF_UP at the display boundary
const displayRate = formatPercent("0.125"); // "+12.50%"
```

Where possible, pass numeric API strings directly to Decimal. Some current boundaries, including order-preview results and the exchange-rate fetch helper, use `number`; do not assume arbitrary precision throughout the entire path. Never use display or preview values as the basis for ledger entries or confirmed execution amounts.

### API, Chart, and UI Support

API and exchange-rate lookup modules are HTTP clients, not pure helpers. Reuse them separately from chart/display transformations. CSS color conversion depends on the browser DOM/Canvas.

| Module | Export | Purpose / caveat |
| --- | --- | --- |
| [api.ts](../front/src/lib/api.ts) | `ApiError`, endpoint functions such as `getRankings`, `getCandles`, `placeOrder` | Reuse API calls and code/message/data handling; internal `request()` is private |
| [order-retry-policy.ts](../front/src/lib/order-retry-policy.ts) | `generateClientOrderId()`, `nextClientOrderId(policy, currentId)` | SAME or no policy: retain ID; NEW: generate ID; NOT_RETRYABLE: null. An idempotency key, not an authentication nonce |
| [exchange-rate.ts](../front/src/lib/exchange-rate.ts) | `fetchExchangeRate()` | Display-rate lookup with default fallback on failure; not a source for execution exchange rates |
| [candle-chart-data.ts](../front/src/lib/candle-chart-data.ts) | `toCandlestickData`, `toVolumeData` | Convert to numeric chart data, sort by time, and deduplicate; not for settlement calculations |
| [exchange-rate-chart-data.ts](../front/src/lib/exchange-rate-chart-data.ts) | `toLinePoints`, `isTimeVisible`, `formatTickMark` | Downsample and format exchange-rate chart time axes; does not change stored source data |
| [chart-colors.ts](../front/src/lib/chart-colors.ts) | `resolveCssColor(name, fallback)` | Convert CSS variables to chart colors; returns fallback when no DOM is available |
| [category-badge.ts](../front/src/lib/category-badge.ts) | `categoryLabel`, `CATEGORY_BADGE_STYLE` | Map dividend status and stock types to UI badges; does not change backend stock classification |

## 9. Adoption and Change Checklist

- Before consolidating similar code, check calculation precision, null handling, error codes, and time-zone policies.
- Keep validation, normalization, calculation, and display separate. Do not calculate through a Formatter or substitute a Normalizer for validation.
- Reuse helpers without arbitrarily merging service-specific exceptions, locks, or transaction boundaries.
- Preserve existing tests first. If a refactoring breaks a test, first check whether it changed a service-specific policy.
- Add boundary tests for new public methods, and update method names, examples, and caveats in both language versions of this guide.
- Reference tests: [normalization](../back/src/test/java/com/baedang/global/normalizer/DomainNormalizerTest.java), [service contracts](../back/src/test/java/com/baedang/global/normalizer/DomainNormalizationContractTest.java), [market information](../back/src/test/java/com/baedang/stock/entity/MarketCountryTest.java), [return ratios](../back/src/test/java/com/baedang/account/support/ReturnRateCalculatorTest.java), [frontend helpers](../front/src/lib/__tests__).

## LIMIT lifecycle components (#120)

- LimitOrderRequestPolicy: supported input currency and lossless input-price validation. Not a rounding helper.
- LimitOrderPricing: inject; converts US KRW limits to fixed USD cents and computes original-input reserve through LimitOrderSettlementCalculator. One-fill indicative amounts reuse MarketOrderSettlementCalculator; never use these to settle partial fills.
- LimitOrderService: NEVER entry point for acceptance, quote and cancellation. Performs external preparation before DB mutation. The disabled acceptance flag does not block existing-order replay or cancellation.
- LimitOrderTransactionService: REQUIRED account-first acceptance/closure; no external calls or ledger inserts. Returns closure results so an expiration conflict can be converted to an HTTP error after commit.
- OrderReadService: read-only ownership checks, current-round order cursor and per-order execution cursor; executed balances come from linked ledger entries.
- LimitOrderExpirationService: NEVER scan, no external market calls, independent per-order closure transactions with 2-second transaction-local lock timeout and retry on failure. LimitOrderExpirationScheduler runs this service on the dedicated single-thread limitOrderTaskScheduler, including asynchronous startup recovery and a 30-second fixed delay.
- Account.reserveCash/releaseCash and Holding.reserveQuantity/releaseQuantity: mutate reservation only; caller must hold the account lock and, for holdings, the holding lock. Holding methods take explicit UTC change time.

## Current-price collection (#140)

During regular sessions, QuoteSnapshotLoadService walks only ACTIVE stocks that are ranked or have an unexpired LIMIT order (PENDING/PARTIALLY_FILLED, quantity > filledQuantity), in 200-row stockId keyset pages with a 5-second target. Membership is derived from orders across all users, not a separate flag. After the last active order ends, a non-ranked stock leaves scheduled collection on the next query; one already-submitted fetch may finish. Ranked stocks remain. No full-universe sweep is performed. Order eligibility and candle/backfill policies remain separate.

QuoteRefreshCoordinator merges overlapping in-flight stock requests across scheduled and on-demand price reads. Background work has three threads, no executor queue, and an 8 requests/second submission budget; one separate caller-side slot remains for urgent reads. The existing Toss client still enforces the final shared MARKET_DATA 15 TPS, including retries and other callers. Queued/waiting stock identities are bounded (default 1,000) and removed on completion/failure. A background rejection does not advance the page cursor. The dispatcher uses its own scheduler (default 25ms fixed delay); actual cycle time also includes work and capacity waits.

Inject QuoteRefreshCoordinator and call requireFresh(stock, maxAge) **outside a transaction** for trading-oriented reads. It reuses a recent source quoteAt or refreshes and rejects missing/future/stale results. It neither opens a trade nor fetches candles, sessions, FX or tradability metadata. OrderMarketDataService connects it to orders; order orchestration validates the other contracts. refresh(stock) is the display-oriented refresh operation; its caller may explicitly retain an older display value on failure. Existing daily-backfill policy is unchanged.

QuoteSnapshotPersistenceService validates positive/representable prices, currency and non-future source time, then uses atomic JDBC UPSERT. Older quoteAt responses (or older collection times at equal quoteAt) cannot replace newer data. Price updates preserve prev_close and price limits; updatePrevClose updates only that column. No new table or migration is introduced.

Configuration: trading.quote-collection.refresh-interval=5s, dispatch-interval=25ms, background-concurrency=3, background-requests-per-second=8, max-in-flight-stocks=1000, request-timeout=20s. ExternalHttpConfig applies configurable toss.connect-timeout=2s/read-timeout=5s to auto-configured RestClient builders so hung I/O cannot permanently occupy a slot. The coordinator wait timeout is not a deadline covering all queued and HTTP work.

Metrics: quote.collection.batch, quote.collection.sweep.submission (submission sweep, not all HTTP completions), quote.collection.source.age (observed response ages), quote.collection.inflight, quote.collection.requested, quote.collection.updated, quote.collection.failures; use existing toss.ratelimiter metrics for shared group usage. Ages remain source ages even after successful retrieval. /prices only supplies symbol/timestamp/lastPrice/currency; it does not refresh Stock suspension/liquidation flags. StockTradingStatusService refreshes those metadata separately.

### Trading market-data preparation and status cache

- Inject `OrderMarketDataService` into order/estimate orchestration. `refreshStatus` verifies status; `requireQuote` delegates to `QuoteRefreshCoordinator.requireFresh`. `prepareEstimate` preserves time-related non-executable quote reasons. No candle backfill is triggered.
- `StockTradingStatusService` is shared by order preparation and the book scheduler. Use `requireCurrent` for one stock or `refreshBatch` for up to 200. SymbolInfoPort results share a bounded 1,000-stock, 5-minute in-memory cache; refresh-lock waiting is limited to 5 seconds. Failed requests are not cached as success. TTL does not guarantee real-time exchange status. Configure `trading.stock-status-cache-ttl`.
- `StockTradingStatusPersistenceService` updates status columns in a separate database transaction, preserving rankings, names and warnings. KR updates listing/suspension/liquidation; US updates listing status.
- External preparation uses `NEVER`: never invoke it inside financial transactions. Existing Port/Adapter and global RateLimiter paths are reused.
- `StockRepository.findQuoteTargets/isQuoteTarget` selects ACTIVE ∩ (ranked ∪ unexpired LIMIT PENDING/PARTIALLY_FILLED with remaining quantity > 0), across all users. Publication rechecks membership. The execution-worker migration adds a partial stock/expiry index for this predicate.

### Limit execution components (#122)

- `LimitOrderExecutionPlanner.plan(...)`: pure level selection shared by execution and preview. Uses `LimitOrderSettlementCalculator` for cumulative deltas and binary-searches affordable whole-share BUY quantities. Never inject a repository/HTTP client here.
- `LimitOrderExecutionService.prepare(stockId, side)` / `execute(orderId, selectedPreparation)`: NEVER boundaries. Prepare before selecting candidates, then pass the selection's version/FX into execution. Execution refreshes its context and returns PRIORITY_CHANGED if that basis changed. Same-version revision/financial-lock conflicts get at most one retry; never retry a lower-priority candidate on a new version/rate.
- `LimitExecutionProgress`: single-instance stock/side price cursors keyed by bookVersion/numeric FX, not revision. `LimitOrderTransactionService` publishes `LimitOrderAcceptedEvent`; the AFTER_COMMIT listener coalesces pending arrivals to the earliest-priority candidate without invalidating an in-flight selection. After `advance`, the next `position` applies pending arrivals against the updated cursor and changes the page token, restarting at the head only for an earlier arrival. The worker continues within its visit budget without waiting for admissions to stop. Prune cursors and pending notifications after full rotation; do not treat this as a multi-instance lock or persistent order state.
- `LimitOrderExecutionTransactionService.execute(attempt)`: account-first REQUIRED boundary, consumes shared book levels and records all financial effects atomically. Do not invoke directly from a surrounding transaction; use the NEVER orchestrator. `LimitExecutionAttempt` carries expected execution count and version/revision, not user prices or mutable balance assumptions.
- `LimitOrderPreviewService`: reuses the planner, with no writes or resource reservation. `LimitExecutionPreviewResponse.avgExecutionPrice` is display-only, KRW 0 / USD 2 decimals; monetary totals must not be derived from it.
- `Account.settleReservedBuy`, `Holding.settleReservedSell`: consume already reserved resources, not buying power/sellable free quantity. Caller enforces the individual order's reserve and shares the financial transaction. Unused full-fill BUY reserve is explicitly released.
- `LimitOrderExecutionWorker`: always scheduled on `limitExecutionTaskScheduler`, not the publication/expiration scheduler. Budget/cursor behavior and preview fields are documented in `api-spec.md`.
