# 모의 주식 트레이딩 서비스

투자 초보자가 거래를 체결하는 데서 그치지 않고 이해하도록 돕는 모의투자 서비스.
**기술:** Java 21 · Spring Boot 3.5.16 · Next.js 16.3 · PostgreSQL 18/TimescaleDB · Testcontainers
**Java 패키지:** `com.baedang` — 변경 금지.

## 명령

```bash
cd back && bash gradlew test
cd front && npm test
cd front && npm run dev
cd infra/local && docker compose up -d
```

## 절대 규칙

- 증권사 클라이언트는 정확한 method/path만 허용하고 호출자가 임의 path나 TR ID를 넘길 수 없게 한다. **실제 주문·정정·취소·계좌 API는 절대 호출하지 않는다.**
- Toss는 시세·환율·장 캘린더·캔들·거래 입력을 담당한다. KIS는 OAuth와 승인된 국내 산업·재무 GET 5개로 제한하고 거래 판정·체결에 사용하지 않는다.
- 증권사 credential/token을 소스·fixture·예외·로그에 남기지 않는다.
- 스키마는 Flyway가 관리한다. **기준 브랜치에 이미 존재하는 migration은 수정·삭제·이름 변경·순서 변경·버전 재사용을 절대 하지 않는다.** 이전 스키마를 고칠 때는 반드시 더 높은 번호의 새 migration을 추가한다.

## 구현 전 원본 문서

- 사용자용 금융용어 → `tools/terms.md` (`python3 tools/wiki/generate_wiki_terms.py`로 `front/src/data/wikiTerms.ts` 재생성)
- API·정산 계산·시장 배치 → `docs/api-spec.md`
- 스키마·회계 불변식·잠금·계좌 생명주기 → `docs/erd.md`
- 공용 클라이언트·에러·서비스·프론트 모듈 → `docs/shared-components.md`
- 화면·폴링 → `docs/wireframe.md`
- 브랜치·커밋·이슈·PR → `docs/conventions.md`
