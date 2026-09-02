# 공용 헬퍼 및 도메인 지원 기능 사용 가이드

현재 소스 코드에서 재사용할 수 있는 헬퍼의 색인입니다. 새 기능을 작성하기 전에 같은 역할의 구현이 있는지 확인하세요.
전역 정적 유틸리티와 도메인 전용 계산기·정책 빈을 구분하며, 개별 서비스의 `private` 메서드나 엔티티 getter·팩토리를 모두 나열하지는 않습니다.

이 문서는 기존 동작을 설명합니다. API 계약은 [API 명세](api-spec.ko.md), 저장 정책은 [ERD](erd.ko.md)를 함께 확인하고, 헬퍼의 공개 메서드나 정책을 바꿀 때 이 문서도 갱신하세요.

## 1. 빠른 선택

| 필요한 작업 | 사용할 기능 | 호출 방식 |
| --- | --- | --- |
| 심볼·통화·이메일·검색어 정규화 | `DomainNormalizer` | 정적 메서드 |
| BigDecimal을 API 응답 문자열로 변환 | `FinancialDecimalFormatter` | 정적 메서드 |
| 국가 코드 파싱·시장별 시간대·기본 통화 | `MarketCountry` | 정적 메서드 / enum 메서드 |
| 계좌·보유 종목 손익률 계산 | `ReturnRateCalculator` | 정적 메서드 |
| 주문 거래대금·수수료·세금 계산 | `OrderAmountCalculator` | Spring 빈 주입 |
| 보유 종목 평가 | `HoldingValuator` | Spring 빈 주입 |
| 현재 시각·시장 현지 날짜 계산 | `TimeConfig`의 `Clock` | Clock 주입 |
| 차트 조합·거래일·실시간 시세 판정 | 해당 도메인의 Policy / Resolver | Spring 빈 주입 |
| 프론트 금액 계산·표시 | `D`, `format.ts`, `order-amount.ts` | 모듈 import |

## 2. 문자열 정규화 — DomainNormalizer

소스: [DomainNormalizer.java](../back/src/main/java/com/baedang/global/normalizer/DomainNormalizer.java)

패키지: `com.baedang.global.normalizer`

| 메서드 | 변환 | 예시 |
| --- | --- | --- |
| `symbol(value)` | 앞뒤 공백 제거 + 대문자 | `" intc "` → `"INTC"` |
| `currency(value)` | 앞뒤 공백 제거 + 대문자 | `" usd "` → `"USD"` |
| `email(value)` | 앞뒤 공백 제거 + 소문자 | `" User@Example.COM "` → `"user@example.com"` |
| `searchKey(value)` | 정규식 `\s+`에 해당하는 내부·외부 공백 제거 + 소문자 | `" 삼 성 Elec "` → `"삼성elec"` |
| `upperCode(value)` | 일반 코드의 앞뒤 공백 제거 + 대문자 | `" buy "` → `"BUY"` |
| `lowerCode(value)` | 일반 코드의 앞뒤 공백 제거 + 소문자 | `" 1D "` → `"1d"` |

- 대소문자 변환은 모두 `Locale.ROOT`를 사용합니다. 서버 기본 로케일에 의존하지 않습니다.
- 모든 메서드는 `null`을 그대로 반환합니다. 공백만 있는 입력은 변환 결과가 빈 문자열일 수 있습니다.
- 정규화는 검증이 아닙니다. `currency("xxx")`는 `"XXX"`이며 지원 통화인지 판단하지 않습니다.
- 필수값 검사와 `BusinessException`의 오류 코드·상세·구조화 데이터는 호출부에 남깁니다. 예를 들어 주문과 차트의 심볼 누락 오류는 같다고 가정하면 안 됩니다.
- 검색 서비스는 `null`인 종목 필드를 빈 문자열로 취급하는 별도 정책이 있습니다. 이를 모든 헬퍼의 `null` 정책으로 확장하지 않습니다.
- `symbol()`은 내부 공백을 제거하지 않습니다. 심볼에 `searchKey()`를 대신 사용하지 마세요.
- 비밀번호·토큰·불투명 커서·UUID·수량 문자열에는 이 변환을 일괄 적용하지 않습니다.

```java
import com.baedang.global.normalizer.DomainNormalizer;

String symbol = DomainNormalizer.symbol(rawSymbol);
String currency = DomainNormalizer.currency(rawCurrency);
// 필요한 필수값·허용값 검증은 해당 서비스의 기존 규칙에 맞게 수행합니다.
```

## 3. 응답 숫자 포맷 — FinancialDecimalFormatter

소스: [FinancialDecimalFormatter.java](../back/src/main/java/com/baedang/global/formatter/FinancialDecimalFormatter.java)

패키지: `com.baedang.global.formatter`. 입력은 `BigDecimal`, 결과는 `String`입니다.
계산·저장용 숫자를 만드는 기능이 아니라, 계산이 끝난 값을 응답 또는 설명 문자열로 바꾸는 기능입니다.

| 메서드 | 정책 | 예시 |
| --- | --- | --- |
| `plain(value)` | 반올림 없이 후행 0 제거, 지수 표기 없음 | `1.2300` → `"1.23"`, `0.0000` → `"0"` |
| `rate(value)` | 환율용. `plain()`과 동일, 반올림 없음 | `1383.600000` → `"1383.6"` |
| `averagePrice(value)` | 평단가용. `plain()`과 동일, 소수 정밀도 유지 | `71166.6667` → `"71166.6667"` |
| `usd(value)` | 소수점 2자리 `HALF_UP`, 두 자리 고정 | `88.335` → `"88.34"`, `90` → `"90.00"` |
| `krw(value)` | 소수점 0자리 `HALF_UP` | `122199.552` → `"122200"` |
| `currency(value, currency)` | 통화 코드를 정규화한 뒤 KRW / USD 정책 선택 | `(90, " usd ")` → `"90.00"` |

- 모든 메서드는 값이 `null`이면 `null`을 반환합니다. 필수 응답 필드의 non-null 보장은 DTO 매핑 전 단계에서 해야 합니다.
- `currency()`는 값이 non-null일 때 통화가 null·공백·미지원이면 `IllegalArgumentException`을 던집니다. 값이 null이면 통화 검사 전에 null을 반환합니다.
- `rate()`는 환율을 6자리로 반올림하거나 고정하지 않습니다. 저장된 평균환율을 계산하는 정책과 문자열 표현 정책은 별개입니다.
- `avgBuyPrice`는 KR 종목이라도 `krw()` / `currency()`가 아닌 `averagePrice()`로 표현합니다. 평균 단가의 소수 부분을 미리 없애면 수량을 곱할 때 원가가 달라집니다.
- 포맷한 문자열을 다시 BigDecimal로 읽어서 후속 계산에 사용하지 마세요.

```java
import static com.baedang.global.formatter.FinancialDecimalFormatter.*;

String quantityText = plain(quantity);
String rateText = rate(exchangeRate);
String averagePriceText = averagePrice(avgBuyPrice);
String cashText = krw(cashBalance);
String priceText = currency(lastPrice, stock.getCurrency());
```

## 4. 시장 정보 — MarketCountry

소스: [MarketCountry.java](../back/src/main/java/com/baedang/stock/entity/MarketCountry.java)

| 메서드 | 반환·용도 | 주의점 |
| --- | --- | --- |
| `parse(raw)` | `Optional<MarketCountry>`. `" kr "` → KR | null·공백·미지원 코드는 empty. 오류 응답은 호출부에서 결정 |
| `zoneId()` | KR: `Asia/Seoul`, US: `America/New_York` | 현지 날짜 계산용. 미국 DST 반영 |
| `defaultCurrency()` | KR: `"KRW"`, US: `"USD"` | 실제 종목·시세 통화가 맞는지 검증하는 역할은 아님 |
| `fromMarket(market)` | 거래소 코드 → 국가 | 국가 코드 파싱과 별개. 대문자 거래소 코드를 전달 |
| `marketsNameMap()` | 거래소→국가 매핑 조회 | 수정 불가능한 Map |

`fromMarket()`의 매핑은 KOSPI·KOSDAQ·KR_ETC → KR, NYSE·NASDAQ·AMEX·US_ETC → US입니다.
미지원 non-null 거래소 코드는 `TOSS_API_ERROR`로 처리합니다. 현재 `Map.of` 조회를 사용하므로 null을 받는 안전한 파서가 아닙니다. null 가능 입력은 호출부에서 먼저 검증하세요.

```java
MarketCountry country = MarketCountry.parse(rawCountry)
        .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_INPUT));
// 위 예시는 기본 오류만 사용합니다. 주문 등 기존의 field/retryPolicy 계약은 그대로 유지하세요.
```

### 현재 시각과 KST 고정 정책

[TimeConfig.java](../back/src/main/java/com/baedang/global/config/TimeConfig.java)의 UTC `Clock`을 주입받습니다.

```java
Instant now = clock.instant();
OffsetDateTime utcNow = now.atOffset(ZoneOffset.UTC);
LocalDate marketDate = now.atZone(country.zoneId()).toLocalDate();
```

- 새 시간 의존 로직은 `clock.instant()`에서 시작합니다. 테스트는 `Clock.fixed(...)`를 주입합니다.
- `zoneId()`를 일봉 KST 저장·차트 KST 표현·환율 KST 기준일·KST 기반 캐시 날짜에 무조건 적용하지 않습니다. 현지 날짜와 고정 KST 날짜는 서로 다른 정책입니다.
- 시간대만으로 개장·폐장·휴장 여부를 추정하지 않습니다. 장 운영 정보는 기존 캘린더 / 세션 Port를 사용합니다.

## 5. 수익률과 주문 금액 계산

### ReturnRateCalculator — 계좌 손익률

소스: [ReturnRateCalculator.java](../back/src/main/java/com/baedang/account/support/ReturnRateCalculator.java)

패키지: `com.baedang.account.support`

```java
BigDecimal pnlRate = ReturnRateCalculator.calculate(unrealizedPnl, costBasis);
String pnlRateText = FinancialDecimalFormatter.plain(pnlRate);
```

- 인자 순서는 **손익, 취득원가**입니다. 두 입력은 계산 완료된 non-null 금액을 전달합니다.
- 취득원가가 양수이면 `손익 / 취득원가`, 소수점 4자리 `HALF_UP`입니다.
- 취득원가가 0 이하이면 null입니다. 손익이 0이고 원가가 양수이면 `0.0000`입니다.
- 백분율 변환을 하지 않습니다. `1 / 8`의 결과는 `0.1250`이며 12.5가 아닙니다.
- 현재 `AccountService`와 `HoldingsResponse`가 사용합니다. 환율·주가 등락률의 6자리 계산이나 평단가·평균환율 계산을 이 함수로 교체하지 않습니다.

### OrderAmountCalculator — 주문 계산

소스: [OrderAmountCalculator.java](../back/src/main/java/com/baedang/trading/service/OrderAmountCalculator.java)

`calculate(marketCountry, side, executedPrice, quantity, exchangeRate)` → `OrderAmount`.
설정된 수수료·세율을 쓰는 Spring 빈이므로 주입받아 사용합니다. 입력 유효성·거래 가능 여부는 주문 정책에서 검증합니다.

- KR 주문은 환율을 계산에 사용하지 않으며 결과 환율은 1입니다.
- US 주문은 단가를 센트 `HALF_UP` → 수량 곱하기 → 환율 적용 → 원 단위 `HALF_UP` 순서입니다.
- 수수료는 원 단위로 확정한 거래대금에 수수료율을 곱한 뒤 원 단위 반올림합니다.
- US 매도 SEC Fee는 `max(달러 거래대금 × 설정 세율, 설정 최소액)`을 센트 반올림하고, 환율 적용 후 원 단위 반올림합니다.
- 매수 순금액은 거래대금 + 수수료, 매도 순금액은 거래대금 − 수수료 − 세금입니다.
- 결과에는 원화 반올림 전 거래대금도 있습니다. 원가 계산용 값과 정산 금액을 혼용하지 않습니다.
- 이 클래스 내부의 `krw()` / `usd()`는 BigDecimal 계산용 private 메서드입니다. 같은 이름의 문자열 Formatter로 대체하지 않습니다.

## 6. 도메인 전용 지원 기능

전역 헬퍼처럼 모든 기능에 적용하지 말고 아래 도메인 계약 안에서 재사용하세요. Spring 빈은 직접 생성하지 않고 주입받습니다.

| 소스 | 공개 기능 | 범위·주의점 |
| --- | --- | --- |
| [HoldingValuator](../back/src/main/java/com/baedang/account/service/HoldingValuator.java) | `valuate(holdings, quoteByStockId, usdKrwRate)` | 계좌·보유 목록의 원화 평가. 종목별 반올림 후 합산하는 기존 정책을 재사용 |
| [LedgerCursor](../back/src/main/java/com/baedang/account/support/LedgerCursor.java) | 정적 `encode(entryId)`, `decode(cursor)` | 원장 전용 Base64URL 커서. 디코딩 오류는 `INVALID_CURSOR`. 랭킹 커서와 형식이 다름 |
| [StockCategory](../back/src/main/java/com/baedang/stock/entity/StockCategory.java) | 정적 `from(securityType, isCommonShare)` | ETF / ETN 우선, 보통주 여부가 false면 PREFERRED, 나머지 INDIVIDUAL |
| [CandleQueryPolicy](../back/src/main/java/com/baedang/stock/service/CandleQueryPolicy.java) | `parse(interval, range)`, `parseMarketCountry(value)` | 현재 `1m/1D=200`, `1d/1M=22`, `1d/6M=130`, `1d/1Y=250` 요청. 실제 반환 개수 보장이나 백필 개수 설정은 아님 |
| [MarketOrderPolicy](../back/src/main/java/com/baedang/trading/service/MarketOrderPolicy.java) | `parseCommand`, `parseTerms`, `determineRejection`, `determineStaticRejection`, `validateExecutionContextFresh`, `hasValidCurrencyForMarket` | 주문 입력·거래 가능·시세/컨텍스트 신선도·시장 통화 검증. 검증 순서와 재시도 오류 데이터를 유지 |
| [ClientOrderRetryPolicy](../back/src/main/java/com/baedang/trading/model/ClientOrderRetryPolicy.java) | `asData()` | `retryPolicy` 키를 담은 Map. SAME_CLIENT_ORDER_ID / NEW_CLIENT_ORDER_ID / NOT_RETRYABLE 계약 |
| [QuoteRealtimePolicy](../back/src/main/java/com/baedang/stock/service/QuoteRealtimePolicy.java) | `isRealtime(country, quote)`, `isMarketOpen(country)` | 현재·시세 시점 세션을 이용한 판정. 캘린더 조회가 발생할 수 있어 순수 계산 함수가 아님 |
| [LatestCompletedTradingDayResolver](../back/src/main/java/com/baedang/market/service/LatestCompletedTradingDayResolver.java) | `resolve(country)` → `Optional<LocalDate>` | 현지 날짜·캘린더로 최신 확정 거래일 탐색. 현재 마감 확정 지연 10분, 과거 탐색 최대 14일. 조회 장애·응답 불일치·미발견 시 empty |

캘린더가 필요한 로직은 기존 [MarketCalendarPort](../back/src/main/java/com/baedang/market/port/MarketCalendarPort.java)와 [MarketSessionProvider](../back/src/main/java/com/baedang/market/port/MarketSessionProvider.java)를 주입받아 사용하세요. 외부 호출이나 캐시를 별도로 복제하지 않습니다.

스트라이프 락은 아직 공용 헬퍼가 아닙니다. `CandleQueryService`와 `StockOnDemandQuoteService`의 별도 락 구현은 유지하며, 하나의 전역 락으로 공유하지 않습니다.

## 7. 프론트엔드 헬퍼

### 금액 계산·표시

| 모듈 | export | 용도·주의점 |
| --- | --- | --- |
| [decimal.ts](../front/src/lib/decimal.ts) | `D` | `ROUND_HALF_UP`을 설정한 Decimal 클래스. 금액 계산은 이 모듈을 import |
| [format.ts](../front/src/lib/format.ts) | `toDecimal(value)` | 문자열·숫자·Decimal 입력을 Decimal로 변환. null·빈값·비유한 값 등은 null |
| [format.ts](../front/src/lib/format.ts) | `toKrw(nativeValue, currency, exchangeRate)` | USD이면 환율을 곱한 Decimal, 그 외에는 원래 값. 이 단계에서는 원 단위 반올림하지 않음 |
| [format.ts](../front/src/lib/format.ts) | `formatNumber`, `formatSigned`, `formatKoreanAmount` | 정수·부호·억/조 단위 화면 표시 |
| [format.ts](../front/src/lib/format.ts) | `formatPercent(rate)` | 비율에 100을 곱해 소수점 2자리 퍼센트 표시 |
| [format.ts](../front/src/lib/format.ts) | `formatAbsolute(value, decimalPlaces)`, `formatUsd(value)` | 절댓값 지정 자릿수 / `$` 포함 소수점 2자리 표시 |
| [order-amount.ts](../front/src/lib/order-amount.ts) | `calculateOrderAmount(params)` | Decimal로 계산하는 주문 미리보기. 최종 체결 금액은 백엔드가 확정 |

`format.ts`의 문자열 표시 함수는 유효하지 않은 입력에 기본 `"-"` 또는 지정한 fallback을 반환합니다.
`toKrw()`는 USD인데 환율이 없거나 0 이하이면 null입니다. 현재 USD 여부를 대소문자 구분해 검사하고 그 외 통화는 그대로 반환하므로, 지원 통화 검증·정규화 함수로 사용하지 마세요.

```ts
import { D } from "@/lib/decimal";
import { formatNumber, formatPercent, toKrw } from "@/lib/format";

const nativePrice = new D("88.33");
const won = toKrw(nativePrice, "USD", "1383.6");
const displayPrice = formatNumber(won); // 화면 표시 단계에서 원 단위 HALF_UP
const displayRate = formatPercent("0.125"); // "+12.50%"
```

가능하면 API의 숫자 문자열을 Decimal에 바로 전달합니다. 다만 현재 주문 미리보기 결과와 환율 조회 헬퍼 등 일부 경계는 `number`를 사용하므로, 전체 경로가 임의 정밀도를 보장한다고 가정하지 않습니다. 화면 표시·미리보기 값을 원장이나 확정 체결 금액의 근거로 사용하지 마세요.

### API·차트·화면 지원

| 모듈 | export | 용도·주의점 |
| --- | --- | --- |
| [api.ts](../front/src/lib/api.ts) | `ApiError`, `getRankings`, `getCandles`, `placeOrder` 등 API별 함수 | API 호출과 code/message/data 처리 재사용. 내부 `request()`는 private |
| [order-retry-policy.ts](../front/src/lib/order-retry-policy.ts) | `generateClientOrderId()`, `nextClientOrderId(policy, currentId)` | SAME 또는 정책 없음: 기존 ID, NEW: 새 ID, NOT_RETRYABLE: null. 멱등성 키이며 인증용 난수가 아님 |
| [exchange-rate.ts](../front/src/lib/exchange-rate.ts) | `fetchExchangeRate()` | 화면용 환율 조회. 실패 시 기본값으로 대체하므로 체결용 환율 근거로 사용하지 않음 |
| [candle-chart-data.ts](../front/src/lib/candle-chart-data.ts) | `toCandlestickData`, `toVolumeData` | 차트 숫자 데이터 변환·시간 정렬·중복 제거. 정산용 계산이 아님 |
| [exchange-rate-chart-data.ts](../front/src/lib/exchange-rate-chart-data.ts) | `toLinePoints`, `isTimeVisible`, `formatTickMark` | 환율 차트 다운샘플링·시간축 표시. 원본 저장 데이터는 변경하지 않음 |
| [chart-colors.ts](../front/src/lib/chart-colors.ts) | `resolveCssColor(name, fallback)` | CSS 변수를 차트용 색상으로 변환. DOM이 없는 환경에서는 fallback |
| [category-badge.ts](../front/src/lib/category-badge.ts) | `categoryLabel`, `CATEGORY_BADGE_STYLE` | 배당 여부·종목 유형을 화면 배지로 변환. 백엔드 종목 분류 변경 기능이 아님 |

## 8. 적용·변경 시 체크리스트

- 중복 코드가 보여도 계산 정밀도, null 처리, 오류 코드, 시간대 정책이 같은지 먼저 확인합니다.
- 검증·정규화·계산·표시를 구분합니다. Formatter로 계산하거나 Normalizer로 검증을 대신하지 않습니다.
- 헬퍼는 재사용하되 서비스별 예외와 잠금·트랜잭션 경계는 임의로 통합하지 않습니다.
- 기존 테스트를 먼저 유지합니다. 리팩터링 후 실패하면 서비스 고유 정책을 바꿨는지 먼저 점검합니다.
- 새 공개 메서드는 경계값 테스트를 추가하고 이 문서의 메서드명·사용 예·주의점을 함께 갱신합니다.
- 기준 테스트: [정규화](../back/src/test/java/com/baedang/global/normalizer/DomainNormalizerTest.java), [서비스별 계약](../back/src/test/java/com/baedang/global/normalizer/DomainNormalizationContractTest.java), [시장 정보](../back/src/test/java/com/baedang/stock/entity/MarketCountryTest.java), [손익률](../back/src/test/java/com/baedang/account/support/ReturnRateCalculatorTest.java), [프론트 헬퍼](../front/src/lib/__tests__).
