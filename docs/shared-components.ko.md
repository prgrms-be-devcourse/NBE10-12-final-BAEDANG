# 공용 구성요소 사용 가이드

[English](shared-components.md) | 한국어

> 동기화 규칙: 공개 메서드·예제·정책을 변경할 때 `shared-components.md`와 `shared-components.ko.md`를 함께 갱신합니다.

여러 곳에서 재사용하는 전역 설정, 공용 유틸리티·계산기, 도메인 공용 서비스·정책, 프론트 모듈의 색인입니다. 새 기능을 작성하기 전에 같은 역할의 구현이 있는지 확인하세요.
공용이라는 말은 모두 순수 헬퍼라는 뜻이 아닙니다. 각 항목의 역할·호출/주입 방법·부수 효과를 구분하고, 개별 서비스의 private 메서드와 단순 getter는 나열하지 않습니다.

이 문서는 기존 동작을 설명합니다. API 계약은 [API 명세](api-spec.ko.md), 저장 정책은 [ERD](erd.ko.md)를 함께 확인하고, 공용 구성요소의 공개 메서드나 정책을 바꿀 때 이 문서도 갱신하세요.

## 1. 빠른 선택

| 필요한 작업 | 사용할 기능 | 호출 방식 |
| --- | --- | --- |
| 심볼·통화·이메일·검색어 정규화 | `DomainNormalizer` | 정적 메서드 |
| BigDecimal을 API 응답 문자열로 변환 | `FinancialDecimalFormatter` | 정적 메서드 |
| 국가 코드 파싱·시장별 시간대·기본 통화 | `MarketCountry` | 정적 메서드 / enum 메서드 |
| 계좌·보유 종목 손익률 계산 | `ReturnRateCalculator` | 정적 메서드 |
| 주문 거래대금·수수료·세금 계산 | `MarketOrderSettlementCalculator` | Spring 빈 주입 |
| 지정가 누적 정산 차액 계산 | `LimitOrderSettlementCalculator` | Spring 빈 주입 |
| 보유 종목 평가 | `HoldingValuator` | Spring 빈 주입 |
| 현재 시각·시장 현지 날짜 계산 | `TimeConfig`의 `Clock` | Clock 주입 |
| 차트 조합·거래일·실시간 시세 판정 | 해당 도메인의 Policy / Resolver | Spring 빈 주입 |
| 프론트 금액 계산·표시 | `D`, `format.ts`, `order-amount.ts` | 모듈 import |

## 2. 전역 설정과 공통 인프라

`global` 패키지는 여러 도메인이 사용하는 공통 기반입니다. 모든 파일이 정적 헬퍼인 것은 아닙니다. 설정 클래스는 보통 직접 호출하지 않고 등록된 빈을 주입받거나 프레임워크의 자동 적용을 이용합니다.

### 설정·공통 엔티티

| 소스 | 역할·사용 방법 | 주의사항 |
| --- | --- | --- |
| [TimeConfig](../back/src/main/java/com/baedang/global/config/TimeConfig.java) | UTC `Clock` 빈 제공. 생성자로 `Clock`을 주입받아 `clock.instant()` 사용 | 테스트에서는 고정 Clock으로 교체. 현지 날짜와 UTC 시각의 변환 예시는 시장 정보 절 참고 |
| [PasswordConfig](../back/src/main/java/com/baedang/global/config/PasswordConfig.java) | `PasswordEncoder` 빈 주입 후 `encode(raw)`, `matches(raw, encoded)` 사용 | 현재 BCrypt 사용. 직접 해시 함수를 만들거나 인코더를 반복 생성하지 않음 |
| [JpaConfig](../back/src/main/java/com/baedang/global/config/JpaConfig.java) | JPA Auditing과 `auditingDateTimeProvider` 자동 적용 | 현재 제공자는 `OffsetDateTime.now(ZoneOffset.UTC)`를 직접 사용하므로 주입 Clock을 고정해도 감사 시각은 고정되지 않음 |
| [BaseEntity](../back/src/main/java/com/baedang/global/entity/BaseEntity.java) | 상속으로 `createdAt`, `updatedAt` 자동 기록 | 실제 테이블에 `created_at`, `updated_at` 두 컬럼이 있는 경우만 상속. 계좌의 `openedAt`·원장의 `occurredAt`을 대체하지 않음 |
| [SchedulingConfig](../back/src/main/java/com/baedang/global/config/SchedulingConfig.java) | 공용 `taskScheduler`, 만료 전용 `limitOrderTaskScheduler`, 호가 전용 `orderBookTaskScheduler`, 일봉 전용 `dailyCandleTaskExecutor` 빈 제공. 해당 실행기는 `@Qualifier("dailyCandleTaskExecutor")`로 주입 | 호가 갱신·정리는 공용 배치와 분리된 단일 스레드에서 직렬 실행하고 DB 트랜잭션에 로컬 2초 락 타임아웃을 적용. 일봉 전용 실행기는 스레드 1개, 큐 10개, 종료 대기 최대 30초. 다른 비동기 작업을 무조건 공유시키지 않으며 배치 활성화 조건은 각 스케줄러 책임 |
| [CorsConfig](../back/src/main/java/com/baedang/global/config/CorsConfig.java) | `/api/**`에 자동 적용. 허용 출처는 `cors.allowed-origins` / `CORS_ALLOWED_ORIGINS`로 설정 | 직접 호출할 필요 없음. CORS 허용은 인증·인가를 대신하지 않음 |

### 오류 처리·외부 통신

| 소스 | 역할·사용 방법 | 주의사항 |
| --- | --- | --- |
| [BusinessException](../back/src/main/java/com/baedang/global/error/BusinessException.java), [ErrorCode](../back/src/main/java/com/baedang/global/error/ErrorCode.java) | 업무 오류를 `throw new BusinessException(ErrorCode.…)`로 전달. 필요하면 detail 또는 data 지정 | detail은 개발자 진단용, data는 클라이언트 분기용. 기존 오류 코드·재시도 계약을 유지 |
| [GlobalExceptionHandler](../back/src/main/java/com/baedang/global/error/GlobalExceptionHandler.java), [ErrorResponse](../back/src/main/java/com/baedang/global/error/ErrorResponse.java) | 전역 예외 처리기가 오류를 HTTP 응답으로 자동 변환 | 컨트롤러마다 같은 try/catch·오류 응답 생성을 복제하지 않음 |
| [TossSecuritiesClient](../back/src/main/java/com/baedang/global/clients/toss/TossSecuritiesClient.java) | Toss 어댑터에서 빈을 주입받아 `get(path, queryParams, responseType)` 호출 | 업무 서비스는 기존 Port를 사용. 허용 경로 검증과 전역 RateLimiter를 우회하지 않으며 실제 주문 API는 절대 호출하지 않음 |
| [TossRateLimiterRegistry](../back/src/main/java/com/baedang/global/clients/toss/TossRateLimiterRegistry.java), [TossApiGroup](../back/src/main/java/com/baedang/global/clients/toss/TossApiGroup.java), [Whitelist](../back/src/main/java/com/baedang/global/clients/toss/Whitelist.java) | 그룹별 공유 호출 제한과 경로 매핑. 레지스트리는 `acquire(group)`, `tryAcquire(group)` 제공 | 일반 요청은 Toss 클라이언트가 이미 제한을 적용하므로 상위 서비스에서 같은 요청에 permit을 이중 획득하지 않음. 호출 제한은 같은 애플리케이션 인스턴스 안에서 공유 |
| [FixedIntervalGate](../back/src/main/java/com/baedang/global/clients/FixedIntervalGate.java) | `com.baedang.global.clients`에 위치한 토큰 버킷 공용 게이트 알고리즘 | 증권사 간 공용 재사용. Toss와 KIS는 독립적인 게이트 인스턴스를 유지하며 증권사 간에 permit을 교차 대여하지 않음 |
| [KisSecuritiesClient](../back/src/main/java/com/baedang/global/clients/kis/KisSecuritiesClient.java), [KisTokenProvider](../back/src/main/java/com/baedang/global/clients/kis/KisTokenProvider.java), [KisRateLimiter](../back/src/main/java/com/baedang/global/clients/kis/KisRateLimiter.java), [KisWhitelist](../back/src/main/java/com/baedang/global/clients/kis/KisWhitelist.java) | 토큰 double-check 잠금과 65초 실패 쿨다운을 적용한 KIS Developers 전용 화이트리스트 클라이언트 | OAuth 토큰 발급 및 승인된 5개 GET 경로로 엄격 제한. 호출 제한 적용(2026-09-10 KST 이전 3 TPS / 2026-09-10 KST 이후 18 TPS 운영 예정). 실제 주문·정정·취소·계좌 API 호출 금지 |
| [KisProperties](../back/src/main/java/com/baedang/global/clients/kis/KisProperties.java) | `kis.*` 설정값 검증 레코드 | `kis.enabled=false`일 때만 빈 credential 허용. 기동 시 1..18 TPS 및 양수 timeout/TTL 검증 |

## 3. 문자열 정규화 — DomainNormalizer

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

## 4. 응답 숫자 포맷 — FinancialDecimalFormatter

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

## 5. 시장 정보 — MarketCountry

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
- 일봉 및 시세 거래일은 거래소 현지 날짜다. 실제 원장 시각은 UTC로 저장하고 KST로 표시한다. 일봉·주봉 응답의 KST 자정은 날짜 라벨이며 봉 시작 시각이 아니다. 환율 기준일은 기존 KST 정책을 유지한다.
- 시간대만으로 개장·폐장·휴장 여부를 추정하지 않습니다. 장 운영 정보는 기존 캘린더 / 세션 Port를 사용합니다.

## 6. 수익률과 주문 금액 계산

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

### MarketOrderSettlementCalculator — 주문 계산

소스: [MarketOrderSettlementCalculator.java](../back/src/main/java/com/baedang/trading/service/MarketOrderSettlementCalculator.java)

`calculate(marketCountry, side, executedPrice, quantity, exchangeRate)` → `MarketOrderAmount`.
설정된 수수료·세율을 쓰는 Spring 빈이므로 주입받아 사용합니다. 입력 유효성·거래 가능 여부는 주문 정책에서 검증합니다.

요율·SEC 최소액은 주문별 스냅샷이 아닌 프로젝트 고정 `.env` 설정입니다. 활성 주문이 있는 동안 재시작·재배포에도 동일한 값을 유지합니다.

- KR 주문은 환율을 계산에 사용하지 않으며 결과 환율은 1입니다.
- US 주문은 단가를 센트 `HALF_UP` → 수량 곱하기 → 환율 적용 → 원 단위 `HALF_UP` 순서입니다.
- 수수료는 원 단위로 확정한 거래대금에 수수료율을 곱한 뒤 원 단위 반올림합니다.
- US 매도 SEC Fee는 `max(달러 거래대금 × 설정 세율, 설정 최소액)`을 센트 반올림하고, 환율 적용 후 원 단위 반올림합니다.
- 매수 순금액은 거래대금 + 수수료, 매도 순금액은 거래대금 − 수수료 − 세금입니다.
- 결과에는 원화 반올림 전 거래대금도 있습니다. 원가 계산용 값과 정산 금액을 혼용하지 않습니다.
- 이 클래스 내부의 `krw()` / `usd()`는 BigDecimal 계산용 private 메서드입니다. 같은 이름의 문자열 Formatter로 대체하지 않습니다.

### LimitOrderSettlementCalculator — 지정가 누적 정산

소스: [LimitOrderSettlementCalculator.java](../back/src/main/java/com/baedang/trading/service/LimitOrderSettlementCalculator.java)

호가 하나의 정산 차액을 주문의 누적 상태 대비 계산합니다. 수수료·세율은 `MarketOrderSettlementCalculator`와 같은 `.env` 설정을 공유하는 Spring 빈으로 주입받습니다. 순수 계산만 수행하며, 호가 탐색·수량 선택·외부 조회·DB 변경은 호출부(엔진/워커) 책임입니다.

#### `calculate(country, side, price, quantity, exchangeRate, previous)` → `LimitOrderSettlementResult`

이번 호가의 gross/fee/tax/net 차액을 누적 합계에서 이전 누적 합계를 빼서 산출합니다. 체결마다 독립 계산할 때 생기는 반올림 오차 누적을 원천 방지합니다.

- 입력 `previous`는 `TradeExecutionRepository.summarizeByOrderId()`로 저장된 체결에서 복원한 `CumulativeSettlementState`이거나, 첫 체결이면 `CumulativeSettlementState.empty()`입니다.
- KR 단가는 정수, US 단가는 센트(소수점 2자리) 단위로 표현 가능해야 합니다. 후행 0은 허용합니다.
- 수량은 양의 정수이며 `maxOrderQuantity` 이하여야 합니다. 누적 수량(`previous.quantity + quantity`)도 동일하게 검증합니다.
- KR은 전달된 환율을 무시하고 1을 사용합니다. US는 양수이며 소수점 6자리로 표현 가능한 환율이 필요합니다.
- `validateState()`는 저장된 누적값과 고정 요율로 금액을 재도출합니다. 불일치 시 과거 금액을 덮어 맞추지 않고 `INTERNAL_ERROR`를 던집니다.
- `netAmountKrw <= 0`이면 `isExecutable() == false`(amounts = null, 이전 상태 유지)를 반환합니다. 호출부는 체결·원장 기록·유동성 소비 없이 후보를 보류해야 합니다.
- SEC Fee는 체결별 거래대금이 아닌 주문 누적 달러 거래대금 기준입니다. 최소액(\$0.01)은 전체 체결에 한 번만 적용합니다. 이번 체결의 SEC 증가분만 현재 환율로 원화 환산하며, 이전 체결의 SEC 비용은 당시 환율을 유지합니다.
- 금액 상한 검증(`MONEY_LIMIT`)은 차액이 아닌 누적 합계 기준입니다.

#### `initialReservedCash(country, limitPrice, quantity, exchangeRate)` → `BigDecimal`

접수(Phase 1)용 매수 최초 동결액을 계산합니다. 내부적으로 `CumulativeSettlementState.empty()`와 `OrderSide.BUY`로 `calculate()`를 호출하여 net 금액을 반환합니다. 환율 버퍼가 없으며 KR은 외부 조회 없이 1을 사용합니다.

#### `reserveAfterBuy(reservedCash, remainingQuantity, fillQuantity, netAmountKrw)` → `BuyReservationResult`

매수 체결이 주문의 현재 `reservedCash` 안에서 가능한지 판단합니다. 자유 예수금은 사용하지 않습니다.

- 전량 체결(`fillQuantity == remainingQuantity`): 체결 가능, 미사용 잔액(`reservedCash − netAmountKrw`) 해제.
- 부분 체결(동결 잔액 있음): 체결 가능, 감소된 동결액 유지.
- 부분 체결(동결 잔액 소진, `remainingCash == 0`): 체결 불가 — 활성 잔량에는 양수 동결액 필요.
- `netAmountKrw <= 0` 또는 `remainingCash < 0`: 체결 불가, 동결액 변경 없음.
- 구매 가능 수량을 탐색하지 않습니다. 호출부(엔진)가 수량을 줄인 뒤 이 메서드를 호출합니다.

#### 관련 모델 타입

| 타입 | 역할 |
| --- | --- |
| [`CumulativeSettlementState`](../back/src/main/java/com/baedang/trading/model/CumulativeSettlementState.java) | 누적 합계 8필드 레코드 (수량, 원시 거래대금, 반올림 전 원화 거래대금, SEC USD, 반올림 전 세금 원화, 확정 gross/fee/tax 원화). DB에서 `summarizeByOrderId()`로 복원 |
| [`LimitOrderSettlementResult`](../back/src/main/java/com/baedang/trading/model/LimitOrderSettlementResult.java) | net 금액, nullable `ExecutionAmounts`, 다음 `CumulativeSettlementState`를 감싸는 결과. `isExecutable()`은 amounts != null 검사, `requireExecutionAmounts()`는 체결 불가 시 예외 |
| [`ExecutionAmounts`](../back/src/main/java/com/baedang/trading/model/ExecutionAmounts.java) | 체결 1건의 정산 차액 (USD 거래대금, 반올림 전 원화 거래대금, SEC USD 차액, 확정 gross/fee/tax/net 원화). `TradeExecution.create()`와 공유 |
| [`BuyReservationResult`](../back/src/main/java/com/baedang/trading/model/BuyReservationResult.java) | `executable`, `reservedCashAfter`, `releasedCash`. 엔진이 `TradeOrder.reservedCash`와 `Account.lockedCash`를 갱신하는 데 사용 |

## 7. 도메인 공용 기능

여러 유스케이스에서 쓰더라도 아래 기능은 각 도메인의 계약에 속합니다. 계산기·파서는 입력을 처리하지만, 원장 서비스는 DB에 저장하고 세션 정책·거래일 리졸버는 외부 조회를 수행할 수 있습니다. Spring 빈은 생성자로 주입받고 필요한 트랜잭션과 잠금은 호출 유스케이스에서 관리합니다.

| 소스 | 공개 기능 | 범위·주의점 |
| --- | --- | --- |
| [LedgerService](../back/src/main/java/com/baedang/trading/service/LedgerService.java) | `recordInitialDeposit(accountId, initialCash, roundNo, occurredAt)`, `recordBuy/recordSell(order, savedExecution, balanceAfter, stock)` | MANDATORY. 초기 지급/체결 원장의 메모·부호·INSERT만 담당. 잔액 변경/정산은 호출부 책임. 소유 주문·저장된 체결·체결 직후 잔액을 전달합니다. 계좌·종목·방향은 주문에서 얻고 체결의 주문 연결을 검증하며, executionId 중복 정상 원장은 DB에서 거절하여 전체 트랜잭션 롤백. |
| [HoldingValuator](../back/src/main/java/com/baedang/account/service/HoldingValuator.java) | `valuate(holdings, quoteByStockId, usdKrwRate)` | 계좌·보유 목록의 원화 평가. 종목별 반올림 후 합산하는 기존 정책을 재사용 |
| [LedgerCursor](../back/src/main/java/com/baedang/account/support/LedgerCursor.java) | 정적 `encode(entryId)`, `decode(cursor)` | 원장 전용 Base64URL 커서. 디코딩 오류는 `INVALID_CURSOR`. 랭킹 커서와 형식이 다름 |
| [StockCategory](../back/src/main/java/com/baedang/stock/entity/StockCategory.java) | 정적 `from(securityType, isCommonShare)` | ETF / ETN 우선, 보통주 여부가 false면 PREFERRED, 나머지 INDIVIDUAL |
| [CandleQueryPolicy](../back/src/main/java/com/baedang/stock/service/CandleQueryPolicy.java) | `parse(interval, range)`, `parseMarketCountry(value)` | 현재 `1m/1D=200`, `1d/1M=22`, `1d/6M=130`, `1d/1Y=250` 요청. 실제 반환 개수 보장이나 백필 개수 설정은 아님 |
| [OrderPolicy](../back/src/main/java/com/baedang/trading/service/OrderPolicy.java) | `parseInput`, `parseTerms`, `determineStaticRejection`, `validateQuoteTime`, `validateExecutionContextFresh`, `hasValidCurrencyForMarket` | 시장가·지정가 공통 입력·종목·시세/컨텍스트 신선도·시장 통화 검증. 검증 순서와 재시도 오류 데이터를 유지 |
| [MarketOrderPolicy](../back/src/main/java/com/baedang/trading/service/MarketOrderPolicy.java) | `determineRejection` | OrderPolicy를 주입받아 공통 검증을 재사용하고 시장가 순정산액·매수 가능 금액·매도 가능 수량 및 거절 우선순위를 검증. 지정가 접수에는 사용하지 않음 |
| [OrderInput](../back/src/main/java/com/baedang/trading/model/OrderInput.java) | `accountId`, `clientOrderId`, `terms` | parseInput의 공통 검증 결과. 각 유스케이스가 MarketOrderCommand 또는 LimitOrderCommand를 직접 생성 |
| [OrderMarketContext](../back/src/main/java/com/baedang/trading/model/OrderMarketContext.java) | `executionRate()`, `isMarketOpenAt(now)` | 시장가 체결·지정가 접수의 공통 외부 시장 스냅샷. 트랜잭션 전에 준비하고 계좌 잠금 후 유효성을 재검증 |
| [ClientOrderRetryPolicy](../back/src/main/java/com/baedang/trading/model/ClientOrderRetryPolicy.java) | `asData()` | `retryPolicy` 키를 담은 Map. SAME_CLIENT_ORDER_ID / NEW_CLIENT_ORDER_ID / NOT_RETRYABLE 계약 |
| [QuoteRealtimePolicy](../back/src/main/java/com/baedang/stock/service/QuoteRealtimePolicy.java) | `isRealtime(country, quote)`, `isMarketOpen(country)` | 현재·시세 시점 세션을 이용한 판정. 캘린더 조회가 발생할 수 있어 순수 계산 함수가 아님 |
| [LatestCompletedTradingDayResolver](../back/src/main/java/com/baedang/market/service/LatestCompletedTradingDayResolver.java) | `resolve(country)` / `resolve(country, requestedAt)` → `Optional<LocalDate>` | 현지 날짜·캘린더로 최신 확정 거래일 탐색. 현재 마감 확정 지연 10분, 과거 탐색 최대 14일. 조회 장애·응답 불일치·미발견 시 empty |

| [TickSizePolicy](../back/src/main/java/com/baedang/orderbook/service/TickSizePolicy.java) | `nextValidPriceAbove`, `previousValidPriceBelow`, `isValidPrice`, `tickSizeAt` | 시장·종목 유형별 호가 단위 및 경계를 넘는 유효 가격 계산. NUMERIC(19,4) 최대 범위(999999999999999.9999) 내에서 계산 |
| [OrderBookGenerator](../back/src/main/java/com/baedang/orderbook/service/OrderBookGenerator.java) | `generate(policy, stock, basePrice, quoteAt, generatedAt, seed)` | 고정 seed와 설정 기반 순수 가상 호가 생성기. V1 깊이 배수·정수 노이즈·tick 상대 라운드 넘버 부스트 적용. ASK 10개와 시장별 BID 깊이(국내 10개, 미국 1~10개)를 생성 |
| [OrderBookExecutionStore](../back/src/main/java/com/baedang/orderbook/port/OrderBookExecutionStore.java) | `lockForExecution(stockId, expectedBookVersion, expectedRevision, side)` | MANDATORY. 지정가 부분 체결 엔진(#122)이 동일 트랜잭션에서 활성 버전과 방향별 실제 레벨을 비관적 락으로 잠금. ASK는 10개, KRW BID는 10개, USD BID는 1~10개이며 10개 미만이면 마지막 가격은 `$0.01`. BUY→ASK, SELL→BID |
| [OrderBookProperties](../back/src/main/java/com/baedang/orderbook/config/OrderBookProperties.java) | `policyVersion()`, `krBaseNotional()`, `minQuantity()`, 등 | `trading.orderbook` 런타임 설정값 검증 레코드. V1 기본값: 3s 주기, 15s maxQuoteAge, 1m retention |

| [StockFinancialInfoPort](../back/src/main/java/com/baedang/stock/port/StockFinancialInfoPort.java), [KisStockFinancialInfoAdapter](../back/src/main/java/com/baedang/stock/client/kis/KisStockFinancialInfoAdapter.java) | 산업분류 및 결산연월별 재무제표용 도메인 포트 및 KIS 어댑터 | 포트는 순수 도메인 레코드(`IndustryData`, `PeriodData`) 반환. 어댑터 빈은 `kis.enabled=true` 조건부 등록 |
| [StockFinancialSyncService](../back/src/main/java/com/baedang/stock/service/StockFinancialSyncService.java) | `ensureFresh(stock, trigger)`, `refresh(stock, trigger)`, `refreshRankedTargets(trigger)` | TTL 판정(재무 7일 / 7d, 산업 30일 / 30d), 종목별 `CompletableFuture` single-flight, 주간 배치 실행. `Optional<StockFinancialInfoPort>` 주입으로 KIS 비활성 시에도 정상 부팅 유지 |
| [StockFinancialQueryService](../back/src/main/java/com/baedang/stock/service/StockFinancialQueryService.java) | `getFinancials(symbol, marketCountry)` | 캐시 우선 재무 조회 서비스. 국내 비ETF/ETN 종목 검증, 조회 시점 영업이익률 계산, FRESH/STALE 판정 및 폴백 처리 |
현재 구현의 설정 가능한 가상 호가 V1 기본값은 다음과 같습니다: `policyVersion=V1`, `refreshInterval=3s`, `refreshInitialDelay=0s`, `maxQuoteAge=15s`, `krBaseNotional=20000000`, `usBaseNotional=15000`, `minQuantity=1`, `maxQuantity=1000000`, `noiseMinBps=8000`, `noiseMaxBps=12000`, `closedVersionRetention=1m`, `retentionInitialDelay=0s`. V1 호가 형상은 런타임 설정이 아니라 코드 불변식입니다. 각 방향은 10레벨이고 인접 레벨은 유효 호가 1틱 간격이며, 미국 BID는 `$0.01`에서 조기 종료할 수 있습니다. 종료 버전과 레벨은 소비 여부와 무관하게 retention 후 삭제되며, 체결 가격·수량·정산 금액은 `trade_execution`에 영구 보존됩니다. 다른 형상은 새 정책 버전으로 구현합니다. 이 수치는 #121 PR에서 근거를 제시하고 합의할 모의 공급 제안값이며, 구현만으로 합의가 완료되거나 실제 시장 잔량을 재현한 것은 아닙니다. 두 initial delay는 스케줄러 시작 시점만 제어하는 운영 설정이며 0 이상이어야 합니다.

가상 호가 생성·조회는 상시 활성입니다. 호가는 정규장 중 유효한 시세가 있을 때만 생성합니다. 지정가 접수·취소·만료·체결은 별도 유스케이스입니다. 사용 가능한 호가가 없으면 워커는 물량을 만들지 않고 보류합니다.

캘린더가 필요한 로직은 기존 [MarketCalendarPort](../back/src/main/java/com/baedang/market/port/MarketCalendarPort.java)와 [MarketSessionProvider](../back/src/main/java/com/baedang/market/port/MarketSessionProvider.java)를 주입받아 사용하세요. 외부 호출이나 캐시를 별도로 복제하지 않습니다.

스트라이프 락은 아직 공용 헬퍼가 아닙니다. `CandleQueryService`와 `StockOnDemandQuoteService`의 별도 락 구현은 유지하며, 하나의 전역 락으로 공유하지 않습니다.

### DecimalScaleValidator — 거래 입력 소수 자릿수 검증

`com.baedang.trading.support.DecimalScaleValidator.isRepresentableAtScale(value, scale)`을 정적으로 호출합니다. null은 false, 후행 0을 제외하고 허용 소수 자릿수로 손실 없이 표현 가능하면 true입니다. 원본 값·스케일을 변경하지 않으며 전체 NUMERIC precision, 양수 여부, 통화별 정산 계산은 검증하지 않습니다. 주문·체결·정산 입력의 기존 조건문에 결합하고, 예외 선택은 호출부에서 담당합니다.

### 가상 호가 및 잠금 계약 (Virtual Order Book & Locking Contracts)

가상 호가 모듈(`com.baedang.orderbook`)은 Toss 현재가를 기반으로 모든 사용자가 공유하는 가상 매수 10호가·매도 10호가를 공급하고, #122 체결 엔진을 위한 잠금 저장소 계약을 제공합니다.

#### 1. 잠금 순서 규칙 (교착 상태 방지)
- **Publisher (호가 게시·종료)**: `stock → order_book_version` (account, trade_order, holding을 잠그지 않음)
- **Consumer (#122 지정가 체결 엔진)**: `account → trade_order → order_book_version → order_book_level(체결 가격 순서) → holding` (stock을 잠그지 않음)
- 두 주체 간 상호 대기가 발생하지 않도록 잠금 계층을 엄격히 분리합니다.

#### 2. 체결 엔진(#122) 연동 계약
- **포트 호출**: `OrderBookExecutionStore.lockForExecution(...)`는 `Propagation.MANDATORY`로 실행되며, 호출 전 #122가 `account → trade_order`를 잠근 동일 트랜잭션 안에서 호출해야 합니다.
- **방향 매핑**: 주문 BUY는 가상 공급 ASK를 소비하고, 주문 SELL은 가상 공급 BID를 소비합니다 (`BUY → ASK`, `SELL → BID`).
- **레벨 정렬 순서**: ASK는 `price ASC, levelDepth ASC`, BID는 `price DESC, levelDepth ASC`로 비관적 락(`FOR UPDATE`)을 획득합니다. 잠근 방향의 깊이가 연속적이지 않거나 가격이 ASK 엄격 오름차순·BID 엄격 내림차순이 아니면 거절합니다.
- **불일치 처리**: 기대하는 `bookVersion`이나 `revision`이 일치하지 않거나 이미 종료된 버전이면 `Optional.empty()`를 반환합니다. 이 결과에 대한 재시도/보류 처리는 #122의 책임이며, HTTP 접수용 `SAME_CLIENT_ORDER_ID`로 일괄 매핑하지 않습니다.
- **상태 전이 primitive**: 한 트랜잭션에서 여러 레벨을 소비하더라도 `OrderBookVersion.advanceRevision()`은 트랜잭션당 1회만 호출합니다.

#### 3. 수량 및 정밀도 정책
- **정수 수량 정책**: DB 컬럼은 후속 소수점 호환성을 위해 `NUMERIC(19,6)`을 유지하지만, V1 가상 호가 생성과 체결 소비는 **정수 주 단위** 정책입니다 (`minQuantity=1`, `maxQuantity=1000000`).
- **수량 분포의 성격**: 깊이 배수와 라운드 넘버 부스트는 모의 시장 V1 공급 정책일 뿐이며, 실제 시장의 호가 잔량 분포를 실증 재현한 것이 아니므로 상단 수량이 항상 크다는 절대 불변식을 가정하지 않습니다.


### 한국투자증권(KIS) 재무정보 연동 계약

#### 1. 호출 제한 및 시크릿 보호
- **공용 게이트 위치**: `com.baedang.global.clients.FixedIntervalGate`를 공용으로 재사용하되, `TossRateLimiterRegistry`와 `KisRateLimiter`는 엄격히 분리된 게이트 인스턴스를 유지합니다.
- **호출 제한 정책**: `kis.requests-per-second` (1..18) 설정 기반. 운영 환경은 2026-09-10 KST 이전 3 TPS / 2026-09-10 KST 이후 18 TPS 운영 예정입니다. 토큰 발급은 single-flight 중복 제거 및 실패 시 65초 쿨다운을 적용합니다.
- **시크릿 보호**: KIS `app-key`, `app-secret`, access token은 환경변수/시크릿으로만 주입하며 소스, fixture, 로그, 예외 메시지에 남기지 않습니다. `.env.example`에는 변수명과 빈 기본값만 제공합니다.

#### 2. 아키텍처 및 포트-어댑터 경계
- **도메인 포트 격리**: `StockFinancialSyncService`는 `StockFinancialInfoPort`에 의존하고, `StockFinancialQueryService`는 저장소에 의존하면서 캐시 갱신을 `StockFinancialSyncService`에 위임합니다. 두 서비스 모두 KIS 클라이언트나 어댑터를 직접 참조하지 않습니다.
- **Optional 포트 주입**: `StockFinancialSyncService`는 `Optional<StockFinancialInfoPort>`를 주입받아, `kis.enabled=false` 상태에서도 앱 기동이 깨지지 않고 로컬 캐시로 안전하게 서비스합니다.
- **트랜잭션 분리**: 외부 KIS HTTP 호출은 절대 활성 DB 트랜잭션 안에서 수행하지 않습니다. 단기 트랜잭션은 파싱된 기간 데이터 저장과 동기화 시각 갱신 시 `StockFinancialPersistenceService`에서만 엽니다.

#### 3. TTL 및 스케줄링 정책
- **TTL 규칙**: 재무제표(연간·분기)는 7일(7d / 7 days), 산업분류는 30일(30d / 30 days) TTL을 적용합니다.
- **Single-Flight 동시성**: 동일 종목의 동시 요청은 `ConcurrentHashMap` owner/waiter 패턴으로 `CompletableFuture`를 공유합니다. owner는 future 완료 후 `finally`에서 자기 맵 엔트리만 비교 제거하고, 일반 조회 flight에 합류한 강제 갱신 waiter는 결과를 다시 검사하여 재무 그룹이 갱신되지 않았으면 강제 flight를 시작합니다.
- **주간 배치 호출량**: 매주 월요일 08:10 KST에 국내 상위 100위 비ETF/ETN 종목을 순차 처리합니다. 종목당 연간 4콜 + 분기 4콜을 호출하며 산업분류는 미적재 또는 만료 시에만 1콜 추가합니다 (최대 800 / 900콜).
- **단일 레플리카 한계**: 현재 인메모리 single-flight와 rate limiter는 단일 JVM 인스턴스 범위입니다. 다중 레플리카로 확장하기 전 분산 락, 공유 토큰 캐시, 중앙 rate limiter가 선행되어야 합니다.

#### 4. 폴백 및 Negative Cache
- **Negative Cache**: KIS에서 정상 빈 응답이 오면 해당 동기화 시각을 갱신하고 빈 상태를 유지하여, TTL 기간 동안 불필요한 반복 호출을 방지합니다.
- **STALE 폴백**: 외부 갱신이 실패하더라도 기존 캐시가 존재하면 `dataStatus="STALE"`로 반환합니다. 기존 캐시가 전혀 없으면 원래 실패 코드(`KIS_RATE_LIMITED` 우선, 그 외 `KIS_API_ERROR`)를 클라이언트에 반환합니다.
- **그룹 격리**: 산업분류·연간·분기 갱신 그룹은 각각 독립적으로 시도합니다. 내부 저장 실패는 나머지 그룹 실행이 끝날 때까지 보류한 뒤, 첫 실패를 다시 던지고 이후 실패는 suppressed exception으로 보존합니다.

## 8. 프론트 공용 모듈

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

API·환율 조회 모듈은 HTTP 호출을 수행하는 클라이언트이며 순수 헬퍼가 아닙니다. 차트·표시 변환과 구분하여 재사용하세요. CSS 색 변환은 브라우저 DOM/Canvas에 의존합니다.

| 모듈 | export | 용도·주의점 |
| --- | --- | --- |
| [api.ts](../front/src/lib/api.ts) | `ApiError`, `getRankings`, `getCandles`, `placeOrder` 등 API별 함수 | API 호출과 code/message/data 처리 재사용. 내부 `request()`는 private |
| [order-retry-policy.ts](../front/src/lib/order-retry-policy.ts) | `generateClientOrderId()`, `nextClientOrderId(policy, currentId)` | SAME 또는 정책 없음: 기존 ID, NEW: 새 ID, NOT_RETRYABLE: null. 멱등성 키이며 인증용 난수가 아님 |
| [exchange-rate.ts](../front/src/lib/exchange-rate.ts) | `fetchExchangeRate()` | 실패를 Provider에 전달하며 마지막 정상값·시각을 보존하고 실패 상태를 표시합니다. 임의 기본값은 없으며 체결 환율의 출처가 아닙니다 |
| [candle-chart-data.ts](../front/src/lib/candle-chart-data.ts) | `toCandlestickData`, `toVolumeData` | 차트 숫자 데이터 변환·시간 정렬·중복 제거. 정산용 계산이 아님 |
| [exchange-rate-chart-data.ts](../front/src/lib/exchange-rate-chart-data.ts) | `toLinePoints`, `isTimeVisible`, `formatTickMark` | 환율 차트 다운샘플링·시간축 표시. 원본 저장 데이터는 변경하지 않음 |
| [chart-colors.ts](../front/src/lib/chart-colors.ts) | `resolveCssColor(name, fallback)` | CSS 변수를 차트용 색상으로 변환. DOM이 없는 환경에서는 fallback |
| [category-badge.ts](../front/src/lib/category-badge.ts) | `categoryLabel`, `CATEGORY_BADGE_STYLE` | 배당 여부·종목 유형을 화면 배지로 변환. 백엔드 종목 분류 변경 기능이 아님 |

## 9. 적용·변경 시 체크리스트

- 중복 코드가 보여도 계산 정밀도, null 처리, 오류 코드, 시간대 정책이 같은지 먼저 확인합니다.
- 검증·정규화·계산·표시를 구분합니다. Formatter로 계산하거나 Normalizer로 검증을 대신하지 않습니다.
- 헬퍼는 재사용하되 서비스별 예외와 잠금·트랜잭션 경계는 임의로 통합하지 않습니다.
- 기존 테스트를 먼저 유지합니다. 리팩터링 후 실패하면 서비스 고유 정책을 바꿨는지 먼저 점검합니다.
- 새 공개 메서드는 경계값 테스트를 추가하고 양쪽 언어 문서의 메서드명·사용 예·주의점을 함께 갱신합니다.
- 기준 테스트: [정규화](../back/src/test/java/com/baedang/global/normalizer/DomainNormalizerTest.java), [서비스별 계약](../back/src/test/java/com/baedang/global/normalizer/DomainNormalizationContractTest.java), [시장 정보](../back/src/test/java/com/baedang/stock/entity/MarketCountryTest.java), [손익률](../back/src/test/java/com/baedang/account/support/ReturnRateCalculatorTest.java), [프론트 헬퍼](../front/src/lib/__tests__).

## 지정가 생애주기 구성요소 (#120)

- LimitOrderRequestPolicy: 지원 입력 통화 및 무손실 가격 자릿수 검증. 반올림 도구가 아닙니다.
- LimitOrderPricing: 주입하여 미국 원화 입력을 고정 USD 센트 지정가로 환산하고 LimitOrderSettlementCalculator로 원본 입력 동결액 계산. 단일 체결 가정 견적은 MarketOrderSettlementCalculator를 재사용하며 부분 체결 정산에는 사용하지 않습니다.
- LimitOrderService: 접수·견적·취소 NEVER 진입점. 외부 정보를 DB 변경 전에 준비합니다. 접수 비활성화는 기존 주문 재생·취소를 차단하지 않습니다.
- LimitOrderTransactionService: REQUIRED 계좌 우선 잠금 접수·종료. 외부 호출·원장 INSERT 없음. 만료 경합 결과를 값으로 반환하여 커밋 후 HTTP 오류로 변환합니다.
- OrderReadService: 읽기 전용 소유권 검증, 현재 회차 주문 커서·주문별 체결 커서. 체결 직후 잔액은 연결 원장에서 조회합니다.
- LimitOrderExpirationService: NEVER 스캔, 외부 시장 호출 없음. 주문별 종료 트랜잭션에 2초 잠금 대기 제한을 적용하고 실패 건은 다음 스캔에서 재시도합니다. LimitOrderExpirationScheduler가 전용 단일 스레드 limitOrderTaskScheduler에서 시작 시 비동기 복구 및 완료 후 30초 간격 실행을 담당합니다.
- Account.reserveCash/releaseCash, Holding.reserveQuantity/releaseQuantity: 동결 상태만 변경. 호출부는 계좌 잠금 및 필요한 보유 잠금을 획득해야 합니다. 보유 메서드는 명시적 UTC 변경 시각을 받습니다.

## 현재가 수집 확대 (#140)

QuoteSnapshotLoadService는 정규장 중 ACTIVE 종목 가운데 랭킹 또는 미만료 LIMIT 주문(PENDING/PARTIALLY_FILLED, quantity > filledQuantity)이 있는 종목만 stockId keyset 200개 페이지로 5초 목표 순회합니다. 별도 플래그 없이 모든 사용자의 주문 존재 여부로 판단합니다. 마지막 활성 주문이 종료된 비랭킹 종목은 다음 대상 조회부터 제외하며 이미 제출된 요청 1회는 완료될 수 있습니다. 랭킹 종목은 유지합니다. 전체 종목 순회는 하지 않으며 거래 허용 범위와 캔들/백필은 별도입니다.

QuoteRefreshCoordinator는 정기·온디맨드 현재가 조회의 진행 중 종목을 병합합니다. 배경 작업은 3개 스레드, 실행기 대기열 없음, 초당 8회 제출 예산을 사용하며 사용자 호출용 별도 1개 슬롯을 둡니다. 최종 MARKET_DATA 합산 15 TPS는 기존 Toss 클라이언트가 재시도·다른 호출까지 포함해 제한합니다. 진행/대기 종목 맵은 기본 1,000개로 제한하고 완료/실패 시 제거합니다. 배경 제출이 거절되면 페이지 커서를 유지합니다. 전용 스케줄러 기본 fixedDelay는 25ms이며 실제 순환 시간에는 작업·용량 대기도 포함됩니다.

거래용 조회는 QuoteRefreshCoordinator를 주입하여 **트랜잭션 밖에서** requireFresh(stock, maxAge)를 호출합니다. 원본 quoteAt이 신선하면 재사용하고 아니면 갱신 후 누락/미래/오래된 시세를 거절합니다. 주문·캔들·세션·환율·거래 상태 조회는 하지 않습니다. OrderMarketDataService가 주문에 연결하고 주문 서비스가 나머지 계약을 검증합니다. refresh(stock)는 화면용 갱신으로 실패 시 기존값 표시 여부는 호출자가 결정합니다. 일봉 백필 정책은 그대로입니다.

QuoteSnapshotPersistenceService는 트랜잭션 밖에서 통화·가격·정규장 시각을 검증한 뒤 짧은 JDBC 트랜잭션으로 시세를 저장한다. 기준가 복구는 새로 조회한 일봉을 별도로 검증하고 시세의 거래소 현지 거래일이 유지될 때만 기준가를 갱신한다. 같은 거래일의 새 시세는 복구를 막지 않는다. 새 거래일에서는 이전 기준가를 폐기하고, 같은 거래일의 검증된 기준가는 보존한다. 역순 응답은 무시한다. V10은 prev_close_date만 추가하며 상하한가는 보존한다.

설정: trading.quote-collection.refresh-interval=5s, dispatch-interval=25ms, background-concurrency=3, background-requests-per-second=8, max-in-flight-stocks=1000, request-timeout=20s. ExternalHttpConfig는 자동 구성 RestClient 빌더에 toss.connect-timeout=2s/read-timeout=5s를 적용해 무응답 I/O가 슬롯을 영구 점유하지 않게 합니다. coordinator 대기 timeout은 전체 큐/HTTP 작업을 합친 총 응답시간 보장이 아닙니다.

지표: quote.collection.batch, quote.collection.sweep.submission(HTTP 완료가 아닌 제출 순회 시간), quote.collection.source.age(실제 응답에서 관측한 시세 나이), quote.collection.inflight, quote.collection.requested, quote.collection.updated, quote.collection.failures. 그룹 전체 사용량은 기존 toss.ratelimiter 지표를 사용합니다. 조회에 성공해도 시세의 원본 시각은 그대로입니다. /prices는 symbol/timestamp/lastPrice/currency만 제공하며 Stock의 거래정지·정리매매 플래그를 갱신하지 않습니다. 상태 정보는 StockTradingStatusService가 별도 종목 메타데이터 API로 확인합니다.

### 주문 시장 데이터 준비와 거래 상태 캐시

- `OrderMarketDataService`: 주문·견적에서 주입하여 사용합니다. `refreshStatus`는 거래 상태를 확인하고 `requireQuote`는 `QuoteRefreshCoordinator.requireFresh`를 호출합니다. `prepareEstimate`는 시세 시간 오류를 기존 실행 불가 견적 사유로 전달합니다. 차트 백필은 하지 않습니다.
- `StockTradingStatusService`: 주문 준비와 호가 스케줄러가 공유합니다. `requireCurrent`(단건), `refreshBatch`(최대 200개)를 제공합니다. SymbolInfoPort 조회를 5분 TTL·최대 1,000종목 인메모리 캐시로 공유하며 갱신 락 대기는 최대 5초입니다. HTTP 실패는 성공으로 캐시하지 않습니다. TTL은 거래소 상태의 실시간 보장이 아닙니다. `trading.stock-status-cache-ttl`로 설정합니다.
- `StockTradingStatusPersistenceService`: 상태 컬럼만 별도 DB 트랜잭션에서 갱신합니다. 랭킹·종목명·경고는 보존합니다. KR은 상장/거래정지/정리매매, US는 상장 상태를 갱신합니다.
- 외부 준비 서비스는 `NEVER`이며 금융 트랜잭션 안에서 호출하지 않습니다. API 경로는 기존 Port/Adapter 및 전역 RateLimiter를 그대로 사용합니다.
- `StockRepository.findQuoteTargets/isQuoteTarget`는 ACTIVE ∩ (랭킹 ∪ 미만료 LIMIT PENDING/PARTIALLY_FILLED, 잔여 수량 > 0)를 모든 사용자 기준으로 조회합니다. 호가 게시 직전에도 대상을 재검증합니다. 체결 워커 마이그레이션에서 이 조건용 종목/만료 부분 인덱스를 추가합니다.

### 지정가 체결 공통 구성요소 (#122)

- `exchangeRateTaskScheduler`: 분 단위 환율 수집 전용 단일 스레드입니다. 공용 시장 배치와 분리하지만 Toss 클라이언트의 공유 TPS 제한은 유지합니다.
- `ExchangeRateRepository.findHistoryBuckets`: SQL에서 KST 자정 기준 버킷별 마지막 원본 행만 선택하며 정밀도와 원본 시각을 보존합니다. 표시 이력 전용이며 체결은 집계하지 않은 최신 행을 사용합니다.

- `ExchangeRateScheduler` / `ExchangeRateLoadService` / `ExchangeRatePersistenceService`: 매분 Toss 환율을 수집·검증하여 DB에 갱신합니다. `valid_from`, `valid_until`, `collected_at`, `rate`, `mid_rate`를 보존하며 같은 시작 시각의 오래된 수신 응답은 최신 관측값을 덮어쓰지 않습니다.
- `ExecutionExchangeRateProviderBridge`: 기본 조회는 캐시 없이 DB 최신 행을 읽습니다. MarketOrderService만 누락/만료 시 금융 트랜잭션 밖에서 `refreshUnavailableForMarketOrder()`를 호출하고 DB를 한 번 재조회합니다. `rate`를 사용하고 원본 유효기간과 미래 수신 시각을 검증하며 누락/만료 환율을 대체 사용하지 않습니다. 금융 트랜잭션은 잠금 후 전달받은 스냅샷을 재검증합니다. `ExchangeRateService`와 계좌 평가는 같은 DB를 쓰되 `midRate` 우선 표시 정책을 유지합니다. 최신/이력 API는 `validFrom`을 제공하며 프론트 최신 환율 폴링은 1분입니다. 정기 수집과 복구는 `ExchangeRateLoadService`의 전용 단일 스레드 exchangeRateRefreshExecutor에서 실행하는 Future를 공유합니다. 최초 호출자도 작업 큐/API 시간을 포함해 trading.exchange-rate-refresh-wait(기본 5초)까지만 기다립니다. 시간 초과로 공유 작업을 취소하지 않으며 늦게 완료된 결과는 후속 요청이 사용합니다. 완료 후 재호출 제한은 5초이며 환율 TTL이 아닙니다. 실패·동일 만료 응답이면 시장가 요청은 SAME_CLIENT_ORDER_ID로 실패하고 기동 수집은 추가하지 않습니다.

- `LimitOrderExecutionPlanner.plan(...)`: 체결과 미리보기에서 공유하는 순수 호가 선택기입니다. 누적 차액은 `LimitOrderSettlementCalculator`를 사용하고 매수 가능 정수 수량은 이진 탐색합니다. DB/HTTP 의존성을 넣지 않습니다.
- `LimitOrderExecutionService.prepare(stockId, side)` / `execute(orderId, selectedPreparation)`: NEVER 경계입니다. 후보 선정 전에 준비하고 선정 당시 버전/환율을 실행에 전달합니다. 실행은 컨텍스트를 새로 준비하며 근거가 바뀌면 PRIORITY_CHANGED를 반환합니다. 같은 버전의 revision/금융 락 충돌만 최대 1회 재시도하며 후순위를 새 버전/환율에서 자동 재시도하지 않습니다.
- `LimitExecutionProgress`: 단일 인스턴스의 종목·방향별 가격 커서를 bookVersion/환율 값에 연결하며 revision은 초기화 키가 아닙니다. `LimitOrderTransactionService`가 발행하는 `LimitOrderAcceptedEvent`의 AFTER_COMMIT 리스너는 실행 중 선정을 무효화하지 않고 대기 알림을 가장 선순위 한 건으로 합칩니다. `advance` 후 다음 `position`에서 갱신된 커서와 알림을 비교해 페이지 토큰을 변경하며 선순위 접수일 때만 처음으로 돌아갑니다. 워커는 접수가 멈추길 기다리지 않고 방문 예산 안에서 계속 진행합니다. 전체 순회 후 관찰되지 않은 그룹의 커서와 대기 알림을 함께 제거합니다. 다중 인스턴스 잠금이나 영속 주문 상태로 사용하지 않습니다.
- `LimitOrderExecutionTransactionService.execute(attempt)`: 계좌 우선 REQUIRED 경계에서 공유 호가 잔량과 모든 금융 상태를 원자적으로 변경합니다. 상위 트랜잭션에서 직접 호출하지 않고 NEVER 오케스트레이터를 사용합니다. `LimitExecutionAttempt`는 기대 체결횟수와 버전/revision을 전달하며 사용자 가격/가변 잔액을 신뢰하는 입력이 아닙니다.
- `LimitOrderPreviewService`: 같은 선택기를 사용하되 쓰기·자원 예약을 하지 않습니다. `LimitExecutionPreviewResponse.avgExecutionPrice`는 표시용 KRW 0 / USD 2자리이며 금액을 역산하는 데 사용하지 않습니다.
- `Account.settleReservedBuy`, `Holding.settleReservedSell`: 자유 예수금/매도 가능 수량이 아니라 이미 동결된 자원을 소비합니다. 호출부가 주문별 한도를 검증하고 같은 금융 트랜잭션을 사용합니다. 전량 매수 후 남은 동결액은 명시적으로 해제합니다.
- `LimitOrderExecutionWorker`: 생성/만료 스케줄러와 분리된 `limitExecutionTaskScheduler`에서 상시 실행합니다. 예산/커서와 미리보기 필드는 `api-spec.ko.md`를 참고합니다.

## KRX 시장조치 — 서킷브레이커·사이드카 (#165)

**이 파이프라인은 거래 가능 여부를 판단한다.** 입력이 외부 사이트의, 공격자가 고를 수 있는 텍스트이므로 경계는 관례가 아니라 계약으로 좁힙니다.

### 수집 경계

- `KindUriPolicy`: 화이트리스트만 허용합니다. RSS 경로는 query 고정, viewer는 `method`와 14자리 `acptNo`, 본문은 `/external/{y}/{m}/{d}/{6}/{14}/{5}.htm` 패턴입니다. 타 호스트·`http`·비표준 포트·user-info·fragment·traversal은 **요청이 나가기 전에** 거절합니다 — 거절된 URI는 I/O가 되지 않습니다.
- `KindHttpClient.getText(uri, maxBytes)`: GET 전용, `Redirect.NEVER`, 1 MiB 초과는 **전체 거절**, UTF-8 고정입니다. 응답 본문과 전체 query 문자열은 로그에 남기지 않습니다.
- 클라이언트는 주입해서 쓰고 호출자 안에서 `RestClient`를 만들지 않습니다. 외부 호출은 DB 트랜잭션 없이 실행하며(`Propagation.NEVER`), 기본값 `krx.market-events.enabled=false`에서는 스케줄러도 클라이언트 빈도 호출도 생기지 않습니다.

### 3홉 파싱, 그리고 왜 3홉인가

1. `KindRssParser` — JDK XML에 DTD·외부 엔티티·외부 DTD/스키마를 모두 끄고 100건 상한을 둡니다. 제목 분류, `acptNo`, `pubDate`로 후보를 만듭니다.
2. `KindViewerParser` — viewer HTML에서 `/external/` 링크가 **정확히 1개**여야 하고, 그 `acptNo`가 viewer 쿼리와 일치해야 합니다.
3. `KindMarketEventDetailParser` — jsoup, 라벨로 찾은 행(행 번호 금지), 엄격한 시각 형식, 그리고 발동시각.

**`pubDate`는 게시 시각이지 발동시각이 아닙니다.** fixture 실측으로 실제 발동보다 19~28초 늦고, `haltUntil`은 `triggeredAt`에서 계산합니다. RSS만으로는 "언제부터 거래가 중단됐나"를 답할 수 없고 상세 공시만이 답할 수 있습니다. 반대로 `acptNo`를 열거할 수 있는 곳은 RSS뿐입니다. 그래서 둘 다 필요하고, KIND 사이트 구조가 강제하는 viewer 홉이 하나 더 붙습니다.

상세 단계에서 제목을 다시 분류하고 명시된 지속시간을 재확인하므로, 자기 공시와 어긋나는 후보는 저장되지 않습니다. 부분 파싱값으로 거래를 막지 않습니다.

### 저장과 스케줄링

- `MarketEventPersistenceService.insert(confirmed, haltUntil)`: 이벤트 하나씩 짧은 REQUIRED 트랜잭션으로 저장합니다. 이미 있으면 `false`를 반환합니다.
- `MarketEventCollectionService.collect()`: 두 시장을 도는 `Propagation.NEVER`입니다. 시장 실패는 그 시장만, 후보 실패는 그 후보만 건너뜁니다. 캘린더 실패는 전송 실패(`stage=fetch`)와 구분해 `stage=calendar`로 태깅합니다 — 전자는 fail-open 위험(3단계 CB의 종료시각을 못 구하면 저장되지 않음)이고 후자는 외부 장애입니다. 실패 시 기존 `halt_until`을 연장하지 않습니다.
- 중복 방지는 사전 확인이 아니라 DB `(source, source_event_id)` UNIQUE입니다. 두 인스턴스가 `existsBySourceAndSourceEventId`를 모두 통과할 수 있고, 두 번째 insert를 막는 것은 제약뿐입니다. `DataIntegrityViolationException`은 그 행이 실제로 존재할 때만 정상 중복으로 분류하고 아니면 재전파합니다 — 그렇지 않으면 잘못된 데이터가 중복으로 숨습니다.
- `MarketEventCollectionScheduler`: `marketEventTaskScheduler`(단일 스레드)에 `fixedDelay` 15초, 그리고 `ApplicationReadyEvent`에서 1회 시작 시도입니다. `MarketSessionProvider.isOpen(KR, now)`일 때만 수집합니다. 세션 조회 실패와 수집 실패를 모두 삼켜 한 주기의 예외가 루프를 멈추지 않게 합니다. 전용 스케줄러를 쓰고 공용 `taskScheduler`를 쓰지 않습니다.
- 지표 `krx.market_event.poll/candidate/persisted/parse_error/delivery_delay`의 태그는 닫힌 열거형만 씁니다(`market`, `type`, `result`, `stage`). `acptNo`·제목·URL은 태그로 쓰지 않습니다.

### 조회와 소비

- `MarketEventQueryService.get(market, date)`: KST 하루를 반개구간 `[from, to)` UTC 범위로 바꿉니다. 100건 상한, `active`는 저장값이 아니라 응답 시각에 계산합니다. 공개 응답 시각은 항상 `+09:00`이고 저장은 UTC입니다.
- `MarketEventController`: `GET /api/market/events?market=&date=`는 공개입니다. 두 파라미터를 문자열로 받아 파싱 실패를 다른 API와 같은 `INVALID_INPUT` + `data.field`로 내보냅니다 — enum/날짜 바인딩이 프레임워크 메시지를 내보내게 두지 않습니다.
- 소비자는 `haltUntil`을 읽고 만료를 기다립니다. RSS 해제 공시나 RSS 상태로 재개를 추론하지 않습니다. 수집 장애가 나도 기존 구간은 그대로 남고 스스로 만료됩니다.
- 사이드카는 프로그램 호가에만 영향을 줍니다 — 일반 주문을 막으면 안 됩니다. 서킷브레이커 주문 차단은 별도 관심사입니다(Part 4/#166).


## 거래일·종가 복구 (#173)

- `MarketTradingDayPolicy`: `calendar(country, date)`, `quoteTradeDate(country, instant)`, `previousTradingDay(country, date)`. 기존 캘린더 Port와 캐시를 재사용한다.
- `DailyCandlePersistenceService.upsert(stockId, currency, country, candles, requestedAt)`: 외부 요청 전 시각으로 확정일을 검사하고 저장한 `List<DailyCandle>`을 반환한다. 기준가 복구는 이 반환값에서 정확한 날짜를 선택한다. 수집·저장은 공통 조정자 안에서 실행한다.
- `PrevCloseUpdateService.update(country)` / `recover(stock)`: 최신 확정 일봉과 정확한 직전 거래일의 종가를 확보한다. 배경 대상은 랭킹·활성 지정가 종목이고 상세 조회는 비랭킹도 복구한다. 장 마감 후 일봉으로 복구한 가격의 `quote_at`은 캘린더 종료 시각이다. 복구가 새 정규장 가격을 덮어쓰지 않는다.
- `trading.reference-recovery.initial-delay=5s`, `interval=1m`: 기동 후 누락 작업을 보충하고 실패를 재시도한다. `referenceRecoveryScheduler` 전용 스레드를 사용한다.
- `MinuteCandlePersistenceService.upsert(stockId, country, candles)`: 봉 시작 시각이 정규장 `[open, close)`에 해당하는 분봉만 저장한다.

`MarketStatusService`도 시장별 현지 날짜를 사용하고 기존 캘린더 Port 캐시를 공유한다. 상태 응답 시각의 KST 표현은 유지한다.

`QuoteSnapshotPersistenceService.repairReference(stock, expected, reference)`는 이번 외부 조회로 검증한 일봉만 받는다. 직전 거래일을 검증하고 quote_at에서 파생한 거래소 현지 거래일이 그대로일 때 기준가만 갱신한다. 같은 거래일의 새 시세는 허용하며 다른 거래일로 넘어가면 차단한다. 현재가·수집 시각은 보존한다. `saveRecoveredClose`는 조회한 행이 그대로일 때만 확정 종가로 교체한다.

`DailyCandleFetchCoordinator.withStockLock(stockId, operation)`을 정기 수집·시드·상세 백필·기준가 복구에서 공유한다. 외부 요청 시작부터 저장 완료까지 같은 종목을 직렬화한다. 락은 단일 애플리케이션 인스턴스 범위이며 다중 인스턴스 수집에는 별도 분산 조정이 필요하다.


### 캘린더 공통 정책과 일봉 완료 판정

- 캘린더를 사용하는 서비스는 `MarketTradingDayPolicy.calendar(country, date)`로 국가별 조회 및 응답 검증을 공유한다. 캐싱 데코레이터도 동일한 `MarketCalendarDay.requireMatching` 검증을 적용한 뒤 저장하므로 국가·날짜가 다른 응답은 캐시되지 않는다. 실패 처리(배치 생략 또는 예외 전달)는 호출부가 유지한다.
- `MarketCalendarDay.isRegularSessionAt(instant)`는 시장 개장 여부·분봉 시작 시각에 사용하는 `[개장, 마감)` 판정이다. `acceptsRegularQuoteAt(instant)`는 마감 시각의 시세까지 허용한다. `isFinalizedAt(requestedAt)`는 마감 후 10분 조건을 공유하며, 실제 일봉 확정에는 외부 요청 시작 시각을 넘긴다.
- 일봉 공통 잠금 안에서 백필 상태를 한 번 확인하고 조회·저장한다. 현재가 전용 잠금을 일봉 경로에서 다시 획득하지 않는다. 현재가와 일봉은 서로 다른 잠금을 유지한다.
- 시드 성공·백필 완료·해당 날짜 갱신 완료·후속 주봉 갱신은 `DailyCandlePersistenceService.upsert`가 반환한 저장 대상 행으로 판단한다. 미확정 일봉만 받은 경우 완료를 기록하지 않고 다음 요청에서 재시도한다. 정기 수집도 반환된 행에 기대 거래일이 있어야 성공이다.
- 날짜 변환은 기존 `MarketCountry.zoneId()`를 사용한다. 새 날짜 변환 서비스·범용 수집기·추가 캐시는 만들지 않는다.
