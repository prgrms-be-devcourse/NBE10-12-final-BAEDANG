# 모의 주식 트레이딩 서비스

주식 초보자를 위한 교육형 모의투자 서비스입니다.
실제 시세로 거래하되 돈은 가상이고, 수수료와 세금까지 반영해 **"산 가격에 팔면 본전"이 아니라는 것**을 체감하게 하는 것이 목표입니다.

가입하면 모의투자금 5,000만원을 받아 거래대금 상위 100종목(국내·미국)을 사고팔 수 있습니다.

|            |                                                |
| ---------- | ---------------------------------------------- |
| **백엔드** | Java 21 · Spring Boot 3.5.16 · Spring Data JPA |
| **프론트** | Next.js 16.3 (Node 20.9+)                      |
| **DB**     | PostgreSQL 18 + TimescaleDB                    |
| **시세**   | 토스증권 Open API                              |

---

## 폴더 구조

```
.
├── front/    Next.js 화면
├── back/     Spring Boot API
├── infra/    DB 스키마 · Docker
└── docs/     설계 문서 (ERD · 와이어프레임 · API 명세)
```

`docs/` 안의 HTML 은 브라우저로 바로 열면 됩니다.

| 문서                  | 내용                                |
| --------------------- | ----------------------------------- |
| `docs/erd.html`       | 테이블 12개 · 컬럼 사전 · 배치 일정 |
| `docs/wireframe.html` | 화면 6개                            |
| `docs/api-spec.html`  | 엔드포인트 17개                     |

---

## 구동 방식

### 1. DB

Docker Desktop 을 켠 뒤:

```bash
cd infra
docker compose up -d
```

PostgreSQL + TimescaleDB 가 뜨고 `schema.sql` · `timescale.sql` 이 자동 실행됩니다.
**PostgreSQL 을 따로 설치하지 않아도 됩니다.**

스키마까지 들어갔는지 확인:

```bash
docker exec -it trading-db psql -U trading -d trading -c "\dt"
```

테이블 14개가 나오면 정상입니다.

| 명령                              | 동작                                      |
| --------------------------------- | ----------------------------------------- |
| `docker compose ps`               | 상태 확인                                 |
| `docker compose logs -f postgres` | 로그                                      |
| `docker compose down`             | 중지 (데이터 유지)                        |
| `docker compose down -v`          | 중지 + 데이터 삭제 — **스키마 바꿨을 때** |

스키마를 수정했다면 `down -v` 후 다시 올려야 반영됩니다.

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

`application.yml` 에 기본값이 다 들어 있어 **그냥 실행해도 됩니다.**
토스 API 키가 필요한 수집기 담당자만 `.env.example` 을 복사해 채우세요.

```bash
cp .env.example .env
```

Spring Boot 는 `.env` 를 자동으로 읽지 않으므로,
IntelliJ 실행 구성의 **Environment variables** 에 넣거나 EnvFile 플러그인을 쓰세요.

---

## 접속 정보

|         |                                                                  |
| ------- | ---------------------------------------------------------------- |
| 백엔드  | `http://localhost:8080`                                          |
| 프론트  | `http://localhost:3000`                                          |
| DB      | `localhost:5432` / `trading` / `trading` / `trading`             |
| pgAdmin | `http://localhost:8081` — `docker compose --profile tools up -d` |
