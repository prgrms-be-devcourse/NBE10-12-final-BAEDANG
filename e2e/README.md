# Browser integration tests (#195)

This independent npm package runs Chromium against the real Next.js production build,
Spring Boot controllers/security/services, and an ephemeral PostgreSQL 18/TimescaleDB.
External market-data ports are replaced, and SMTP delivery is explicitly disabled. No broker credentials are required.

## Requirements and commands

Install Java 21, Node.js 22, and Docker (Docker Desktop on Windows). Set `JAVA_HOME` to Java 21.
From the repository root:

```sh
npm ci --prefix front
npm ci --prefix e2e
cd e2e
npx playwright install chromium
# Linux also needs browser system libraries: npx playwright install --with-deps chromium
npm run test:smoke
npm test
npm run test:ui
npm run typecheck
npm run test:scripts
npm run report
npm run clean
```

`npm test -- --grep "CB"` selects tests; `npm test -- --repeat-each=2` checks repeatability.
`npm test -- --project mobile-chromium` runs the seven responsive flows only;
`npm test -- --project chromium` runs the 38 desktop flows.
All modes build the frontend and E2E Java source set first. Ports 13000, 18088 and 18089
must be free. Existing development servers are never reused. The database port is assigned
by Docker. Do not invoke Playwright directly: the runner supplies a fresh control key.

If startup reports missing dependencies (or `Cannot find module .../@playwright/test/cli.js`),
run `npm ci --prefix e2e` from the repository root. Frontend dependencies are installed
separately with `npm ci --prefix front`. The runner checks both entry points before
creating its runtime state or starting containers and servers.

## Isolation and lifecycle

- Exactly one Playwright worker, `fullyParallel: false`, and no retries or sharding.
- Desktop Chromium runs 38 scenarios. Mobile Chromium uses touch/mobile emulation at
  375×812 and runs only `@responsive` scenarios (seven runs); the full suite totals 45.
  Mobile coverage includes menu navigation, login/logout, ranking-to-detail navigation,
  order confirmation, partial fills, holdings/ledger cards and order cancellation.
  Targeted overflow and viewport assertions cover rankings, detail inputs and account
  cards. This is not real-device, Safari, screenshot-diff or every-breakpoint coverage.
- Each run owns a uniquely labelled container and anonymous storage. Existing local DBs
  are not used. The launcher rejects URLs outside `127.0.0.1:<port>/baedang_e2e`.
- Flyway applies the existing production migrations; no test migration changes the schema.
- Before each test, the prior Spring context is closed and a fresh context is created.
  This resets caches, gates, execution cursors and external-port state without reflection
  into production services. The dedicated database's mutable data is cleared and seeded.
  Identity sequences are intentionally not reset; tests use returned IDs.
- Each test uses a fresh browser context and a separate user. Authentication fixtures
  call the real signup endpoint. UI auth tests exercise the actual login/signup forms.
- The final fixture clears test data and closes the backend. The outer runner stops
  frontend/backend processes and removes only its labelled container and volumes.
- `clean` handles leftovers after interrupted runs. It checks the run identifier against
  Windows process command lines (Linux process environment) and the Docker label before removing resources. It never deletes
  project files or the normal development database. Logs/reports remain for diagnosis.
  Stop an active runner with Ctrl+C before using `clean`: manual cleanup refuses a live
  recorded runner. Automatic cleanup checks its own run ID so an older runner cannot
  clean a replacement run. Lookup/termination failures keep the state file for retry;
  successful cleanup verifies server processes have exited before removing that file.

The E2E Java code lives in `back/src/e2e`, outside `main` and `test`. `bootJar` must not
contain `com/baedang/e2e`. Scheduling registration and startup runners are excluded in this
launcher; production configuration remains unchanged. Collection/execution service methods
are explicitly invoked by the scenario. Synchronous test executors make completion visible.

## Scenario controls and time

The loopback-only control server accepts a run-specific `X-E2E-Key` and a small fixed command
set, not arbitrary SQL or broker URLs. Fixtures call `reset`, `clear`, `publish`, `tick`,
`advance`, `expire`, `limits`, `upper`, `liquidity`, and `halt`.

KR starts at 2026-09-14 10:00 KST; US starts at 2026-09-14 10:00 New York time.
The shared local start date/time is defined only in `E2eClock`. Quote seed dates derive
from that clock separately for each market: KR bounds use the local date, and previous
close dates use `MarketTradingDayPolicy.previousTradingDay` with the scenario calendar.
The scenario calendar currently treats weekdays as open; it does not model real holidays.
Clock, JWT and JPA auditing share the scenario clock. `advance` refreshes quote/FX evidence,
so session-expiry tests are not accidentally testing stale quotes. The database's native
`now()` and browser timers are separate: assertions target application timestamps and
business state, not exact database default timestamps. Browser clock control is used only
for polling tests. The visibility test dispatches browser visibility events explicitly; it
does not claim to test the operating system's tab/background scheduling policy.

All external market ports are deterministic. Candles currently return an empty list and
financial data is disabled; chart rendering and financial-report collection are outside
this package's initial coverage. Recovery scenarios can withhold bounds, then restore DB
evidence. HTTP adapter wire contracts remain covered by backend tests.

## Adding and debugging scenarios

Import `test` from `fixtures/test.ts`, never the unconfigured Playwright test. Use a
`@smoke` title suffix only for core user flows. Use real UI actions and check outcomes via
public authenticated read APIs; use scenario controls only to prepare market conditions
or run scheduled work. Do not reproduce settlement algorithms in TypeScript fixtures.

Prefer role/name/placeholder locators and state-based assertions. Existing `data-tour`
markers are used only where input labels are not associated with their controls. Do not
add sleeps or reuse another test's orders. Network interception is limited to transport
delay/failure tests and retains real backend responses.

Responsive views can render both desktop and mobile content in the DOM. Filter text
locators to visible elements before selecting a match; role locators already exclude
hidden elements by default. Use `openNavigation` before accessing collapsed mobile menu
actions. Add `@responsive` only to flows that need both screen sizes; mobile-only flows
live in `responsive.spec.ts`, which the desktop project excludes.

HTML reports live in `playwright-report`; failure screenshots and traces in `test-results`.
Server logs live in `.runtime`. These files can contain disposable auth state and are
ignored by Git; do not publish traces from sessions using real accounts.

## CI

`.github/workflows/e2e.yml` runs smoke on PR updates to develop/main and the full suite
on pushes to develop/main. Manual runs select smoke or full. A change-detection step
skips unrelated documentation changes while leaving a completed workflow check.
Smoke totals 16 runs (12 desktop + 4 mobile); full totals 45 (38 desktop + 7 mobile).
Both projects run serially with one worker and the same per-test cleanup policy.
Superseded runs of the same PR are cancelled. Cleanup and seven-day diagnostic artifacts
run even after failure. Require a full run on the final merge candidate manually.

Windows local execution is verified during development. Linux CI verification must be
confirmed from the actual workflow run; adding the workflow alone is not evidence it passed.

Once this workflow exists on the repository's default branch, select Actions → E2E →
Run workflow, choose the PR's source branch and `suite: full`. The CLI equivalent is:

```sh
gh workflow run e2e.yml --ref feat/playwright_e2e -f suite=full
```

Replace the branch with the PR's pushed source branch. This tests that branch's HEAD,
not the temporary PR merge commit. Re-running a PR smoke job retains the smoke selection.
GitHub requires the dispatch workflow to exist on the default branch, so the first PR
introducing this file cannot rely on the manual button before that requirement is met.
See [GitHub's manual workflow documentation](https://docs.github.com/en/actions/how-tos/manage-workflow-runs/manually-run-a-workflow).

## Stateful authentication (#203)

Browser setup logs in through the real same-origin auth relay and uses HttpOnly cookies.
API inspection helpers own a separate session and retain both rotated tokens only in test memory.
The runner supplies loopback `AUTH_BACKEND_URL` / `AUTH_PUBLIC_ORIGIN` and a per-run session encryption key.
Actuator uses an ephemeral loopback port, avoiding the ordinary backend's 8081.
Auth coverage includes cookie visibility/CSRF, two-tab restoration and logout, old Access rejection,
password-change revocation, and the existing desktop/mobile reload flow. All 45 cases use one worker.

The authenticated `/restart` control action rebuilds the Spring application with the same DB, keys and Clock instant; it does not clear scenario data. Auth tests use it to verify persisted sessions and encrypted predecessor-grace replay.

## Frontend regression coverage after the develop merge

- `/` always opens `/intro`, including repeat visits. Dedicated tests enter `/main`
  through the start link (desktop/mobile) or Enter (desktop). Other flows navigate
  directly to their target route; the obsolete `iv_intro_seen` bypass is removed.
- Signup and login cover both the default `/main` destination and explicit `/my`.
- Favorites use the ranking heart control, real persistence, account list reload,
  detail navigation, and removal without accidental navigation on both viewports.
- The personality image opens/closes after two real market purchases and the
  actual four-week unlock period, advanced through the existing scenario clock.
- Password reset covers the request acknowledgement, missing/invalid token and
  mismatched password handling. The launcher forces `mail.enabled=false`, regardless
  of inherited deployment configuration. SMTP delivery and successful reset through
  a delivered email link are outside this suite; responses are not fabricated.
