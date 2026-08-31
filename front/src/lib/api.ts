/**
 * 백엔드 API 클라이언트. `back/src/main/java/com/baedang/auth/*`, `.../trading/*` 에
 * 구현된 회원가입·로그인·주문 API를 그대로 호출합니다.
 *
 * <p>1주차 백엔드는 토큰을 발급하지 않고 `userId`만 돌려줍니다 — 이후 요청은
 * `X-User-Id` 헤더로 사용자를 식별하는 개발용 방식입니다 (2주차에 JWT로 교체 예정).
 * `AuthProvider`가 이 `userId`를 들고 있다가 필요할 때 헤더에 실어 보냅니다.
 */

const API_BASE_URL = process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080";

export type AuthUser = {
  userId: number;
  email: string;
  nickname: string;
};

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
  method: "GET" | "POST";
  headers?: Record<string, string>;
  body?: unknown;
};

async function request<T>(path: string, init: RequestInput): Promise<T> {
  let res: Response;
  try {
    res = await fetch(`${API_BASE_URL}${path}`, {
      method: init.method,
      headers: { "Content-Type": "application/json", ...init.headers },
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

export function signUp(input: { email: string; password: string; nickname: string }): Promise<AuthUser> {
  return request<AuthUser>("/api/auth/signup", { method: "POST", body: input });
}

export function login(input: { email: string; password: string }): Promise<AuthUser> {
  return request<AuthUser>("/api/auth/login", { method: "POST", body: input });
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
export function getAccountSummary(userId: number): Promise<AccountSummary> {
  return request<AccountSummary>("/api/accounts/me", {
    method: "GET",
    headers: { "X-User-Id": String(userId) },
  });
}

// ── 주문 ──────────────────────────────────────────────────────────────────────

export type PlaceOrderInput = {
  accountId: number;
  clientOrderId: string;
  symbol: string;
  marketCountry: "KR" | "US";
  side: "BUY" | "SELL";
  quantity: string;
};

export type OrderResponse = {
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

/** `POST /api/orders` — 시장가 매수/매도. 로그인한 사용자만 호출 가능(X-User-Id 헤더). */
export function placeOrder(userId: number, input: PlaceOrderInput): Promise<OrderResponse> {
  return request<OrderResponse>("/api/orders", {
    method: "POST",
    headers: { "X-User-Id": String(userId) },
    body: input,
  });
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
  rateAt: string;
};

/**
 * `GET /api/exchange-rates/latest` — 랭킹 화면 환율 배너. base/quote 생략 시 기본값 USD/KRW.
 *
 * <p>대소문자는 백엔드(`ExchangeRateService`)가 정규화해줘서 어차피 안전하지만, 여기서도
 * 대문자로 맞춰 보낸다 — `?base=usd`와 `?base=USD`가 서로 다른 캐시 키로 취급돼 캐시가
 * 갈라지는 것을 막기 위해서다(oxcm07님 리뷰, PR #53).
 */
export function getExchangeRateLatest(base = "USD", quote = "KRW"): Promise<ExchangeRateLatest> {
  return request<ExchangeRateLatest>(
    `/api/exchange-rates/latest?base=${encodeURIComponent(base.toUpperCase())}&quote=${encodeURIComponent(quote.toUpperCase())}`,
    { method: "GET" }
  );
}
