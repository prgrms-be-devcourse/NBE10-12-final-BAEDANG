# 부하 테스트 (k6 × Grafana 상관 분석) — 이슈 #70

k6로 부하를 주고, **클라이언트 지표(RPS·p95·에러율)** 와 **서버 지표(JVM·GC·HikariCP·HTTP)** 를
하나의 Grafana 대시보드에서 **같은 시간축으로 상관 분석**한다.

- 대상: `GET /api/stocks/rankings` · `GET /api/stocks/{symbol}` · `GET /api/market/status`
- 스택: 기존 **`infra/development`의 Prometheus/Grafana를 재사용**(옵션 a). k6가 remote-write로 push.

## 구성 (누가 무엇을)

> **앱(측정 대상)은 이 스택에 포함되지 않는다.** `infra/development/docker-compose.yml`은
> postgres/prometheus/grafana만 띄운다. 앱은 **별도로**(호스트에서 직접, 또는 network
> `common`의 `back` 컨테이너로) 미리 기동되어 있어야 하고, 아래 실행 전제(§선행)를 따른다.

| 파일 | 역할 |
|---|---|
| `infra/development/docker-compose.yml` | Prometheus `--web.enable-remote-write-receiver` + Grafana provisioning 볼륨 |
| `infra/development/prometheus.yml` | 앱 scrape (15s) — `host.docker.internal:8081`(호스트 앱) 또는 `back_1:8081`(컨테이너 앱) |
| `infra/development/grafana/provisioning/` | 데이터소스(Prometheus) + 대시보드 provider |
| `back/.../application.yaml` | `management.metrics.distribution.percentiles-histogram` — 서버측 p95용 |
| `infra/load-test/scenarios/` | k6 시나리오(`main.js`) + 헬퍼(`lib/`) |
| `infra/load-test/grafana/dashboards/loadtest-correlation.json` | 상관 대시보드(provisioning으로 자동 로드) |

## 선행 전제 (설계 §0-0 — 반드시 확인)

1. **앱이 `TOSS_ENABLED=true` + 시드(#84) 웜업 DB로 기동**되어 있어야 한다.
   랭킹 종목이 DB/캐시에서 응답해야 부하 지표가 앱 성능을 반영하고, 라이브 Toss를 안 친다.
2. **cron 수집기(마스터·랭킹·prev-close·정기 일봉)와 겹치지 않는 시간대**에 실행한다.
   `QuoteSnapshotScheduler`(5s)·분봉(매분)은 프로덕션 유사성 위해 켜둔 채로 둔다(배경 부하로 수용).
3. 심볼은 rankings 응답에서 **파생**한다(하드코딩 금지). marketCountry는 요청한 `market`에서 얻는다.

## 워밍업 적재 (최초 1회)

테스트 대상(rankings=DB · detail=시세스냅샷+일봉 · status=캐시)이 **라이브 Toss를 거의 안 치도록**
DB를 미리 채운다. cron 시각(월 07:00 등)을 기다리지 말고 **수동 트리거 플래그**로 당겨 적재한다.
적재는 본인 토스 키로 실제 호출하므로 **쿼터를 소모**한다(일회성). 적재 데이터는 `pgdata` 볼륨에 남는다.

**전제**: 로컬 DB 기동(`infra/local` 또는 `infra/development`), `.env`에 `TOSS_ENABLED=true` +
`TOSS_CLIENT_ID`/`TOSS_CLIENT_SECRET`(본인 키).

```bash
# 1) 로드 플래그를 켜고 1회 기동 → 종목 마스터(~8500) + 랭킹 상위100(KR+US) 적재
#    (마스터는 STOCK_ALL 1 TPS 등으로 throttle 되어 수 분 소요될 수 있음)
TOSS_ENABLED=true \
TOSS_LOAD_STOCK_MASTER=true \
TOSS_LOAD_STOCK_RANKING=true \
SEED_DAILY_CANDLES=true \
  ./gradlew -p back bootRun

# 2) 앱이 뜬 뒤 일봉 200일 시드(#84) 관리자 트리거
curl -X POST http://localhost:8080/internal/admin/seed/daily-candles

# 3) 적재 완료 확인 후 앱 종료 → 로드 플래그를 모두 끄고(TOSS_ENABLED=true 만) 재기동해 테스트
```

- ⚠️ **시세·분봉은 미리 적재 불가** — `isOpen` 게이트라 **KR 장중에만** 채워진다. 테스트를 장중에 돌리면
  `QuoteSnapshotScheduler`(5s)가 자연히 채우고, 미충족 종목은 상세 조회 시 on-demand로 채워진다.
- ⚠️ **적재는 자원 제한 없이** 하라(대량 적재에 힙 1GB는 빡빡). 자원 제한(§자원 제한 실행)은 **테스트 런에만** 건다.

## 자원 제한 실행 (선택 — 실서버 근사)

개발서버는 EC2 `t4g.micro`(2 vCPU ARM, RAM ~1GB + swap 4GB). 로컬 절대 수치를 여기에 근접시키려면 앱 자원을 제한한다.
목표 ②(토스 rate limit 여유)엔 불필요하고, ①③(레이턴시·DB풀 한계)의 실서버 근사에만 의미가 있다.

- **방법 A (호스트, 쉬움)** — 힙만 제한. 메모리/GC 압박은 근사, CPU는 로컬 그대로.
  ```powershell
  $env:JAVA_TOOL_OPTIONS="-Xmx768m"; ./gradlew -p back bootRun
  ```
- **방법 B (컨테이너, 완전)** — CPU·메모리 둘 다 제한. `back/Dockerfile`로 빌드해 `back_1`로 띄우면
  `prometheus.yml`의 `back_1:8081` 타깃이 살아난다(그땐 k6 `BASE_URL=http://back_1:8080/api`).
  ```bash
  docker build -t baedang-back:local back
  docker run --rm --name back_1 --network common --cpus=2 --memory=1g --memory-swap=5g \
    -e JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75" \
    -e TOSS_ENABLED=true -e TOSS_CLIENT_ID=... -e TOSS_CLIENT_SECRET=... \
    -e DB_URL=jdbc:postgresql://trading-db:5432/trading -e DB_USERNAME=trading -e DB_PASSWORD=trading \
    -p 8080:8080 -p 8081:8081 baedang-back:local
  ```
  (DB 컨테이너 `trading-db`도 같은 network `common`에 있어야 함.)

> **재현 못 하는 것**: t4g.micro는 버스터블(CPU 크레딧)이라 지속 부하 시 baseline으로 스로틀되는 절벽이 있고,
> swap이 EBS(네트워크 디스크)라 스왑 지연이 크다. 로컬은 이 둘을 흉내 못 내므로 **capacity 절대치는 여전히 낙관적**이다.
> 진짜 절대 수치는 동급 인스턴스에서 측정한다.

## 실행

### 1) 모니터링 스택 기동

```bash
cd infra/development && docker compose up -d prometheus grafana
```

- Grafana: http://localhost:3001 (기본 admin/admin) → 대시보드 폴더 **"Load Test"**
- Prometheus는 호스트 포트를 노출하지 않는다. k6는 network `common` 안에서 `trading-prometheus:9090`으로 push.

### 2) k6 실행 (remote-write로 push)

network `common`에 붙여 앱과 Prometheus(`trading-prometheus`)에 접근한다.
**기본 토폴로지 = 앱은 호스트, k6는 컨테이너**. k6 컨테이너가 호스트 앱에 닿도록
`--add-host`로 `host.docker.internal`을 매핑한다(Docker Desktop은 자동이지만 명시해도 무방).

```bash
# 전체 4시나리오 순차(smoke→load→stress→spike, ~13분)
docker run --rm --network common --add-host host.docker.internal:host-gateway \
  -v "$(pwd)/infra/load-test/scenarios:/scripts" \
  -e K6_PROMETHEUS_RW_SERVER_URL=http://trading-prometheus:9090/api/v1/write \
  -e K6_PROMETHEUS_RW_TREND_STATS="p(95),p(99),avg,max" \
  -e BASE_URL=http://host.docker.internal:8080/api \
  grafana/k6 run -o experimental-prometheus-rw \
  --tag testid="run-$(date +%s)" /scripts/main.js
```

PowerShell:

```powershell
docker run --rm --network common --add-host host.docker.internal:host-gateway `
  -v "${PWD}/infra/load-test/scenarios:/scripts" `
  -e K6_PROMETHEUS_RW_SERVER_URL=http://trading-prometheus:9090/api/v1/write `
  -e K6_PROMETHEUS_RW_TREND_STATS="p(95),p(99),avg,max" `
  -e BASE_URL=http://host.docker.internal:8080/api `
  grafana/k6 run -o experimental-prometheus-rw `
  --tag testid="run-$([int](Get-Date -UFormat %s))" /scripts/main.js
```

> 앱을 network `common`의 **`back` 컨테이너**로 배포했다면 `BASE_URL=http://back_1:8080/api`로 덮어쓴다.
> 이 경우 `--add-host`는 불필요하다.

### 3) 단일 시나리오 / 강도 조절 (env)

| env | 기본 | 설명 |
|---|---|---|
| `RUN_SCENARIO` | (전체) | `smoke`\|`load`\|`stress`\|`spike` 하나만 실행 |
| `LOAD_VUS` / `STRESS_VUS` / `SPIKE_VUS` | 50 / 300 / 400 | 시나리오별 VU 목표 |
| `MARKET_KR_WEIGHT` | 0.7 | KR/US 비중 |
| `RANKINGS_PAGES` | 5 | 심볼 워밍업 커서 페이지 수 |
| `BASE_URL` | `http://back_1:8080/api` | 앱 API 베이스 |

예: `-e RUN_SCENARIO=load -e LOAD_VUS=80`

## US 세션 런 (야간 KST — 배경부하를 US 기준으로)

KR 대신 **미국 장중**에 돌리면 `QuoteSnapshotScheduler`(5s)·분봉이 **US 유니버스** 기준으로 실동작한다
(스케줄러는 KR·US를 각각 `isOpen` 게이팅). KR 종목은 장 마감이라 DB의 마지막 종가로 고정 서빙되지만
rankings/detail은 그대로 동작한다(라이브 Toss 안 침).

**시간대**: US 정규장 ≈ **22:30~05:00 KST**(EDT 기준, DST로 1시간 이동 — 하드코딩 말 것).
- 회피할 cron: **월 21:00 KST**(KR 랭킹수집, US 개장 직전) · **~05:10~06:10 KST**(US 일봉수집, US 마감 후).
- 깨끗한 구간 ≈ **23:30~04:30 KST**.

**절차** (워밍 DB는 이미 영속 — 재적재 불필요):
```bash
# 1) US 개장 확인
curl http://localhost:8080/api/market/status        # markets[US].open == true 확인

# 2) 앱 기동 (KR 런과 동일 — 워밍 DB, 필요 시 -Xmx768m 메모리 제한)
#    US 랭킹을 그 시점 기준으로 갱신하고 싶으면 TOSS_LOAD_STOCK_RANKING=true 로 1회 기동(선택)

# 3) k6 실행 — US 비중을 높여 라이브 배경과 정합
docker run --rm --network common --add-host host.docker.internal:host-gateway \
  -v "$(pwd)/infra/load-test/scenarios:/scripts" \
  -e K6_PROMETHEUS_RW_SERVER_URL=http://trading-prometheus:9090/api/v1/write \
  -e K6_PROMETHEUS_RW_TREND_STATS="p(95),p(99),avg,max" \
  -e BASE_URL=http://host.docker.internal:8080/api \
  -e MARKET_KR_WEIGHT=0.2 \
  grafana/k6 run -o experimental-prometheus-rw \
  --tag testid="us-$(date +%s)" /scripts/main.js
```

- **데이터 준비 상태(2026-09-03 기준)**: US 랭킹 종목 일봉 ~52종목 워밍됨(KR ~87). 부하 테스트엔 충분하나,
  더 넓은 US 커버리지를 원하면 US 장중에 `TOSS_LOAD_STOCK_RANKING=true` 로 랭킹을 갱신 후 일봉 시드를 재호출한다.
- 나머지(모니터링 스택·대시보드·$testid 필터·자원 제한)는 KR 런과 **동일**하다.

## 시나리오 & threshold (설계 §4)

| 시나리오 | executor | 목적 | gate |
|---|---|---|---|
| smoke | constant-vus 1 | 경로·응답 sanity | 하드(p95<300, err<1%) |
| load | ramping-vus | SLA 검증 | 하드(p95<500, err<1%) |
| stress | ramping-vus 한계까지 | 브레이킹 포인트 | **없음**(육안 판독) |
| spike | ramping-vus 급증→급감 | 회복력 | **없음** |

stress/spike는 의도적으로 한계를 넘기므로 threshold gate가 없고 `abortOnFail`도 안 쓴다.
pass/fail은 대시보드에서 "어느 RPS에서 p95가 꺾이고 에러가 튀는지"로 판독한다.

## ⚠️ 메트릭 이름 확정 필요 (설계 §3-2)

대시보드 PromQL의 메트릭 이름은 **실제 출력과 대조해 확정**해야 한다(버전에 따라 접미사·라벨 상이):

- k6: `k6_http_reqs_total`, `k6_http_req_duration_p95/p99`, `k6_http_req_failed_rate`, `k6_vus`
  → 실제 이름은 `http://localhost:9090` (임시 포트포워드) 또는 Grafana Explore에서 `k6_` 검색으로 확인.
- 서버(micrometer): `http_server_requests_seconds_bucket`, `jvm_memory_used_bytes`,
  `jvm_gc_pause_seconds_*`, `hikaricp_connections_active/pending/max`
  → `/actuator/prometheus`(관리포트 8081) 출력으로 확인. `_bucket`이 있어야 서버측 p95가 그려진다.

## 리포트는 후속 (설계 §7)

부하 프로필·병목·개선점 리포트는 위 스택 가동 + 첫 실측 후 별도 태스크로 남긴다(조용히 드롭 금지).
