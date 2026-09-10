# Mock Stock Trading Service

Beginner-focused mock stock trading service: help users understand trading, not just execute it.
**Stack:** Java 21 · Spring Boot 3.5.16 · Next.js 16.3 · PostgreSQL 18/TimescaleDB · Testcontainers
**Java package:** `com.baedang` — do not rename.

## Commands

```bash
cd back && bash gradlew test
cd front && npm test
cd front && npm run dev
cd infra/local && docker compose up -d
```

## Guardrails

- Broker clients whitelist exact methods/paths; callers cannot supply arbitrary paths or TR IDs. **Never call real order, amend, cancel, or account APIs.**
- Toss owns quotes, FX, calendars, candles, and trading inputs. KIS is limited to OAuth plus five approved KR industry/financial GETs; never use it for trading decisions or executions.
- Keep broker credentials/tokens out of source, fixtures, errors, and logs.
- Flyway owns the schema. **NEVER modify, delete, rename, reorder, or reuse the version of a migration already present on the base branch.** Correct earlier schema only with a new, higher-numbered migration.

## Sources of Truth

- User-facing financial vocabulary → `tools/terms.md` (regenerate `front/src/data/wikiTerms.ts` with `python3 tools/wiki/generate_wiki_terms.py`)
- API, settlement calculations, market jobs → `docs/api-spec.md`
- Schema, accounting invariants, locks, account lifecycle → `docs/erd.md`
- Shared clients, errors, services, frontend modules → `docs/shared-components.md`
- UI and polling → `docs/wireframe.md`
- Branches, commits, issues, PRs → `docs/conventions.md`
