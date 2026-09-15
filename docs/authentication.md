# Stateful authentication and Refresh Token Rotation (#203)

English | [한국어](authentication.ko.md)

## Deployment and transport

The existing Vercel frontend, EC2 backend and PostgreSQL remain in use. Next.js relays only
`POST /api/auth/{signup,login,refresh,logout}` and the two explicit
`POST /api/auth/password/{forgot,reset}` routes to the backend. It is not a general API proxy.
Other API calls continue to use `NEXT_PUBLIC_API_BASE_URL` and Bearer Access authentication.
Password reset uses the backend implementation merged from develop. This auth change does not provision Redis, SMTP or infrastructure.

Vercel server environment variables:

| Variable | Meaning |
| --- | --- |
| `AUTH_BACKEND_URL` | HTTPS backend origin exposed through NPMplus; no path, credentials or query |
| `AUTH_PUBLIC_ORIGIN` | Exact frontend origin, e.g. `https://nbe-10-12-final-baedang.vercel.app` |
| `NEXT_PUBLIC_API_BASE_URL` | Existing browser API origin; it also needs HTTPS when called from the HTTPS frontend |

EC2 must additionally set `AUTH_SESSION_ENCRYPTION_KEY` to an independently generated 32-byte
Base64 secret (`openssl rand -base64 32`). Keep it separate from `JWT_SECRET` and outside source control.
Keep both keys stable across application restarts. Replacing the encryption key while grace records
exist makes those retries unreadable; revoke sessions before intentional key replacement.

NPMplus exposes the backend at `https://54.180.217.176.sslip.io`, forwarding internally over HTTP
to `trading-back-1:8080`. Set both backend URL variables above to this HTTPS origin and set
`AUTH_PUBLIC_ORIGIN` to `https://nbe-10-12-final-baedang.vercel.app` before releasing frontend and backend together.
The relay fails closed with `AUTH_UNAVAILABLE` on missing/unsafe configuration. Only non-Vercel runs
whose frontend and backend are both localhost/127.0.0.1 allow HTTP. See `front/.env.example`.
Vercel previews require their own exact allowed public origin.

## Browser and backend contracts

The browser keeps Access in memory only. The `baedang_refresh` cookie is HttpOnly, Secure in
production, SameSite=Lax, Path=/api/auth, host-only, and expires at the session's absolute expiration.
The relay removes Refresh from response JSON. No auth tokens are stored in localStorage or sessionStorage;
legacy `trading-auth-user` is removed on mount. A random localStorage stamp coordinates account changes
and pending logout, without storing tokens or user details.

All six relay routes require an exact Origin match, `X-Auth-Request: 1`, and JSON Content-Type.
Refresh/logout read only the cookie, ignoring any supplied token in the browser body. Upstream requests
use fixed paths, a 10-second timeout, no redirects and no caching. Responses use `Cache-Control: no-store`.
Backend auth endpoints remain JSON APIs for the relay and non-browser clients; backend APIs do not consume
auth cookies and do not create HttpSession. Spring Security's `STATELESS` setting controls HttpSession,
not the PostgreSQL session validation described here.

| Operation | Backend request/response | Browser relay difference |
| --- | --- | --- |
| Signup/login | Credentials → user/account, accessToken, refreshToken, expiresAt | Sets cookie; removes refreshToken |
| Refresh | `{refreshToken}` → accessToken, refreshToken, expiresAt | Body `{}`; reads and replaces cookie |
| Logout | `{refreshToken}` → 200 empty | Body `{}`; deletes cookie and returns 204 |
| Password forgot/reset | Email or reset token/new password → 200 empty | Fixed routes; preserves empty success/errors; does not read or change the Refresh cookie |
| Profile | Bearer Access → `GET /api/users/me` | Direct backend call after refresh on page restoration |

Logout accepts an unexpired, correctly signed Refresh from the same session even if it has rotated;
repeated logout is idempotent. Relay logout treats an already invalid/expired session (401) as complete
and deletes the cookie. Other errors leave the cookie untouched. Refresh errors never delete cookies:
a delayed error must not erase a newer login cookie.

## Session invariants and locking

Access defaults to 15 minutes and the session to 7 days from login. Refresh rotation never extends
the session expiration; Access expiration is capped at the session expiration. JWTs include issuer,
subject, token_type, sid, generation and a random jti. Pre-migration tokens without sid require login again.
Each login creates an independent session. Logout revokes that session; password change, password reset and withdrawal
revoke all user sessions in the same transaction as the account change.

Every authenticated request validates the JWT and queries PostgreSQL for an active session and active
user. There is no session cache. Checks beginning after revocation commits reject old Access tokens;
requests already authenticated are not retroactively cancelled. Database connection/transaction errors
fail authentication with 503 `AUTH_UNAVAILABLE`, not an anonymous or authenticated bypass.

Password-reset issuance locks the user before checking cooldown or invalidating/inserting tokens. Reset first looks up only the owner ID, then locks the user and reset token and rechecks usage/expiry before changing the password and revoking sessions. No token entity is cached before the user lock.

Login locks the user before checking its password. Refresh, logout, password change, password reset and withdrawal use
the same user-first lock order. Refresh then locks the session row. Signup creates user, account,
initial ledger entry and session in one transaction. RTR commits its mutation before returning tokens.
Reuse revocation is committed before throwing the public error, so exception rollback cannot undo it.

## Rotation and bounded retry

`auth_session` stores SHA-256 hashes of the current and immediate previous Refresh tokens. A current
hash/generation match rotates once. For **20 seconds from that rotation**, a match of the immediate
previous hash/generation returns the **same successor Refresh**. It neither rotates again nor extends
the grace deadline. The successor is stored as AES-256-GCM ciphertext with a random nonce and the
session ID as authenticated associated data; the encryption key is outside the database.

At or after the deadline, or for a token two or more generations old, a correctly signed reused token
revokes only that session (`REFRESH_TOKEN_REUSED`). A forged token cannot revoke an arbitrary session.
The current successor still rotates normally. This bounded tolerance reduces accidental logout but
cannot distinguish a legitimate retry from an attacker holding the immediate predecessor within grace.
It does not guarantee recovery from an arbitrarily delayed or lost response.

Cleanup runs hourly: it clears expired grace hashes/ciphertext and deletes sessions whose absolute
expiration is more than 7 days old (including revoked sessions). Ciphertext may physically remain until
the next cleanup; the grace deadline is enforced on every request, independently of cleanup.

## Frontend coordination and errors

Proactive refresh (10 minutes, visible tabs only), TOKEN_EXPIRED recovery and restoration share one
in-flight refresh per tab. Web Locks serialize cookie-changing auth operations across tabs;
BroadcastChannel propagates user/login/logout state, guarded by the shared stamp against stale events.
Use modern browsers with Web Locks and BroadcastChannel for cross-tab guarantees. Without Web Locks,
the fallback only shares requests within a tab, and the 20-second server tolerance does not guarantee
arbitrary concurrent/out-of-order multi-tab recovery.

Authenticated requests retry at most once after refresh, preserving the original body and clientOrderId.
Requests from a previous login are never retried under a new user's Access. Invalid sessions clear memory;
network errors and 5xx retain state for later visible/online or polling recovery. Logout clears memory
immediately and invalidates late responses. If server logout fails, a token-free pending marker causes
the next restoration/login to retry logout before restoring authentication. A full reload otherwise
restores via cookie refresh followed by profile retrieval. Password changes redirect to login with an explanation.

Nickname updates capture the originating session epoch/stamp and discard late success responses after
login/logout or a cross-tab account change. Profile-only events contain userId/email/nickname, never tokens;
AuthProvider applies them only to the matching current user while retaining the latest Access.

| Code | Meaning |
| --- | --- |
| `TOKEN_EXPIRED` | JWT/session expired; Access can trigger one refresh attempt |
| `INVALID_TOKEN` | Invalid signature/type/claims, missing session or inactive user |
| `SESSION_REVOKED` | Session inactive or revoked |
| `REFRESH_TOKEN_REUSED` | Old signed token reused outside the allowed predecessor grace |
| `AUTH_UNAVAILABLE` | Transient database/transport failure or relay configuration unavailable |

## Verification and transition

V18 adds `auth_session` and drops `users.token_version`; existing user, account and trade data are preserved. Deploying this
contract requires one login for existing users and coordinated frontend/backend releases. A backend
rollback to code requiring `token_version` needs a separate schema restoration; rolling back the frontend alone is incompatible with RTR.

Tests cover PostgreSQL rotation concurrency, exact grace/expiration boundaries, revocation persistence,
session isolation, password/withdrawal invalidation, legacy/forged JWT rejection, client refresh sharing,
late responses, transient failures, relay CSRF/cookie transport, and real browser multi-tab restoration.
E2E uses an independent browser login session and a separate API inspection session. The existing
single-worker desktop/mobile trading suite stays enabled. Production HTTPS/cookie delivery must still
be verified after the production environment variables and coordinated deployment are applied.

Browser auth requests have a separate 15-second timeout covering headers and body consumption. A timeout aborts the fetch, releases the Web Lock and clears the shared refresh flight. REQUEST_TIMEOUT is transient: it does not clear authentication or automatically replay token rotation; pending logout stays retryable.
