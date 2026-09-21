# InvestUp

**주식 초보자를 위한 모의 주식 트레이딩 서비스**

실제 시세를 바탕으로 가상의 자산을 거래합니다. 프로젝트에 설정된 수수료와 세금까지 반영해 **"산 가격에 팔면 본전"이 아니라는 것**을 체감하고, 자신의 투자 성향까지 돌아볼 수 있습니다.

가입하면 기본 모의투자금 5,000만원으로 국내·미국 종목을 검색하고 시장가·지정가 주문을 경험할 수 있습니다. 서비스에 등록된 종목은 랭킹 포함 여부와 관계없이 거래 조건을 충족하면 주문할 수 있습니다.

| 구분 | 기술 |
| --- | --- |
| **백엔드** | Java 21 · Spring Boot 3.5.16 · Spring Security · Spring Data JPA · Flyway |
| **프론트** | Next.js 16.3 · React · TypeScript · 반응형 웹 |
| **DB** | PostgreSQL 18 + TimescaleDB |
| **시세·환율·캘린더** | 토스증권 Open API |
| **산업·재무** | 한국투자증권(KIS) Open API |
| **시장 이벤트** | KRX KIND |
| **테스트** | JUnit 5 · Testcontainers · JaCoCo · Vitest · Playwright |
| **모니터링** | Prometheus · Grafana · Micrometer |

---

## 주요 기능

- **회원·인증** — 회원가입·로그인·로그아웃, JWT 기반 인증과 DB 세션 관리, Refresh Token 회전, 닉네임·비밀번호 수정, 이메일 기반 비밀번호 재설정, 회원 탈퇴
- **종목 검색·상세** — 종목명·영문명·종목 코드 검색, 한글 자모·초성 검색, 현재가·차트·호가·재무 정보 조회, 관심 종목 관리
- **거래** — 시장가 즉시 체결, 실제 시세 기반의 전 사용자 공유 가상 호가를 이용한 지정가 체결·부분 체결, 주문 취소·만료와 수수료·세금 정산
- **계좌·마이페이지** — 보유 종목·평가손익, 주문·체결 내역, 계좌 초기화
- **시세·시장** — 정규장 중 랭킹·활성 지정가 주문 종목의 시세 주기 수집과 요청 시 조회, 환율·시장 캘린더, 서킷브레이커·사이드카 수집
- **차트** — 일봉·분봉을 TimescaleDB 하이퍼테이블로 저장하고 주봉·5분봉·10분봉을 연속 집계
- **종목 랭킹** — 최근 1주 거래대금 기준 국내·미국 각각 상위 100종목, 주간 갱신
- **투자 성향 리포트** — 원가 기반 4축 투자 MBTI(16유형), 라운드 리더보드와 퍼센타일·유형별 비교 (기본: 현재 계좌 개설 4주 후 제공)
- **금융 용어 위키** — 용어·별칭의 부분일치·초성 검색
- **산업·재무 정보** — KIS 기반 국내 개별주 업종·재무 지표 조회
- **운영 모니터링** — Prometheus·Grafana 대시보드, 시세 신선도·외부 API·주문·배치 지표와 알림 규칙

---

## 폴더 구조

```text
.
├── front/    Next.js 화면 및 프론트 단위 테스트
├── back/     Spring Boot API 및 백엔드 테스트
├── e2e/      Playwright 브라우저 통합 테스트
├── infra/    로컬 DB · AWS Terraform · 모니터링 · 부하 테스트
├── tools/    금융 용어 원본 및 생성 도구 등
└── docs/     ERD · 화면 구성 · API 명세 · 인증 · 공용 구성요소 · 컨벤션
```

상세 설계와 정책은 아래 문서에서 확인할 수 있습니다.

| 문서 | 내용 |
| --- | --- |
| [ERD](docs/erd.ko.md) | 테이블 구조 · 컬럼 사전 · 관계 및 제약조건 · 배치 일정 |
| [화면 구성](docs/wireframe.ko.md) | 화면 흐름 및 UI 구성 |
| [API 명세](docs/api-spec.ko.md) | REST 엔드포인트 · 요청 및 응답 |
| [인증 구조](docs/authentication.ko.md) | 인증 세션 · 토큰 · 프론트 인증 중계 |
| [공용 구성요소](docs/shared-components.ko.md) | 전역 설정 · 공용 유틸리티 · 도메인 서비스 · 프론트 모듈 |
| [협업 컨벤션](docs/conventions.md) | 브랜치 · 커밋 · 이슈 · PR 템플릿 |

---

## 로컬 실행

### 준비 사항

- Java 21
- Node.js 20.9 이상과 npm — 브라우저 통합 테스트 환경은 Node.js 22 기준
- Docker Desktop 또는 Docker Compose를 사용할 수 있는 Docker 환경

아래 각 절의 명령은 **저장소 루트에서 시작**합니다.

### 1. 환경변수 설정

백엔드는 루트의 [.env.example](.env.example), 프론트는 [front/.env.example](front/.env.example)을 복사해 설정합니다.

Windows PowerShell:

```powershell
Copy-Item .env.example .env
Copy-Item front/.env.example front/.env.local
```

macOS / Linux:

```bash
cp .env.example .env
cp front/.env.example front/.env.local
```

#### 백엔드

| 변수 | 설정 |
| --- | --- |
| `JWT_SECRET` | 32바이트 이상의 난수를 Base64로 인코딩한 JWT 서명 키 |
| `AUTH_SESSION_ENCRYPTION_KEY` | JWT 키와 별도로 생성한 32바이트 난수의 Base64 값. 세션 암호화에 사용하며 재시작 시에도 유지 |
| `TOSS_ENABLED` | 일반 백엔드 실행 시 `true` |
| `TOSS_CLIENT_ID`, `TOSS_CLIENT_SECRET` | 외부 시세·환율·캘린더 조회에 사용할 토스 API 자격증명 |
| `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` | 기본값은 아래 로컬 DB 설정과 동일 |

**Spring Boot는 `.env`를 자동으로 읽지 않습니다.** IntelliJ의 Environment variables·EnvFile 또는 터미널 환경변수로 주입하세요. 키와 비밀번호를 담은 파일은 커밋하지 않습니다.

선택 기능은 다음 설정을 추가합니다.

| 기능 | 설정 |
| --- | --- |
| KIS 산업·재무 조회 | `KIS_ENABLED=true`, `KIS_APP_KEY`, `KIS_APP_SECRET` |
| KRX 시장 이벤트 수집 | `KRX_MARKET_EVENTS_ENABLED=true` |
| 비밀번호 재설정 메일 | `MAIL_ENABLED=true`, SMTP 접속 정보와 발신자 설정. 재설정 링크 주소는 `FRONTEND_BASE_URL`로 지정 |

환경변수의 실제 적용 이름과 기본값은 [application.yaml](back/src/main/resources/application.yaml)을 기준으로 확인하세요.

#### 프론트

기본 로컬 설정:

```dotenv
NEXT_PUBLIC_API_BASE_URL=http://localhost:8080
AUTH_BACKEND_URL=http://localhost:8080
AUTH_PUBLIC_ORIGIN=http://localhost:3000
```

Next.js는 `front/.env.local`을 자동으로 읽습니다. 인증 중계에 필요한 `AUTH_BACKEND_URL`과 `AUTH_PUBLIC_ORIGIN`을 모두 설정하고, `AUTH_PUBLIC_ORIGIN`은 브라우저 접속 주소와 일치시켜야 합니다.

### 2. DB 실행

```bash
cd infra/local
docker compose up -d
```

PostgreSQL 18과 TimescaleDB가 함께 실행됩니다. 백엔드 기동 시 Flyway가 [마이그레이션 파일](back/src/main/resources/db/migration)을 적용하며, 기존 볼륨의 데이터는 유지됩니다.

백엔드 기동 후 적용 이력을 확인할 수 있습니다.

```bash
docker exec trading-db psql -U trading -d trading -c "SELECT installed_rank, version, description, success FROM flyway_schema_history ORDER BY installed_rank;"
```

각 마이그레이션의 `success`가 `t`이면 정상입니다.

다음 명령은 `infra/local`에서 실행합니다.

| 명령 | 동작 |
| --- | --- |
| `docker compose ps` | 상태 확인 |
| `docker compose logs -f postgres` | DB 로그 확인 |
| `docker compose down` | 중지 및 컨테이너 제거, 데이터 볼륨 유지 |
| `docker compose down -v` | 데이터 볼륨까지 삭제. 폐기 가능한 로컬 DB를 초기화할 때만 사용 |

스키마 변경 시 기존 마이그레이션과 데이터 볼륨을 유지하고 새 마이그레이션을 추가합니다.

### 3. 백엔드 실행 및 최초 데이터 적재

IntelliJ에서 **`back` 폴더**를 열고, 환경변수를 지정한 뒤 `TradingApplication`을 실행합니다.

환경변수를 주입한 터미널에서도 실행할 수 있습니다.

Windows PowerShell:

```powershell
cd back
.\gradlew.bat bootRun
```

macOS / Linux:

```bash
cd back
./gradlew bootRun
```

빈 DB로 시작할 때는 최초 기동 전에 다음 환경변수를 설정해 종목 마스터·상세·랭킹을 순서대로 적재합니다.

```dotenv
TOSS_LOAD_STOCK_MASTER=true
TOSS_LOAD_STOCK_MASTER_DETAIL=true
TOSS_LOAD_STOCK_RANKING=true
```

종목·랭킹 적재를 확인한 뒤 위 세 플래그를 `false`로 되돌립니다.

### 4. 프론트 실행

```bash
cd front
npm ci
npm run dev
```

`http://localhost:3000`으로 접속합니다.

### 5. 모니터링 실행 (선택)

```bash
docker compose -f infra/monitoring/docker-compose.monitoring.yml up -d
```

실행 중인 백엔드와 DB를 관측합니다. 포트가 겹치는 `infra/development` 모니터링 구성과는 동시에 실행하지 않습니다. 상세 설정은 [모니터링 안내](infra/monitoring/README.md)를 참고하세요.

---

## 테스트

### 백엔드 단위·통합 테스트

Windows PowerShell:

```powershell
cd back
.\gradlew.bat test
```

macOS / Linux:

```bash
cd back
./gradlew test
```

Testcontainers 기반 통합 테스트에는 실행 중인 Docker가 필요합니다. 테스트 실행 후 JaCoCo HTML 리포트는 `back/build/reports/jacoco/test/html/index.html`에서 확인할 수 있습니다.

### 프론트 단위 테스트

```bash
cd front
npm test
```

### 브라우저 통합 테스트

Java 21, Node.js 22, Docker 환경에서 실행합니다.

```bash
npm ci --prefix front
npm ci --prefix e2e
cd e2e
npx playwright install chromium
npm run test:smoke
```

전체 시나리오는 `e2e`에서 `npm test`로 실행합니다. 별도 테스트 DB와 외부 데이터 대역을 사용하며, 상세 준비 사항은 [E2E 안내](e2e/README.md)를 참고하세요.

---

## 로컬 접속 정보

| 대상 | 기본 주소 또는 설정 |
| --- | --- |
| 프론트 | `http://localhost:3000` |
| 백엔드 API | `http://localhost:8080` |
| 백엔드 관리 포트 | `http://localhost:8081` |
| DB | `localhost:5432` / DB: `trading` / 사용자: `trading` / 비밀번호: `trading` |
| Grafana | `http://localhost:3001` — 모니터링 스택 실행 시 |
| Prometheus | `http://localhost:9090` — 모니터링 스택 실행 시 |
