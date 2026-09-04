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
| Calculate order gross amounts, fees, and taxes | `OrderAmountCalculator` | Inject a Spring bean |
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
| [SchedulingConfig](../back/src/main/java/com/baedang/global/config/SchedulingConfig.java) | Enables scheduling and provides `dailyCandleTaskExecutor`. Inject that executor with `@Qualifier("dailyCandleTaskExecutor")` | Dedicated to daily candles: one thread, queue capacity 10, up to 30 seconds for shutdown. Do not indiscriminately share it with other async tasks; each scheduler owns its activation conditions |
| [CorsConfig](../back/src/main/java/com/baedang/global/config/CorsConfig.java) | Automatically applies to `/api/**`; configure origins via `cors.allowed-origins` / `CORS_ALLOWED_ORIGINS` | No direct invocation needed. CORS does not replace authentication or authorization |

### Error Handling and External Communication

| Source | Role / usage | Caveat |
| --- | --- | --- |
| [BusinessException](../back/src/main/java/com/baedang/global/error/BusinessException.java), [ErrorCode](../back/src/main/java/com/baedang/global/error/ErrorCode.java) | Signal business errors with `throw new BusinessException(ErrorCode.…)`; optionally supply detail or data | Detail is for developer diagnostics; data is for client decisions. Preserve existing error-code and retry contracts |
| [GlobalExceptionHandler](../back/src/main/java/com/baedang/global/error/GlobalExceptionHandler.java), [ErrorResponse](../back/src/main/java/com/baedang/global/error/ErrorResponse.java) | Automatically converts exceptions to HTTP error responses | Do not duplicate the same try/catch and response construction in each controller |
| [TossSecuritiesClient](../back/src/main/java/com/baedang/global/clients/toss/TossSecuritiesClient.java) | Inject into Toss adapters and call `get(path, queryParams, responseType)` | Business services use existing Ports. Do not bypass allowed-path validation or the global RateLimiter; never call real-money order APIs |
| [TossRateLimiterRegistry](../back/src/main/java/com/baedang/global/clients/toss/TossRateLimiterRegistry.java), [TossApiGroup](../back/src/main/java/com/baedang/global/clients/toss/TossApiGroup.java), [Whitelist](../back/src/main/java/com/baedang/global/clients/toss/Whitelist.java) | Shared per-group request limits and path mapping; the registry exposes `acquire(group)` and `tryAcquire(group)` | The Toss client already applies limits to ordinary calls, so do not acquire another permit for the same request in an upstream service. Limits are shared within an application instance |

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

### OrderAmountCalculator — Order Calculations

Source: [OrderAmountCalculator.java](../back/src/main/java/com/baedang/trading/service/OrderAmountCalculator.java)

`calculate(marketCountry, side, executedPrice, quantity, exchangeRate)` → `OrderAmount`.
Inject this Spring bean so it uses the configured fee and tax rates. The order policy validates input and tradability.

Rates and the SEC minimum are project-fixed `.env` settings, not per-order snapshots. Keep them unchanged across restarts/deployments while orders are active.

- KR orders do not use the supplied exchange rate in the calculation; the result's exchange rate is 1.
- US orders round the unit price to cents with `HALF_UP`, multiply by quantity, apply the exchange rate, then round to whole won with `HALF_UP`.
- The trading fee is calculated from the gross amount already rounded to whole won, then rounded to whole won itself.
- For US sells, round `max(USD gross amount × configured rate, configured minimum)` to cents, apply the exchange rate, then round to whole won for the SEC Fee.
- Buy net amount is gross amount + fee. Sell net amount is gross amount − fee − tax.
- The result also contains the KRW gross amount before rounding. Do not confuse cost-basis calculation values with settlement amounts.
- The private `krw()` / `usd()` methods in this class return BigDecimal for calculations. Do not replace them with the identically named string Formatter methods.

## 7. Domain-Shared Components

Even when reused across use cases, these components retain domain-specific contracts. Calculators and parsers process inputs; the ledger service writes to the database, while session policies and trading-day resolvers may perform external lookups. Inject Spring beans through constructors; the calling use case manages the required transactions and locks.

| Source | Public functionality | Scope / caveat |
| --- | --- | --- |
| [LedgerService](../back/src/main/java/com/baedang/trading/service/LedgerService.java) | `recordInitialDeposit(accountId, initialCash, roundNo, occurredAt)`, `recordBuy/recordSell(order, savedExecution, balanceAfter, stock)` | MANDATORY. Only memos/signs/INSERT for initial funding and fills; caller owns settlement/balance mutation. Pass the owning order, saved execution and immediate balance. Account/stock/side come from the order; execution/order ownership is validated. Duplicate normal execution ledger entries fail at DB level and roll back the transaction. |
| [HoldingValuator](../back/src/main/java/com/baedang/account/service/HoldingValuator.java) | `valuate(holdings, quoteByStockId, usdKrwRate)` | KRW valuation for account summaries and holdings lists; reuse the existing round-per-stock-before-summing policy |
| [LedgerCursor](../back/src/main/java/com/baedang/account/support/LedgerCursor.java) | Static `encode(entryId)`, `decode(cursor)` | Ledger-only Base64URL cursor; decoding errors produce `INVALID_CURSOR`. Different format from ranking cursors |
| [StockCategory](../back/src/main/java/com/baedang/stock/entity/StockCategory.java) | Static `from(securityType, isCommonShare)` | ETF / ETN take precedence; false common-share flag yields PREFERRED, otherwise INDIVIDUAL |
| [CandleQueryPolicy](../back/src/main/java/com/baedang/stock/service/CandleQueryPolicy.java) | `parse(interval, range)`, `parseMarketCountry(value)` | Currently requests `1m/1D=200`, `1d/1M=22`, `1d/6M=130`, `1d/1Y=250`. Not a guarantee of returned count or a backfill count setting |
| [MarketOrderPolicy](../back/src/main/java/com/baedang/trading/service/MarketOrderPolicy.java) | `parseCommand`, `parseTerms`, `determineRejection`, `determineStaticRejection`, `validateExecutionContextFresh`, `hasValidCurrencyForMarket` | Order input, tradability, quote/context freshness, and market-currency validation; preserve validation order and retry error data |
| [ClientOrderRetryPolicy](../back/src/main/java/com/baedang/trading/model/ClientOrderRetryPolicy.java) | `asData()` | Map containing `retryPolicy`; SAME_CLIENT_ORDER_ID / NEW_CLIENT_ORDER_ID / NOT_RETRYABLE contract |
| [QuoteRealtimePolicy](../back/src/main/java/com/baedang/stock/service/QuoteRealtimePolicy.java) | `isRealtime(country, quote)`, `isMarketOpen(country)` | Uses sessions at the current and quote times; may query the calendar, so it is not a pure calculation |
| [LatestCompletedTradingDayResolver](../back/src/main/java/com/baedang/market/service/LatestCompletedTradingDayResolver.java) | `resolve(country)` → `Optional<LocalDate>` | Resolves the latest completed trading day using local dates and the calendar; currently a 10-minute finalization delay and up to 14 days of lookback. Returns empty on lookup failure, response mismatch, or no result |

For calendar-dependent logic, inject the existing [MarketCalendarPort](../back/src/main/java/com/baedang/market/port/MarketCalendarPort.java) and [MarketSessionProvider](../back/src/main/java/com/baedang/market/port/MarketSessionProvider.java). Do not duplicate external calls or caches.

Striped locks are not yet a shared helper. Keep the separate implementations in `CandleQueryService` and `StockOnDemandQuoteService`; do not share one global lock.

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
