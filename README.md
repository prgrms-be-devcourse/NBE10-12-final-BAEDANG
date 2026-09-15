# InvestUp

**주식 초보자를 위한 모의 주식 트레이딩 서비스**

실제 시세로 거래하되 돈은 가상입니다. 수수료와 세금까지 반영해 **"산 가격에 팔면 본전"이 아니라는 것**을 체감하게 하고, 거래를 실행하는 데서 그치지 않고 자신의 투자 성향까지 돌아보게 하는 것이 목표입니다.

가입하면 모의투자금 5,000만원을 받아 거래대금 상위 100종목(국내·미국)을 사고팔 수 있습니다.

|                |                                                          |
| -------------- | -------------------------------------------------------- |
| **백엔드**     | Java 21 · Spring Boot 3.5.16 · Spring Data JPA · Flyway   |
| **프론트**     | Next.js 16.3 (Node 20.9+) · 반응형 웹                    |
| **DB**         | PostgreSQL 18 + TimescaleDB (시계열 캔들)                |
| **시세·환율**  | 토스증권 Open API                                        |
| **산업·재무**  | 한국투자증권(KIS) Open API                               |
| **테스트**     | JUnit 5 · Testcontainers · JaCoCo                        |
| **모니터링**   | Prometheus · Grafana · Micrometer                        |

---

## 주요 기능

- **회원·인증** — 회원가입 / 로그인·로그아웃, JWT(access·refresh), 닉네임·비밀번호 수정, 회원 탈퇴
- **거래** — 시장가·지정가 주문, 체결, 전 사용자 공유 가상 호가(order book), 수수료·증권거래세 정산
- **계좌·마이페이지** — 보유 종목·평가손익, 주문/체결 내역, 계좌 초기화
- **시세·시장** — 토스 시세 폴링(5초), 환율, 시장 개장 상태·캘린더, 서킷브레이커·사이드카 수집, 일봉·분봉 캔들
- **랭킹** — 거래대금 상위 종목(국내·미국)
- **투자 성향 리포트** — 원가 기반 4축 투자 MBTI(16유형) + 라운드 리더보드(퍼센타일·유형별 비교)
- **금융 용어 위키** — 부분일치·초성 검색
- **산업·재무 정보** — KIS 기반 업종·재무 지표 조회
- **상시 운영 모니터링** — Prometheus/Grafana 대시보드 + 도메인 지표(시세 신선도·외부 API·주문·배치)·알림

---

## 폴더 구조

```
.
├── front/    Next.js 화면 (반응형)
├── back/     Spring Boot API
├── infra/    로컬 Docker(local/) · AWS Terraform(development/) · 모니터링 스택(monitoring/)
└── docs/     설계 문서 (ERD · 와이어프레임 · API 명세 · 공용 구성요소 · 컨벤션)
```

`docs/` 문서는 Markdown 입니다. 영문 원본(`*.md`)과 한국어판(`*.ko.md`)이 함께 있습니다.

| 문서                                                          | 내용                                              |
| ------------------------------------------------------------- | ------------------------------------------------- |
| [`docs/erd.ko.md`](docs/erd.ko.md)                            | 테이블 24개 · 컬럼 사전 · 배치 일정               |
| [`docs/wireframe.ko.md`](docs/wireframe.ko.md)                | 화면 구성                                         |
| [`docs/api-spec.ko.md`](docs/api-spec.ko.md)                  | REST 엔드포인트(40여 개)                          |
| [`docs/shared-components.ko.md`](docs/shared-components.ko.md) | 전역 설정·공용 유틸리티·도메인 서비스·프론트 모듈 |
| [`docs/conventions.md`](docs/conventions.md)                  | 브랜치·커밋·이슈·PR 템플릿                        |

---

## 구동 방식

### 1. DB

Docker Desktop 을 켠 뒤:

```bash
cd infra/local
docker compose up -d
```

PostgreSQL + TimescaleDB 가 빈 상태로 뜹니다.
**PostgreSQL 을 따로 설치하지 않아도 됩니다.**

스키마는 백엔드를 기동할 때 Flyway가
`back/src/main/resources/db/migration/`의 V1, V2, V3… 파일을 순서대로 적용합니다.

기동 후 적용 이력을 확인:

```bash
docker exec -it trading-db psql -U trading -d trading \
  -c "SELECT installed_rank, version, description, success FROM flyway_schema_history ORDER BY installed_rank;"
```

각 migration의 `success`가 `t`이면 정상입니다.

| 명령                              | 동작                                      |
| --------------------------------- | ----------------------------------------- |
| `docker compose ps`               | 상태 확인                                 |
| `docker compose logs -f postgres` | 로그                                      |
| `docker compose down`             | 중지 (데이터 유지)                        |
| `docker compose down -v`          | 중지 + 데이터 삭제 — **폐기 가능한 로컬 DB 초기화에만 사용** |

Flyway가 관리하는 볼륨은 스키마가 바뀌어도 삭제하지 않습니다. 새 migration은 다음 백엔드 기동 때 자동 적용됩니다. Flyway 도입 전에 `schema.sql`로 만든 로컬 볼륨에는 `flyway_schema_history`가 없으므로, 데이터가 불필요하면 한 번만 `down -v` 후 다시 올리세요. 보존할 데이터가 있으면 볼륨을 삭제하지 말고 백업 후 baseline·migration 절차를 적용해야 합니다.

### 2. 백엔드

IntelliJ 에서 **`back` 폴더**를 열고 `TradingApplication` 을 실행합니다.

터미널에서 실행하려면:

```bash
cd back
.\gradlew bootRun        # Windows
./gradlew bootRun        # macOS / Linux
```

`http://localhost:8080` 에서 뜹니다.

동작 확인 (Windows CMD):

```bat
curl -X POST http://localhost:8080/api/auth/signup ^
  -H "Content-Type: application/json" ^
  -d "{\"email\":\"test@example.com\",\"password\":\"password123\",\"nickname\":\"주린이\"}"
```

macOS / Linux:

```bash
curl -X POST http://localhost:8080/api/auth/signup \
  -H 'Content-Type: application/json' \
  -d '{"email":"test@example.com","password":"password123","nickname":"주린이"}'
```

### 3. 프론트

```bash
cd front
npm install
npm run dev
```

`http://localhost:3000` 에서 뜹니다.
아직 생성 전이라면 `front/README.md` 를 보세요.

### 4. 환경변수 (선택)

`application.yaml` 에 기본값이 다 들어 있어 **그냥 실행해도 됩니다.**
토스 API 키가 필요한 수집기 담당자만 `.env.example` 을 복사해 채우세요.

```bash
cp .env.example .env
```

Spring Boot 는 `.env` 를 자동으로 읽지 않으므로,
IntelliJ 실행 구성의 **Environment variables** 에 넣거나 EnvFile 플러그인을 쓰세요.

### 5. 모니터링 (선택)

Prometheus + Grafana 상시 운영 스택을 함께 띄우려면:

```bash
docker compose -f infra/monitoring/docker-compose.monitoring.yml up -d
```

Grafana 는 `http://localhost:3001`, Prometheus 는 `http://localhost:9090` 에서 뜹니다.
자세한 내용은 [`infra/monitoring/README.md`](infra/monitoring/README.md) 를 보세요.

---

## 테스트

```bash
cd back
.\gradlew test           # Windows
./gradlew test           # macOS / Linux
```

Testcontainers 로 PostgreSQL 을 띄워 통합 테스트까지 실행하므로 **Docker 가 실행 중이어야 합니다.**
테스트가 끝나면 JaCoCo 커버리지 리포트가 `back/build/reports/jacoco/test/html/index.html` 에 생성됩니다.

---

## 접속 정보

|         |                                                                  |
| ------- | ---------------------------------------------------------------- |
| 백엔드  | `http://localhost:8080`                                          |
| 프론트  | `http://localhost:3000`                                          |
| DB      | `localhost:5432` / `trading` / `trading` / `trading`             |
| Grafana | `http://localhost:3001` — 모니터링 스택 기동 시                  |
