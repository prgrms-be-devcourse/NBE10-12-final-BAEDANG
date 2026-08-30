# 모의 주식 트레이딩 서비스

주린이(투자 초보자)를 위한 토스증권 Open API 기반 모의 주식 트레이딩 서비스.
"거래를 체결시키는 도구"가 아니라 **"거래를 이해시키는 도구"**.

- MVP 기간: 2026-08-20 ~ 08-25 · AGILE(주차별 스프린트) · GitFlow(Main←Develop←Feature)

## Build / Test

```bash
cd back && ./gradlew test      # Java 21 · Spring Boot · PostgreSQL 18
cd front && npm run dev        # Next.js 16.3
cd infra/local && docker compose up -d        # 로컬 PG + Redis
```

## 프로젝트 구조

```text
back/                                  # Spring Boot 백엔드
front/                                 # Next.js 프론트엔드 (예정)
infra/local/                           # 로컬 DB 인프라 (Docker Compose)
infra/production/                      # 개발 서버 AWS 인프라 (Terraform)
docs/                                  # 설계 문서
```

기존 Java 패키지는 `com.baedang`을 사용한다. 명시적인 요청 없이 변경하지 않는다.

## Rules — 위반 시 프로젝트가 망가지거나 실주문 위험

- `QuoteClient`**는 호출 가능 경로를 화이트리스트로 고정.** `POST /orders` **등 주문 API는 절대 호출 금지 (실주문 위험).**
- 금액·수량은 `NUMERIC`/`BigDecimal`, API 응답 금액은 **문자열**. 시각은 `TIMESTAMPTZ`(UTC 저장, 표시만 변환).
- `ledger_entry`는 append-only — UPDATE/DELETE 금지, 잘못 기록 시 반대 부호 항목으로 상쇄.
- 주문·잔고 트랜잭션은 `account` 행 `FOR UPDATE` 잠금부터 시작 (동시 주문 이중 차감 방지).
- **주문가능금액 =** `cash_balance − locked_cash` / **매도가능수량 =** `quantity − locked_quantity`. 동결액은 `gross_amount`가 아니라 **수수료·세금 포함** `net_amount` 로 잠근다.
- 포트폴리오 초기화는 삭제가 아님 — `CLOSED` + `round_no+1` 새 계좌 개설.
- 미국 정규장 시각 하드코딩 금지 — `/market-calendar` 캐시로 판정 (서머타임 1시간 이동).
- 에러는 `BusinessException` 1개 + 에러 코드 표 방식, 프론트에 코드·사용자용 메시지 함께 내려줌.

## Reference — 구현 시 `docs/`를 열어 확인

- 엔드포인트·응답 형식 → `docs/api-spec.md`
- 테이블·컬럼·배치 일정 → `docs/erd.md`
- 화면·폴링 주기 → `docs/wireframe.md`
- 브랜치/커밋/이슈/PR 컨벤션 → `docs/conventions.md` (브랜치/커밋/이슈/PR 를 만들때 반드시 사용)
