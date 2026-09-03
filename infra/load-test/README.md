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
