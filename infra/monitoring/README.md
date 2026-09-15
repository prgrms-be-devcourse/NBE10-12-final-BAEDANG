# 상시 운영 모니터링 (Prometheus + Grafana)

"평소에 서버가 문제없이 돌고 있나"를 보기 위한 상시 모니터링 스택입니다.
부하 테스트 전용 스택(`infra/development`, `infra/load-test`)과는 목적이 다릅니다.

---

## ⚠️ 먼저 읽어주세요 — 도메인 지표는 코드에 붙이기 전까지 비어 있습니다

인프라 지표(HTTP·JVM·HikariCP·PostgreSQL)는 앱만 띄우면 **바로** 나옵니다.
하지만 **도메인 지표**(시세 신선도, 외부 API 성공·실패, 주문 처리 시간, 일 배치 성공)는
`TradingMetrics` 를 **앱 코드에서 실제로 호출해야** 시계열이 생깁니다.

호출하기 전까지는:

- 대시보드 `01 · Service Health` 의 **도메인 패널 3개**가 "No data"
- 알림 `QuoteStale` · `ExternalApiFailureRate` · `DailyBatchNotRun` · `DailyCandleBatchNotRun` 이
  **울리지 않음** (오탐이 아니라 "관측할 시계열 자체가 없음")

`TradingMetrics` 컴포넌트와 이 스택은 준비돼 있으니, 아래 "TradingMetrics 붙이기"를 보고
스케줄러·외부 클라이언트·주문 서비스에 호출을 심어주세요. 별도 이슈로 분리해도 됩니다.

> **또 하나의 한계** — `DailyBatchNotRun` 은 `time() - 마지막_성공_시각` 으로 판정하는데,
> 앱 재시작 후 그 배치가 **한 번도 성공한 적이 없으면** gauge 시계열 자체가 없어 **침묵**합니다.
> 바로 그 "멈춤"이 놓치고 싶지 않은 상황인데도요. job 이름이 동적이라 `absent()` 로 깔끔히
> 막기 어렵습니다. 기동 시 각 배치의 gauge 를 과거 시각으로 시드하면 이 구멍이 닫힙니다.

---

## 구성 요소

| 서비스 | 이미지(고정) | 포트 | 역할 |
|---|---|---|---|
| Prometheus | `prom/prometheus:v3.1.0` | 9090 | 지표 수집·저장(30일/5GB)·알림 평가 |
| Grafana | `grafana/grafana:11.4.0` | **3001** | 대시보드 (admin/admin) |
| postgres-exporter | `prometheuscommunity/postgres-exporter:v0.16.0` | 9187(내부) | 호스트 PostgreSQL 관측 |

- Grafana 는 **3001**입니다. 기본 3000 은 Next.js 개발 서버와 겹칩니다.
- 앱·DB 는 이 스택에 없습니다. 앱은 IntelliJ 로 호스트에서(관리 포트 8081), DB 는
  별도 compose 로 뜨고, 이 스택은 `host.docker.internal` 로 바깥에서 관측만 합니다.
- 이미지 태그는 전부 고정입니다(`latest` 금지). 팀원마다 버전이 갈리는 걸 막기 위함입니다.

> **⚠️ `infra/development` 스택과 동시에 띄우지 마세요.** 둘 다 Grafana 를 3001 로 노출합니다.
> 상시 모니터링을 볼 때는 이 스택만, 부하 테스트를 볼 때는 그 스택만 띄웁니다.

---

## 기동 순서

1. **DB 를 먼저 띄웁니다** (별도 compose).
   ```bash
   cd infra/local && docker compose up -d      # 또는 infra/development
   ```
2. **앱을 IntelliJ 에서 실행합니다.** 관리 포트 8081 에 `/actuator/prometheus` 가 열려야 합니다.
   ```bash
   curl http://localhost:8081/actuator/prometheus | head
   ```
3. **모니터링 스택을 띄웁니다.**
   ```bash
   cd infra/monitoring && docker compose -f docker-compose.monitoring.yml up -d
   ```
   DB 접속 비밀번호가 기본값(`trading`)이 아니면 환경변수로 넘깁니다.
   ```bash
   DB_USERNAME=trading DB_PASSWORD=... DB_NAME=trading \
     docker compose -f docker-compose.monitoring.yml up -d
   ```

### 확인 체크리스트

- [ ] **Targets 가 전부 UP** — http://localhost:9090/targets
      `spring-boot`, `postgres`, `prometheus` 세 job 이 모두 `UP` 이어야 합니다.
      (`spring-boot` 이 DOWN 이면 앱이 안 떴거나 8081 이 안 열린 것)
- [ ] **알림 규칙 로드됨** — http://localhost:9090/rules 에 5개 그룹이 보입니다.
- [ ] **Grafana 접속** — http://localhost:3001 (admin/admin) → 대시보드 2개가 자동 등록됨.
- [ ] **패널에 값이 나옴** — `02 · JVM Runtime` 은 앱만 떠 있으면 바로 값이 보여야 합니다.

규칙 파일을 고친 뒤에는 재시작 없이 리로드할 수 있습니다(`--web.enable-lifecycle`):
```bash
curl -X POST http://localhost:9090/-/reload
```

---

## 대시보드 — 언제 이 화면을 여는가

### `01 · Service Health`
- **언제**: "지금 서비스가 사용자에게 정상인가"를 볼 때. 장애 신고가 들어왔을 때 가장 먼저.
- 상단 stat 로 up/가동시간/RPS/5xx율/p95 를 한눈에 보고, 아래에서 엔드포인트별 지연·상태코드,
  그리고 **도메인**(시세 신선도·외부 API·주문 처리)까지 내려갑니다.
- CPU·메모리가 초록인데 사용자가 "안 돼요" 하면 → **도메인 패널**부터 보세요. 시세가 멈췄을 수 있습니다.

### `02 · JVM Runtime & Connection Pool`
- **언제**: 앱이 "느리다/불안정하다"의 원인을 자원에서 찾을 때.
- 힙·GC·CPU·스레드로 앱 프로세스 상태를, HikariCP 섹션으로 커넥션풀(상한 10)의
  active/idle/**pending**/timeout 을 봅니다. p95 가 튀는데 원인을 모를 때 여기 pending·GC 를 보세요.

---

## TradingMetrics 붙이기

`com.baedang.global.metrics.TradingMetrics` 를 주입받아 아래처럼 호출합니다.
Lombok 없이 생성자 주입, 예외는 삼키지 않습니다.

```java
private final TradingMetrics metrics;

public QuoteSnapshotScheduler(TradingMetrics metrics /* ... */) {
    this.metrics = metrics;
}

// ① 시세 갱신 — !! "수집 제출"이 아니라 실제 시세 "저장 성공" 시점에 호출한다.
//    QuoteRefreshCoordinator.fetch() 에서 persist 성공(updated>0) 직후에 부른다. 스케줄러의
//    syncQuotes()>0 은 비동기 제출을 뜻할 뿐이라, 거기서 부르면 조회/저장이 계속 실패해도
//    freshness 가 초기화돼 정상처럼 보인다(false green).
metrics.quoteUpdated("KR");     // → trading_quote_staleness_seconds{market="KR"}

// ①-b 시장 개장 여부 — 매 폴링 tick 마다(개장/휴장 모두, QuoteSnapshotScheduler). QuoteStale 알림이
//     staleness 와 and on(market) 로 조인해 "개장 중인 시장"만 보게 해, 휴장(야간·주말) 오탐을 막는다.
metrics.marketOpen("KR", session.open());  // → trading_market_open{market="KR"} (1=개장,0=휴장)

// ② 외부 API 호출 — Supplier 로 감싸면 성공/실패·소요시간이 자동 기록됨(예외는 그대로 전파)
QuoteResponse res = metrics.recordExternalCall("toss.quote", () -> tossClient.getQuote(code));
//                  → trading_external_api_seconds{api="toss.quote", outcome="SUCCESS|FAILURE"}

// ③ 주문 처리 — 시작에서 Sample 을 받고, 끝에서 결과 태그와 함께 멈춤
//    !! 이 코드베이스는 업무 거절(STALE_QUOTE·CB 정지 등)을 BusinessException 으로 던진다.
//       그래서 catch(RuntimeException)→"ERROR" 로 뭉뚱그리면 정상 거절이 전부 ERROR 로 오분류된다.
//       거절(BusinessException)=REJECTED, 그 밖의 런타임 오류=ERROR 로 나눈다.
Timer.Sample sample = metrics.startOrderTimer();
String result = "SUCCESS";
try {
    return orderService.execute(cmd);
} catch (BusinessException e) {
    result = "REJECTED";
    throw e;
} catch (RuntimeException e) {
    result = "ERROR";
    throw e;
} finally {
    metrics.stopOrderTimer(sample, result);   // → trading_order_execution_seconds{result="SUCCESS|REJECTED|ERROR"}
}

// ④ 배치 성공 — 배치가 정상적으로 끝났을 때 (마지막 성공 시각을 기록)
metrics.batchSucceeded("leaderboard-snapshot");   // → trading_batch_last_success_timestamp_seconds{job_name="leaderboard-snapshot"}
// ④-b 시장별로 실패 지점이 나뉜 배치(KR/US 일봉)는 market 태그로 시계열을 분리한다 —
//     한 시장의 성공이 다른 시장의 장애를 가리지 않도록. (시장별 알림 게이팅은 후속 이슈)
metrics.batchSucceeded("daily-candle", "KR");  // → trading_batch_last_success_timestamp_seconds{job_name="daily-candle", market="KR"}
```

붙일 만한 지점(예): 시세 저장 성공(`QuoteRefreshCoordinator.fetch`)→①, 개장 상태(`QuoteSnapshotScheduler`)→①-b,
`TossSecuritiesClient`/`KisSecuritiesClient`→②, 주문 실행 서비스(`place`)→③,
`DailyCandleCollectionScheduler`·`LeaderboardSnapshotScheduler` 등 배치→④.

---

## 자주 겪는 문제

**대시보드 전체가 "No data"**
- Prometheus targets 확인(http://localhost:9090/targets). `spring-boot` 이 DOWN 이면
  앱이 안 떴거나 8081 이 안 열린 것. Linux 호스트면 compose 의 `extra_hosts`(host-gateway) 필요.
- postgres 패널만 No data → exporter 가 DB 에 못 닿음. `DB_PASSWORD` 환경변수, DB 기동 여부,
  exporter 컨테이너의 `extra_hosts` 확인.

**p95 패널만 빈 화면 (`histogram_quantile` 이 값을 안 냄)**
- `_bucket` 메트릭이 없어서입니다. `application.yaml` 의
  `management.metrics.distribution.percentiles-histogram` 에 해당 메트릭이 `true` 인지 확인하세요.
  도메인 Timer 는 `TradingMetrics` 에서 `.publishPercentileHistogram()` 도 필요합니다(둘 다 되어 있음).

**모든 패널이 "Datasource not found"**
- 대시보드 JSON 의 `datasource.uid` 와 데이터소스의 `uid` 가 어긋난 것입니다.
  이 스택은 둘 다 **`PROM`** 으로 고정했습니다. UI 에서 데이터소스를 새로 만들지 말고,
  `datasources.yml` 의 `uid: PROM` 을 유지하세요.

**도메인 패널만 No data**
- 정상입니다(맨 위 경고 참고). `TradingMetrics` 를 앱 코드에 호출하기 전까지는 비어 있습니다.
