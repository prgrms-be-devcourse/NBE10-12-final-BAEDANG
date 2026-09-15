/**
 * 백엔드 API 클라이언트. `back/src/main/java/com/baedang/auth/*`, `.../trading/*` 에
 * 구현된 회원가입·로그인·주문 API를 그대로 호출합니다.
 *
 * <p>백엔드가 stateless JWT 인증을 쓴다 — 로그인/회원가입 응답에 `accessToken`/
 * `refreshToken`이 실려 오고, 이후 보호된 요청(`/api/accounts/**`, `/api/orders/**`,
 * `/api/users/**`)은 `Authorization: Bearer <accessToken>` 헤더로 사용자를 식별한다
 * (구 `X-User-Id` 헤더 방식은 백엔드가 더 이상 받지 않는다 — `SecurityConfig`가
 * 이 경로들을 전부 `authenticated()`로 요구해서, 헤더 없이 부르면 401이 난다).
 *
 * <p>토큰은 이 모듈이 `tokenStore`에 들고 있다가 `auth: true`인 요청에 자동으로
 * 실어 보낸다 — 매 호출부가 토큰을 직접 들고 다닐 필요가 없다. `AuthProvider`가
 * 로그인/로그아웃/새로고침 시점마다 {@link syncAuthTokens}로 이 저장소를 동기화한다.
 */

const API_BASE_URL = process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080";

export type AuthUser = {
  userId: number;
  email: string;
  nickname: string;
  accessToken: string;
  refreshToken: string;
};

type TokenStore = { accessToken: string | null; refreshToken: string | null };
let tokenStore: TokenStore = { accessToken: null, refreshToken: null };

/** AuthProvider가 로그인/로그아웃/localStorage 복원 시점마다 호출해 토큰 저장소를 맞춘다. */
export function syncAuthTokens(tokens: { accessToken: string; refreshToken: string } | null) {
  tokenStore = tokens ? { ...tokens } : { accessToken: null, refreshToken: null };
}

let onAccessTokenRefreshed: ((accessToken: string) => void) | null = null;
let onAuthExpired: (() => void) | null = null;

/**
 * `request()`가 만료된 accessToken을 조용히 재발급했을 때(`onAccessTokenRefreshed`)와,
 * refreshToken마저 만료·무효라 재발급 자체가 실패했을 때(`onAuthExpired`) 알림받을
 * 콜백을 등록한다. `AuthProvider`가 각각 localStorage 갱신·강제 로그아웃 처리를 한다.
 */
export function setAuthEventListeners(listeners: {
  onAccessTokenRefreshed?: (accessToken: string) => void;
  onAuthExpired?: () => void;
}) {
  onAccessTokenRefreshed = listeners.onAccessTokenRefreshed ?? null;
  onAuthExpired = listeners.onAuthExpired ?? null;
}

/**
 * 백엔드 `ErrorResponse`(code/message/data)를 그대로 감싼 에러.
 *
 * <p>`data`는 상황에 따라 모양이 다릅니다 — 회원가입 검증 실패면 `{필드명: 에러메시지}`,
 * 주문 실패면 `{retryPolicy: "SAME_CLIENT_ORDER_ID" | ...}` 식입니다. `fieldErrors`/
 * `retryPolicy` getter로 그때그때 필요한 모양으로 꺼내 씁니다.
 */
export class ApiError extends Error {
  code: string;
  data?: Record<string, unknown>;

  constructor(code: string, message: string, data?: Record<string, unknown>) {
    super(message);
    this.code = code;
    this.data = data;
  }

  /** INVALID_INPUT일 때만 의미 있는 `{필드명: 에러메시지}` 형태 (GlobalExceptionHandler 참고). */
  get fieldErrors(): Record<string, string> | undefined {
    if (this.code !== "INVALID_INPUT" || !this.data) return undefined;
    return this.data as Record<string, string>;
  }

  /**
   * 주문 계열 API의 INVALID_INPUT은 회원가입과 계약이 다르다 — `{필드명: 메시지}` 맵이
   * 아니라 `{"field": "limitPrice"}`처럼 잘못된 파라미터 이름 하나만 문자열로 싣는다
   * (docs/api-spec.md의 LIMIT order lifecycle 설명 참고). 메시지 자체는 이 필드 값과
   * 무관하게 공용 문구(`message`)를 그대로 쓴다.
   */
  get invalidField(): string | undefined {
    if (this.code !== "INVALID_INPUT") return undefined;
    const value = this.data?.field;
    return typeof value === "string" ? value : undefined;
  }

  /**
   * 주문 실패 응답에 실리는 재시도 정책. 없으면(정책 정보 없이 실패한 경우) `undefined`.
   * `back/src/main/java/com/baedang/trading/model/ClientOrderRetryPolicy.java`와 값이 같다.
   */
  get retryPolicy(): "SAME_CLIENT_ORDER_ID" | "NEW_CLIENT_ORDER_ID" | "NOT_RETRYABLE" | undefined {
    const value = this.data?.retryPolicy;
    return value === "SAME_CLIENT_ORDER_ID" || value === "NEW_CLIENT_ORDER_ID" || value === "NOT_RETRYABLE"
      ? value
      : undefined;
  }
}

type RequestInput = {
  signal?: AbortSignal;
  method: "GET" | "POST" | "PATCH" | "PUT" | "DELETE";
  headers?: Record<string, string>;
  body?: unknown;
  /** true면 tokenStore의 accessToken을 Authorization 헤더로 실어 보낸다. 토큰이 없으면 에러. */
  auth?: boolean;
  /**
   * 공개(비로그인도 호출 가능) API인데, 로그인돼 있으면 그 사용자 맞춤 부가 정보를
   * 함께 받고 싶을 때 쓴다(예: 랭킹 응답의 `stockLikeId` — 로그인 시에만 채워진다).
   * 토큰이 있으면 실어 보내되 없어도 에러 내지 않고 그냥 헤더 없이 보낸다.
   */
  authOptional?: boolean;
};

async function fetchOnce<T>(path: string, init: RequestInput): Promise<T> {
  const headers: Record<string, string> = { "Content-Type": "application/json", ...init.headers };
  if (init.auth) {
    if (!tokenStore.accessToken) {
      throw new ApiError("UNAUTHENTICATED", "로그인이 필요해요.");
    }
    headers.Authorization = `Bearer ${tokenStore.accessToken}`;
  } else if (init.authOptional && tokenStore.accessToken) {
    headers.Authorization = `Bearer ${tokenStore.accessToken}`;
  }

  let res: Response;
  try {
    res = await fetch(`${API_BASE_URL}${path}`, {
      signal: init.signal,
      method: init.method,
      headers,
      body: init.body !== undefined ? JSON.stringify(init.body) : undefined,
    });
  } catch {
    // 백엔드가 안 떠 있거나 CORS 등으로 요청 자체가 안 나간 경우.
    throw new ApiError(
      "NETWORK_ERROR",
      "서버에 연결할 수 없어요. 백엔드가 실행 중인지 확인해주세요."
    );
  }

  const json = await res.json().catch(() => null);

  if (!res.ok) {
    const code = json?.code ?? "UNKNOWN_ERROR";
    const message = json?.message ?? "요청을 처리하지 못했어요.";
    const data = (json?.data as Record<string, unknown> | undefined) ?? undefined;
    throw new ApiError(code, message, data);
  }

  return json as T;
}

/**
 * accessToken 수명은 15분(`JWT_ACCESS_TTL`)이라, 오래 켜둔 탭에서는 만료된 채로
 * 요청이 나갈 수 있다 — `AuthProvider`가 주기적으로 조용히 재발급하지만(선제적
 * 갱신), 탭이 오래 백그라운드에 있다 돌아온 직후처럼 그 주기를 놓치는 경우의
 * 안전망으로, 요청이 TOKEN_EXPIRED로 실패하면 여기서 한 번 더 재발급 후 재시도한다.
 * refreshToken마저 무효하면(만료·탈퇴 등) 재로그인이 필요하므로 `onAuthExpired`로
 * 알리고, 사용자에게는 원래의 만료 에러를 그대로 보여준다.
 *
 * <p>`authOptional`도 같은 재발급 로직을 탄다 — `JwtAuthenticationFilter`는 공개
 * 엔드포인트라도 Authorization 헤더가 실려 있으면 검사하므로(백엔드 참고), 만료된
 * 토큰을 실어 보내면 공개 API도 TOKEN_EXPIRED로 실패한다. 다만 재발급마저 실패하면
 * `auth: true`처럼 에러를 던지지 않고, 헤더 없이(로그인 안 한 것처럼) 한 번 더
 * 조용히 재시도한다 — 애초에 로그인 없이도 되는 화면이라 부가 정보만 못 받을 뿐
 * 화면 자체를 에러로 덮을 이유가 없다.
 */
async function request<T>(path: string, init: RequestInput): Promise<T> {
  try {
    return await fetchOnce<T>(path, init);
  } catch (err) {
    const sentAuthHeader = init.auth || (init.authOptional && !!tokenStore.accessToken);
    if (sentAuthHeader && err instanceof ApiError && err.code === "TOKEN_EXPIRED" && tokenStore.refreshToken) {
      try {
        const refreshed = await fetchOnce<{ accessToken: string }>("/api/auth/refresh", {
          method: "POST",
          body: { refreshToken: tokenStore.refreshToken },
        });
        tokenStore = { ...tokenStore, accessToken: refreshed.accessToken };
        onAccessTokenRefreshed?.(refreshed.accessToken);
      } catch {
        tokenStore = { accessToken: null, refreshToken: null };
        onAuthExpired?.();
        if (init.auth) throw err;
        return await fetchOnce<T>(path, { ...init, authOptional: false });
      }
      return await fetchOnce<T>(path, init);
    }
    throw err;
  }
}

export function signUp(input: { email: string; password: string; nickname: string }): Promise<AuthUser> {
  return request<AuthUser>("/api/auth/signup", { method: "POST", body: input });
}

export function login(input: { email: string; password: string }): Promise<AuthUser> {
  return request<AuthUser>("/api/auth/login", { method: "POST", body: input });
}

/**
 * `POST /api/auth/password/forgot` — 비밀번호 찾기 메일 발송 요청.
 *
 * <p>가입 여부와 무관하게 항상 200을 반환한다(계정 열거 공격 방지) — 존재하지
 * 않는 이메일을 넣어도 이 호출은 그냥 성공한다. 실제 메일 발송은 백엔드가 ACTIVE
 * 회원일 때만 한다(`docs/api-spec.md` 참고).
 */
export function requestPasswordReset(email: string): Promise<void> {
  return request<void>("/api/auth/password/forgot", { method: "POST", body: { email } });
}

/**
 * `POST /api/auth/password/reset` — 이메일 링크의 토큰으로 새 비밀번호를 확정한다.
 *
 * <p>토큰이 없거나 이미 쓰였으면 `PASSWORD_RESET_TOKEN_INVALID`, 유효 시간(기본
 * 30분)이 지났으면 `PASSWORD_RESET_TOKEN_EXPIRED`가 난다 — `reset-password` 화면이
 * 이 둘을 구분해서 "다시 요청해주세요" 안내로 보여준다.
 */
export function confirmPasswordReset(input: { token: string; newPassword: string }): Promise<void> {
  return request<void>("/api/auth/password/reset", { method: "POST", body: input });
}

/**
 * `POST /api/auth/refresh` — refreshToken으로 새 accessToken을 받는다.
 * `AuthProvider`가 만료 전에 미리(선제적으로) 호출해 세션을 유지하는 용도다 —
 * `request()` 내부의 재시도용 재발급과는 별개의, 명시적으로 호출하는 경로다.
 */
export function refreshAccessToken(refreshToken: string): Promise<{ accessToken: string }> {
  return request<{ accessToken: string }>("/api/auth/refresh", { method: "POST", body: { refreshToken } });
}

/**
 * `POST /api/auth/logout` — 프론트-백엔드 연동 점검 중 발견된 미연동 API. 백엔드는
 * stateless JWT라 이 호출 자체가 토큰을 실제로 무효화하진 않는다(`docs/api-spec.md`:
 * "Stateless logout. The client discards local tokens." — 클라이언트가 로컬 토큰을
 * 지우는 것 자체가 로그아웃의 본체). 그래도 서버가 로그아웃 이벤트를 감사 로그로
 * 남기거나, 나중에 토큰 블록리스트가 추가될 가능성에 대비해 문서화된 계약대로
 * 호출은 해준다 — `AuthProvider.logout()`이 로컬 상태를 지우기 전에 best-effort로
 * 부른다(실패해도 로컬 로그아웃 자체는 항상 성공해야 하므로 에러를 던지지 않는다).
 */
export function logoutUser(): Promise<void> {
  return request<void>("/api/auth/logout", { method: "POST", auth: true });
}

// ── 회원 정보 ──────────────────────────────────────────────────────────────────

export type UserProfile = {
  userId: number;
  email: string;
  nickname: string;
};

/** `GET /api/users/me` — 내 회원 정보. */
export function getMe(): Promise<UserProfile> {
  return request<UserProfile>("/api/users/me", { method: "GET", auth: true });
}

/** `PATCH /api/users/me` — 닉네임 변경. 중복이면 `NICKNAME_DUPLICATED`. */
export function updateNickname(nickname: string): Promise<UserProfile> {
  return request<UserProfile>("/api/users/me", { method: "PATCH", auth: true, body: { nickname } });
}

/** `PUT /api/users/me/password` — 비밀번호 변경. 현재 비밀번호가 틀리면 `INVALID_PASSWORD`. */
export function changeUserPassword(currentPassword: string, newPassword: string): Promise<UserProfile> {
  return request<UserProfile>("/api/users/me/password", {
    method: "PUT",
    auth: true,
    body: { currentPassword, newPassword },
  });
}

/**
 * `DELETE /api/users/me` — 회원 탈퇴. 계정을 지우지 않고 상태만 WITHDRAWN·CLOSED로
 * 바꾼다(`docs/erd.md`). 백엔드가 토큰을 무효화하진 않으니(stateless JWT), 성공하면
 * 호출부가 반드시 로컬 로그인 상태를 지워야 한다 — 안 지우면 이미 탈퇴한 계정으로
 * 계속 요청을 보내다 USER_NOT_FOUND류 에러만 반복해서 보게 된다.
 */
export function withdrawAccount(currentPassword: string): Promise<void> {
  return request<void>("/api/users/me", { method: "DELETE", auth: true, body: { currentPassword } });
}

// ── 계좌 ──────────────────────────────────────────────────────────────────────

export type AccountSummary = {
  accountId: number;
  roundNo: number;
  initialCash: string;
  cashBalance: string;
  stockValue: string;
  totalAsset: string;
  unrealizedPnl: string;
  unrealizedPnlRate?: string;
  exchangeRate?: string;
  asOf: string;
};

/** `GET /api/accounts/me` — 로그인 사용자의 현재 활성 계좌 요약. */
export function getAccountSummary(): Promise<AccountSummary> {
  return request<AccountSummary>("/api/accounts/me", { method: "GET", auth: true });
}

// ── 주문 ──────────────────────────────────────────────────────────────────────

export type MarketOrderRequest = {
  accountId: number;
  clientOrderId: string;
  symbol: string;
  marketCountry: "KR" | "US";
  side: "BUY" | "SELL";
  quantity: string;
};

export type MarketOrderResponse = {
  orderId: number;
  status: string;
  symbol: string;
  marketCountry: "KR" | "US";
  side: string;
  quantity: string;
  executedPrice: string;
  exchangeRate: string;
  grossAmount: string;
  fee: string;
  tax: string;
  netAmount: string;
  quoteAt: string;
  orderedAt: string;
  account: { cashBalanceAfter: string };
};

/**
 * `POST /api/orders/market` — 시장가 매수/매도. 로그인한 사용자만 호출 가능(accessToken 필요).
 * 지정가 주문은 `POST /api/orders/limit`(별도 엔드포인트)을 사용한다.
 */
export function placeMarketOrder(input: MarketOrderRequest): Promise<MarketOrderResponse> {
  return request<MarketOrderResponse>("/api/orders/market", { method: "POST", auth: true, body: input });
}

export type OrderSide = "BUY" | "SELL";
export type OrderStatus = "PENDING" | "PARTIALLY_FILLED" | "FILLED" | "REJECTED" | "CANCELED" | "EXPIRED";
export type OrderType = "MARKET" | "LIMIT";

/** `GET /api/orders/quote/market` — 시장가 주문 수수료·세금 미리보기. */
export function getMarketOrderQuote(params: {
  symbol: string;
  marketCountry: MarketCountry;
  side: OrderSide;
  quantity: string;
}): Promise<MarketOrderQuoteResponse> {
  const query = new URLSearchParams(params);
  return request<MarketOrderQuoteResponse>(`/api/orders/quote/market?${query.toString()}`, { method: "GET", auth: true });
}

export type MarketOrderQuoteResponse = {
  symbol: string;
  marketCountry: MarketCountry;
  side: OrderSide;
  quantity: string;
  executedPrice: string;
  exchangeRate: string;
  grossAmount: string;
  fee: string;
  tax: string;
  netAmount: string;
  availableCash: string;
  quoteAt: string;
  executable: boolean;
  reason: string | null;
};

export type LimitOrderRequest = {
  accountId: number;
  clientOrderId: string;
  symbol: string;
  marketCountry: MarketCountry;
  side: OrderSide;
  quantity: string;
  /** KR은 KRW 정수만, US는 KRW 정수 또는 USD 센트 단위 허용(limitCurrency로 구분). */
  limitPrice: string;
  limitCurrency: "KRW" | "USD";
};

export type LimitExecutionPreviewStatus = "AVAILABLE" | "UNAVAILABLE" | "NOT_APPLICABLE";

export type LimitExecutionPreview = {
  status: LimitExecutionPreviewStatus;
  reason: string;
  bookVersion: number | null;
  revision: number | null;
  quoteAt: string | null;
  generatedAt: string | null;
  evaluatedAt: string;
  expectedFilledQuantity: string | null;
  remainingQuantity: string | null;
  avgExecutionPrice: string | null;
  grossAmountKrw: string | null;
  feeKrw: string | null;
  taxKrw: string | null;
  netAmountKrw: string | null;
  remainingReservedCash: string | null;
  releasedCash: string | null;
};

export type LimitOrderQuoteResponse = {
  requestedLimitPrice: string;
  requestedLimitCurrency: "KRW" | "USD";
  /** 백엔드가 확정한 종목 통화 지정가(US는 KRW 입력을 접수 시점 환율로 환산한 USD 고정값). */
  limitPrice: string;
  acceptanceExchangeRate: string;
  acceptable: boolean;
  /** 접수 불가 사유 코드(ErrorCode 이름, 예: MARKET_CLOSED). 접수 가능하면 null. */
  reason: string | null;
  availableCash: string;
  availableQuantity: string;
  expiresAt: string;
  limitEstimate: {
    grossAmount: string;
    fee: string;
    tax: string;
    netAmount: string;
    reservedCash: string;
  };
  executionPreview: LimitExecutionPreview;
};

/** `GET /api/orders/quote/limit` — 지정가 주문 접수 가능 여부·예상 예약금 미리보기. */
export function getLimitOrderQuote(params: {
  symbol: string;
  marketCountry: MarketCountry;
  side: OrderSide;
  quantity: string;
  limitPrice: string;
  limitCurrency: "KRW" | "USD";
}): Promise<LimitOrderQuoteResponse> {
  const query = new URLSearchParams(params);
  return request<LimitOrderQuoteResponse>(`/api/orders/quote/limit?${query.toString()}`, { method: "GET", auth: true });
}

/** `POST /api/orders/limit` — 지정가 매수/매도 접수. 성공 시 PENDING(또는 즉시 REJECTED) 주문을 돌려준다. */
export function placeLimitOrder(input: LimitOrderRequest): Promise<OrderDetailResponse> {
  return request<OrderDetailResponse>("/api/orders/limit", { method: "POST", auth: true, body: input });
}

/** `GET /api/orders/{orderId}` — 주문 상세. 종료된 회차의 주문도 조회 가능(본인 것만). */
export function getOrderDetail(orderId: number): Promise<OrderDetailResponse> {
  return request<OrderDetailResponse>(`/api/orders/${orderId}`, { method: "GET", auth: true });
}

export type OrderDetailResponse = {
  orderId: number;
  accountId: number;
  stockId: number;
  symbol: string;
  name: string;
  marketCountry: MarketCountry;
  orderType: OrderType;
  side: OrderSide;
  status: OrderStatus;
  quantity: string;
  filledQuantity: string;
  /** 미체결로 남아 취소 가능한 잔여 수량. 종료된 주문은 0. */
  activeRemainingQuantity: string;
  requestedLimitPrice: string;
  requestedLimitCurrency: "KRW" | "USD";
  limitPrice: string;
  acceptanceExchangeRate: string;
  reservedCash: string;
  grossAmount: string;
  fee: string;
  tax: string;
  netAmount: string;
  rejectReason: string | null;
  orderedAt: string;
  expiresAt: string | null;
  closedAt: string | null;
};

export type ExecutionResponse = {
  executionId: number;
  sequenceNo: number;
  quantity: string;
  price: string;
  exchangeRate: string;
  grossAmount: string;
  fee: string;
  tax: string;
  netAmount: string;
  balanceAfter: string;
  executedAt: string;
};

export type OrderExecutionsResponse = {
  orderId: number;
  stock: { symbol: string; name: string; marketCountry: MarketCountry };
  items: ExecutionResponse[];
  nextCursor: string | null;
  hasNext: boolean;
};

/** `GET /api/orders/{orderId}/executions` — 주문 1건의 체결(부분 체결 포함) 내역, sequenceNo 오름차순. */
export function getOrderExecutions(orderId: number, params?: { cursor?: string; size?: number }): Promise<OrderExecutionsResponse> {
  const query = new URLSearchParams();
  if (params?.cursor) query.set("cursor", params.cursor);
  if (params?.size) query.set("size", String(params.size));
  const qs = query.toString();
  return request<OrderExecutionsResponse>(`/api/orders/${orderId}/executions${qs ? `?${qs}` : ""}`, { method: "GET", auth: true });
}

export type OrderPageResponse = {
  items: OrderDetailResponse[];
  nextCursor: string | null;
  hasNext: boolean;
};

/** `GET /api/accounts/me/orders` — 현재 활성 회차의 주문 내역(커서 페이지네이션). */
export function getMyOrders(params?: { cursor?: string; size?: number }): Promise<OrderPageResponse> {
  const query = new URLSearchParams();
  if (params?.cursor) query.set("cursor", params.cursor);
  if (params?.size) query.set("size", String(params.size));
  const qs = query.toString();
  return request<OrderPageResponse>(`/api/accounts/me/orders${qs ? `?${qs}` : ""}`, { method: "GET", auth: true });
}

/**
 * `PATCH /api/orders/{orderId}` — 미체결(PENDING/PARTIALLY_FILLED) 지정가 주문 취소.
 * 요청 바디는 반드시 `{"status":"CANCELED"}` 그대로여야 한다(다른 필드/값은 INVALID_INPUT).
 * 이미 CANCELED인 주문에 다시 호출해도 성공(멱등)하지만, FILLED/REJECTED/EXPIRED처럼
 * 종료된 주문에 호출하면 409 ORDER_STATE_CONFLICT다.
 */
export function cancelOrder(orderId: number): Promise<OrderDetailResponse> {
  return request<OrderDetailResponse>(`/api/orders/${orderId}`, { method: "PATCH", auth: true, body: { status: "CANCELED" } });
}

// ── 종목 ──────────────────────────────────────────────────────────────────────

export type MarketCountry = "KR" | "US";
export type StockCategory = "INDIVIDUAL" | "PREFERRED" | "ETF" | "ETN";

export type StockDetail = {
  symbol: string;
  name: string;
  englishName: string;
  market: string;
  marketCountry: MarketCountry;
  currency: string;
  isinCode: string;
  category: StockCategory;
  leverageFactor: string | null;
  isDividend: boolean | null;
  price: {
    lastPrice: string;
    prevClose: string;
    changeAmount: string;
    changeRate: string;
    upperLimit: string | null;
    lowerLimit: string | null;
    quoteAt: string;
    realtime: boolean;
  };
  info: {
    marketCap: string;
    sharesOutstanding: string;
    listDate: string | null;
  };
  warnings: { type: string; label: string }[];
  /**
   * 유의사항 조회 성공 여부. `UNAVAILABLE`은 "유의사항 없음"이 아니라 "확인 실패"다 —
   * 화면이 배지를 조용히 감추지 않도록 구분한다.
   */
  warningsStatus: "AVAILABLE" | "UNAVAILABLE";
  tradable: boolean;
  tradableReason: string | null;
};

/** `GET /api/stocks/{symbol}` — 종목 상세. `marketCountry`는 필수(같은 심볼이 시장별로 존재할 수 있음). */
export function getStockDetail(symbol: string, marketCountry: MarketCountry): Promise<StockDetail> {
  return request<StockDetail>(
    `/api/stocks/${encodeURIComponent(symbol)}?marketCountry=${encodeURIComponent(marketCountry)}`,
    { method: "GET" }
  );
}

// ── 재무제표 ──────────────────────────────────────────────────────────────────

export type StockFinancialClassification = { code: string; name: string };

export type StockFinancialPeriod = {
  statementYearMonth: string;
  balanceSheet: {
    currentAssets: string;
    fixedAssets: string;
    totalAssets: string;
    currentLiabilities: string;
    fixedLiabilities: string;
    totalLiabilities: string;
    capitalStock: string;
    capitalSurplus: string;
    retainedEarnings: string;
    totalEquity: string;
  };
  incomeStatement: {
    sales: string;
    operatingProfit: string;
    netIncome: string;
  };
  ratios: {
    salesGrowthRate: string | null;
    operatingProfitGrowthRate: string | null;
    netIncomeGrowthRate: string | null;
    roe: string | null;
    eps: string | null;
    salesPerShare: string | null;
    bps: string | null;
    reserveRatio: string | null;
    debtRatio: string | null;
    netProfitMargin: string | null;
    operatingProfitMargin: string | null;
  };
};

/**
 * `GET /api/stocks/{symbol}/financials` 응답 — 국내 종목 업종 분류·재무제표. `GET
 * /api/stocks/{symbol}`과 달리 KIS 외부 호출을 하므로 별도 엔드포인트로 분리돼 있다.
 *
 * <p>US 종목·ETF/ETN·6자리가 아닌 국내 심볼은 `FINANCIALS_NOT_SUPPORTED`(422)로
 * 아예 이 정보가 없다 — 호출부가 그 에러를 "이 종목엔 탭을 보여주지 않음"으로 다룬다.
 */
export type StockFinancials = {
  symbol: string;
  marketCountry: MarketCountry;
  dataStatus: "FRESH" | "STALE";
  industry: {
    standard: StockFinancialClassification;
    large: StockFinancialClassification;
    medium: StockFinancialClassification;
    small: StockFinancialClassification;
  } | null;
  valuation: {
    calculatedPer: string | null;
    basis: "LATEST_ANNUAL_EPS";
  };
  annual: StockFinancialPeriod[];
  quarterly: StockFinancialPeriod[];
  syncedAt: {
    industry: string | null;
    annual: string | null;
    quarterly: string | null;
  };
};

/** `GET /api/stocks/{symbol}/financials?marketCountry=KR` — 업종 분류 + 연간/분기 재무제표. */
export function getStockFinancials(symbol: string, marketCountry: MarketCountry): Promise<StockFinancials> {
  return request<StockFinancials>(
    `/api/stocks/${encodeURIComponent(symbol)}/financials?marketCountry=${encodeURIComponent(marketCountry)}`,
    { method: "GET" }
  );
}

export type StockSearchItem = {
  symbol: string;
  name: string;
  englishName: string;
  market: string;
  marketCountry: MarketCountry;
  category: StockCategory;
};

/** `GET /api/stocks/search` — 티커/종목명 검색. */
export function searchStocks(query: string, size = 10): Promise<{ items: StockSearchItem[] }> {
  return request<{ items: StockSearchItem[] }>(
    `/api/stocks/search?q=${encodeURIComponent(query)}&size=${size}`,
    { method: "GET" }
  );
}

export type RankingItem = {
  rank: number;
  // 관심 종목 등록/해제(POST·DELETE /api/stocks/likes)에 필요한 종목 PK — symbol은
  // 시장별로 겹칠 수 있어 식별자로 못 쓴다(docs/api-spec.md).
  stockId: number;
  symbol: string;
  name: string;
  market: string;
  category: StockCategory;
  isDividend: boolean | null;
  leverageFactor: string | null;
  currency: string;
  // 시세가 아직 수집되지 않았으면(장 마감 중 등) 이 필드들은 응답에서 통째로
  // 빠진다 — 백엔드가 non_null 직렬화를 쓰기 때문. 항상 있다고 가정하지 말 것.
  lastPrice?: string;
  prevClose?: string;
  changeAmount?: string;
  changeRate?: string;
  quoteAt?: string;
  tradingAmount: string;
  realtime: boolean;
  // 로그인 상태로 조회했고 이 종목을 찜해뒀을 때만 채워진다(관심 없음/비로그인이면
  // 필드 자체가 응답에서 빠진다) — DELETE /api/stocks/likes/{id}에 이 값을 쓴다.
  stockLikeId?: number;
};

export type RankingPage = {
  items: RankingItem[];
  nextCursor: string | null;
  hasNext: boolean;
};

/**
 * `GET /api/stocks/rankings` — 거래대금 상위 100개, 20개씩 커서 페이지네이션.
 *
 * <p>공개 API지만 로그인돼 있으면 `authOptional`로 토큰을 함께 보내 각 종목의
 * `stockLikeId`(내가 찜했는지)까지 받는다 — 비로그인 사용자에게는 헤더가 아예
 * 안 실리니 이전과 동일하게 동작한다.
 */
export function getRankings(market: MarketCountry, size = 20, cursor?: string): Promise<RankingPage> {
  const params = new URLSearchParams({ market, size: String(size) });
  if (cursor) params.set("cursor", cursor);
  return request<RankingPage>(`/api/stocks/rankings?${params.toString()}`, { method: "GET", authOptional: true });
}

// ── 관심 종목(찜) ────────────────────────────────────────────────────────────

/** `POST /api/stocks/likes` — 관심 종목 등록. 이미 찜해둔 종목이면 같은 stockLikeId를 그대로 돌려준다. */
export function likeStock(stockId: number): Promise<{ stockLikeId: number }> {
  return request<{ stockLikeId: number }>("/api/stocks/likes", { method: "POST", auth: true, body: { stockId } });
}

/** `DELETE /api/stocks/likes/{id}` — 관심 종목 해제. id는 등록 응답·랭킹의 stockLikeId. */
export function unlikeStock(stockLikeId: number): Promise<void> {
  return request<void>(`/api/stocks/likes/${stockLikeId}`, { method: "DELETE", auth: true });
}

export type StockLikeItem = {
  stockLikeId: number;
  stockId: number;
  symbol: string;
  name: string;
  marketCountry: MarketCountry;
  // 시세가 아직 없거나 조회에 실패하면 통째로 빠진다(non_null 직렬화).
  prevClose?: string;
  lastPrice?: string;
  changeRate?: string;
};

export type StockLikePage = {
  items: StockLikeItem[];
  nextCursor: string | null;
  hasNext: boolean;
};

/** `GET /api/stocks/likes` — 내가 찜한 종목 목록(최신 등록순), stockLikeId 기준 커서 페이지네이션. */
export function getStockLikes(params?: { cursor?: string; size?: number }): Promise<StockLikePage> {
  const query = new URLSearchParams();
  if (params?.cursor) query.set("cursor", params.cursor);
  if (params?.size) query.set("size", String(params.size));
  const qs = query.toString();
  return request<StockLikePage>(`/api/stocks/likes${qs ? `?${qs}` : ""}`, { method: "GET", auth: true });
}

export type CandleInterval = "1m" | "5m" | "10m" | "1d" | "1w";
export type CandleRange = "1D" | "1W" | "1M" | "6M" | "1Y";

export type Candle = {
  at: string;
  open: string;
  high: string;
  low: string;
  close: string;
  volume: string;
};

export type CandleData = {
  symbol: string;
  interval: string;
  range: string;
  currency: string;
  items: Candle[];
};

/**
 * `GET /api/stocks/{symbol}/candles` — 캔들 차트. 백엔드가 유효한 조합만 허용한다
 * (1m→1D, 5m→1D/1W, 10m→1W, 1d→1M/6M/1Y, 1w→6M/1Y — `CandleQueryPolicy` 참고).
 * 유효한 조합 선택 자체는 `lib/candle-query.ts`의 `CANDLE_UNIT_PERIODS`/`toCandleQuery`가 담당한다.
 */
export function getCandles(
  symbol: string,
  marketCountry: MarketCountry,
  interval: CandleInterval,
  range: CandleRange
): Promise<CandleData> {
  const params = new URLSearchParams({ marketCountry, interval, range });
  return request<CandleData>(`/api/stocks/${encodeURIComponent(symbol)}/candles?${params.toString()}`, {
    method: "GET",
  });
}

export type OrderBookLevel = {
  level: number;
  price: string;
  quantity: string;
};

export type OrderBook = {
  symbol: string;
  marketCountry: string;
  bookVersion: number;
  revision: number;
  basePrice: string;
  currency: string;
  quoteAt: string;
  generatedAt: string;
  virtual: boolean;
  description: string;
  /** 항상 10개(오름차순, ASK 1이 최우선 매도호가) — `docs/api-spec.md` 참고. */
  asks: OrderBookLevel[];
  /**
   * 내림차순(BID 1이 최우선 매수호가). 국내는 항상 10개지만, 미국은 최소 호가
   * 단위($0.01)에 가까운 저가 종목이면 10개 미만(1~10개)이 올 수 있다 — 마지막
   * 행의 가격은 그 경우 항상 $0.01. 고정 인덱스 접근 대신 배열 길이 그대로 렌더링할 것.
   */
  bids: OrderBookLevel[];
};

/**
 * `GET /api/stocks/{symbol}/orderbook` — 전체 사용자가 공유하는 가상 호가 스냅샷.
 * 실제 주문 호가가 아니라 현재가 기반으로 생성된 참고용 데이터다(`virtual: true`).
 *
 * <p>정상적인 상황에서도 503(`ORDER_BOOK_UNAVAILABLE`)이 흔하다 — 장 마감,
 * 거래정지/정리매매 종목, 시세 지연(15초 이상) 등. 호출부는 이 경우 화면 전체를
 * 에러로 덮지 말고 호가 영역에만 안내를 띄운 뒤, 폴링 주기에 따라 조용히
 * 재시도해야 한다(주문과 달리 `retryPolicy`가 없다 — 사용자가 뭘 다시 눌러야
 * 하는 에러가 아니라는 뜻).
 */
export function getOrderBook(symbol: string, marketCountry: MarketCountry): Promise<OrderBook> {
  const params = new URLSearchParams({ marketCountry });
  return request<OrderBook>(`/api/stocks/${encodeURIComponent(symbol)}/orderbook?${params.toString()}`, {
    method: "GET",
  });
}

// ── 마이페이지(보유/원장/초기화) ────────────────────────────────────────────────

export type HoldingItem = {
  symbol: string;
  name: string;
  currency: string;
  quantity: string;
  avgBuyPrice: string;
  avgExchangeRate: string;
  /** 랭킹에서 빠진 보유 종목은 시세가 없어 null일 수 있다. */
  lastPrice: string | null;
  /** 원화로 환산까지 끝난 값(백엔드 계산) — avgBuyPrice/lastPrice와 달리 추가 환산이 필요 없다. */
  evaluationAmount: string;
  unrealizedPnl: string;
  unrealizedPnlRate: string | null;
  realtime: boolean;
};

export type Holdings = {
  items: HoldingItem[];
  asOf: string;
};

/** `GET /api/accounts/me/holdings` — 보유 종목 목록. */
export function getHoldings(): Promise<Holdings> {
  return request<Holdings>("/api/accounts/me/holdings", { method: "GET", auth: true });
}

export type LedgerEntryType = "INITIAL_DEPOSIT" | "BUY" | "SELL";

export type LedgerItem = {
  entryId: number;
  entryType: LedgerEntryType;
  amount: string;
  balanceAfter: string;
  exchangeRate: string;
  memo: string;
  /** 초기금 지급(INITIAL_DEPOSIT)은 주문이 없어 orderId/symbol/name이 없다. */
  orderId: number | null;
  symbol: string | null;
  name: string | null;
  occurredAt: string;
};

export type LedgerPage = {
  items: LedgerItem[];
  nextCursor: string | null;
  hasNext: boolean;
};

/** `GET /api/accounts/me/ledger` — 체결/원장 내역, entryId 기준 커서 페이지네이션(기본 20건). */
export function getLedger(params?: { cursor?: string; size?: number; entryType?: LedgerEntryType }): Promise<LedgerPage> {
  const query = new URLSearchParams();
  if (params?.cursor) query.set("cursor", params.cursor);
  if (params?.size) query.set("size", String(params.size));
  if (params?.entryType) query.set("entryType", params.entryType);
  const qs = query.toString();
  return request<LedgerPage>(`/api/accounts/me/ledger${qs ? `?${qs}` : ""}`, { method: "GET", auth: true });
}

export type AccountReset = {
  accountId: number;
  roundNo: number;
  initialCash: string;
  cashBalance: string;
};

/** `POST /api/accounts/me/reset` — 포트폴리오 초기화(새 회차 계좌 개설). */
export function resetAccount(accountId: number): Promise<AccountReset> {
  return request<AccountReset>("/api/accounts/me/reset", { method: "POST", auth: true, body: { accountId } });
}

// ── 투자 성향 리포트 ──────────────────────────────────────────────────────────

export type PersonalityReportShares = {
  concentration: string;
  domestic: string;
  individual: string;
  aggressive: string;
};

export type LongHeldStock = {
  symbol: string;
  name: string;
  currency: string;
  avgBuyPrice: string;
  lastPrice: string;
  /** 시세가 없으면 null(마이페이지 보유 종목과 같은 사정). */
  returnRate: string | null;
  heldSince: string;
};

/**
 * `GET /api/reports/me` 응답 — 투자 성향 리포트(현재 활성 계좌=라운드 기준).
 *
 * <p>`locked`가 true면 계좌 개설 후 아직 `unlockAt`(개설 + N주)에 못 미친 상태라
 * `initialCash`~`longHeldStocks`가 전부 null/빈 값이다. 잠금이 풀린 뒤에도
 * "고정된 스냅샷"이 아니라 열 때마다 다시 계산된다 — `asOf`가 매번 최신 계산
 * 시각이다(백엔드 주석: "발급(열림) 후에는 열 때마다 재계산한다").
 *
 * <p>`classified`는 보유 종목이 2개 이상이어야 true다 — 미만이면 유형을 정하지
 * 않는 "미분류/신규" 상태로, `typeCode`/`typeLabel`이 null이어도 `shares`는
 * 계산된 값(평가액이 아예 없으면 전부 "0")을 그대로 담는다.
 */
export type PersonalityReport = {
  accountId: number;
  roundNo: number;
  locked: boolean;
  unlockAt: string;
  initialCash: string | null;
  cashBalance: string | null;
  stockValue: string | null;
  totalAsset: string | null;
  totalPnl: string | null;
  /** 초기자본 대비 총손익 — 0~1 소수 문자열(마이페이지의 unrealizedPnlRate와는 다른 지표). */
  returnRate: string | null;
  classified: boolean;
  /** 4글자 유형 코드(예: "CKSB") — 분산·시장·유형·공격성 순. 미분류/잠김이면 null. */
  typeCode: string | null;
  typeLabel: string | null;
  shares: PersonalityReportShares | null;
  holdingCount: number;
  holdingPeriodWeeks: number;
  longHeldStocks: LongHeldStock[];
  asOf: string;
};

/** `GET /api/reports/me` — 내 투자 성향 리포트. */
export function getPersonalityReport(): Promise<PersonalityReport> {
  return request<PersonalityReport>("/api/reports/me", { method: "GET", auth: true });
}

export type LeaderboardEntry = {
  rank: number;
  /** 백엔드가 이미 가운데 글자를 마스킹해서 내려준다 — 프론트에서 다시 가릴 필요 없음. */
  nickname: string;
  returnRate: string;
};

export type LeaderboardMe = {
  rank: number;
  returnRate: string;
  /** 1/5/10/25/50/75 중 하나, 하위권이면 null. */
  topPercent: number | null;
  neighbors: LeaderboardEntry[];
  // 유형(#153 Phase 3) — 내 투자 유형 안에서의 순위다. 미분류(유형 없음)면 다섯 필드 모두 null.
  typeCode: string | null;
  typeLabel: string | null;
  typeRank: number | null;
  typeParticipants: number | null;
  /** 1/5/10/25/50/75 중 하나, 하위권이면 null(topPercent와 같은 브래킷 규칙, 유형 코호트 기준). */
  typePercent: number | null;
};

/**
 * `GET /api/reports/leaderboard` 응답 — 아침 배치 스냅샷 기준(라이브 리포트와 값이 다를 수 있어
 * `asOf`를 함께 내려준다). 스냅샷이 아직 없거나 참가자가 0명이면 `asOf: null · top: [] · me: null`.
 */
export type Leaderboard = {
  asOf: string | null;
  participants: number;
  top: LeaderboardEntry[];
  me: LeaderboardMe | null;
};

/** `GET /api/reports/leaderboard` — 수익률 리더보드. */
export function getLeaderboard(): Promise<Leaderboard> {
  return request<Leaderboard>("/api/reports/leaderboard", { method: "GET", auth: true });
}

export type LeaderboardTypeEntry = {
  typeCode: string;
  typeLabel: string;
  count: number;
  /** 0~1 소수 문자열 — 같은 코호트(같은 라운드·최신 배치)의 유형별 평균 수익률. */
  avgReturnRate: string;
};

/**
 * `GET /api/reports/leaderboard/types` 응답 — 유형별 성과 비교(#153 Phase 3). 아침 배치의
 * 같은 라운드 코호트에서 유형별 평균 수익률을 비교한다. 미분류(유형 없음)는 애초에 집계에서
 * 빠진다. 스냅샷이 없으면 `asOf: null · types: []`.
 */
export type LeaderboardTypes = {
  asOf: string | null;
  types: LeaderboardTypeEntry[];
};

/** `GET /api/reports/leaderboard/types` — 유형별(16종) 평균 수익률 비교. */
export function getLeaderboardTypes(): Promise<LeaderboardTypes> {
  return request<LeaderboardTypes>("/api/reports/leaderboard/types", { method: "GET", auth: true });
}

// ── 시장 운영 상태 ───────────────────────────────────────────────────────────

export type MarketStatusItem = {
  marketCountry: MarketCountry;
  /** 지금 이 순간 정규장 운영 중인지. */
  open: boolean;
  opensAt: string | null;
  closesAt: string | null;
  /** open이 false일 때만 값이 있다. */
  nextOpensAt: string | null;
};

export type MarketStatus = {
  markets: MarketStatusItem[];
  serverTime: string;
};

/** `GET /api/market/status` — 국내/해외 시장 개장 여부·다음 개장 시각. 파라미터 없이 둘 다 내려온다. */
export function getMarketStatus(): Promise<MarketStatus> {
  return request<MarketStatus>("/api/market/status", { method: "GET" });
}

// ── 시장조치(서킷브레이커·사이드카) ───────────────────────────────────────────

export type KrMarket = "KOSPI" | "KOSDAQ";

export type MarketEventItem = {
  eventId: number;
  eventType: "CIRCUIT_BREAKER" | "SIDECAR";
  /** CIRCUIT_BREAKER에서만: 1~3단계. */
  stage?: number;
  /** SIDECAR에서만: 매수/매도 방향. */
  direction?: "BUY" | "SELL";
  triggeredAt: string;
  haltUntil: string;
  publishedAt: string;
  receivedAt: string;
  /** `triggeredAt <= 지금 < haltUntil` 여부 — 서버가 조회 시점에 판정해서 내려준다. */
  active: boolean;
  title: string;
  sourceUrl: string;
};

export type MarketEvents = {
  market: KrMarket;
  date: string;
  items: MarketEventItem[];
};

/**
 * `GET /api/market/events` — KRX 서킷브레이커·사이드카 발동 이력(공개, 로그인 불필요).
 * `date`는 `yyyy-MM-dd`(KST 기준 하루)이고 국내(KOSPI/KOSDAQ)만 지원한다.
 */
export function getMarketEvents(market: KrMarket, date: string): Promise<MarketEvents> {
  return request<MarketEvents>(
    `/api/market/events?market=${encodeURIComponent(market)}&date=${encodeURIComponent(date)}`,
    { method: "GET" }
  );
}

// ── 환율 ──────────────────────────────────────────────────────────────────────

export type ExchangeRateLatest = {
  baseCurrency: string;
  quoteCurrency: string;
  /** 화면 표시용 매매기준율(mid rate). 체결에 쓰는 스프레드 포함 환율과는 다른 값이다. */
  rate: string;
  /** 전일 자정(00:00 KST) 대비 등락액. */
  changeAmount: string;
  /** 전일 자정(00:00 KST) 대비 등락률 (0.0016 = +0.16%). */
  changeRate: string;
  validFrom: string;
};

/**
 * `GET /api/exchange-rates/latest` — 랭킹 화면 환율 배너. base/quote 생략 시 기본값 USD/KRW.
 *
 * <p>대소문자는 백엔드(`ExchangeRateService`)가 정규화해줘서 어차피 안전하지만, 여기서도
 * 대문자로 맞춰 보낸다 — `?base=usd`와 `?base=USD`가 서로 다른 캐시 키로 취급돼 캐시가
 * 갈라지는 것을 막기 위해서다(oxcm07님 리뷰, PR #53).
 */
export function getExchangeRateLatest(base = "USD", quote = "KRW", signal?: AbortSignal): Promise<ExchangeRateLatest> {
  return request<ExchangeRateLatest>(
    `/api/exchange-rates/latest?base=${encodeURIComponent(base.toUpperCase())}&quote=${encodeURIComponent(quote.toUpperCase())}`,
    { method: "GET", signal }
  );
}

export type ExchangeRatePeriod = "1d" | "1w" | "1m" | "3m" | "1y";

export type ExchangeRateHistoryItem = {
  validFrom: string;
  rate: string;
};

export type ExchangeRateHistory = {
  items: ExchangeRateHistoryItem[];
};

/**
 * `GET /api/exchange-rates/history` — 환율 추이 그래프. USD/KRW 고정(백엔드가 MVP는
 * 이 통화쌍만 다룬다 — `ExchangeRateService` 참고), `period`는 필수 파라미터다.
 */
export function getExchangeRateHistory(period: ExchangeRatePeriod, signal?: AbortSignal): Promise<ExchangeRateHistory> {
  return request<ExchangeRateHistory>(
    `/api/exchange-rates/history?period=${encodeURIComponent(period)}`,
    { method: "GET", signal }
  );
}
