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
  symbol: string;
  name: string;
  market: string;
  category: StockCategory;
  isDividend: boolean | null;
  leverageFactor: string | null;
  currency: string;
  lastPrice: string;
  prevClose: string;
  changeAmount: string;
  changeRate: string;
  tradingAmount: string;
  quoteAt: string;
  realtime: boolean;
};

export type RankingPage = {
  items: RankingItem[];
  nextCursor: string | null;
  hasNext: boolean;
};

/** `GET /api/stocks/rankings` — 거래대금 상위 100개, 20개씩 커서 페이지네이션. */
export function getRankings(market: MarketCountry, size = 20, cursor?: string): Promise<RankingPage> {
  const params = new URLSearchParams({ market, size: String(size) });
  if (cursor) params.set("cursor", cursor);
  return request<RankingPage>(`/api/stocks/rankings?${params.toString()}`, { method: "GET" });
}

export type CandleInterval = "1m" | "1d";
export type CandleRange = "1D" | "1M" | "6M" | "1Y";

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
 * (1m은 반드시 range=1D, 1d는 1M/6M/1Y — `CandleQueryPolicy` 참고).
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
export function getHoldings(userId: number): Promise<Holdings> {
  return request<Holdings>("/api/accounts/me/holdings", {
    method: "GET",
    headers: { "X-User-Id": String(userId) },
  });
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
export function getLedger(
  userId: number,
  params?: { cursor?: string; size?: number; entryType?: LedgerEntryType }
): Promise<LedgerPage> {
  const query = new URLSearchParams();
  if (params?.cursor) query.set("cursor", params.cursor);
  if (params?.size) query.set("size", String(params.size));
  if (params?.entryType) query.set("entryType", params.entryType);
  const qs = query.toString();
  return request<LedgerPage>(`/api/accounts/me/ledger${qs ? `?${qs}` : ""}`, {
    method: "GET",
    headers: { "X-User-Id": String(userId) },
  });
}

export type AccountReset = {
  accountId: number;
  roundNo: number;
  initialCash: string;
  cashBalance: string;
};

/** `POST /api/accounts/me/reset` — 포트폴리오 초기화(새 회차 계좌 개설). */
export function resetAccount(userId: number, accountId: number): Promise<AccountReset> {
  return request<AccountReset>("/api/accounts/me/reset", {
    method: "POST",
    headers: { "X-User-Id": String(userId) },
    body: { accountId },
  });
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

export type ExchangeRatePeriod = "1d" | "1w" | "1m" | "3m" | "1y";

export type ExchangeRateHistoryItem = {
  rateAt: string;
  rate: string;
};

export type ExchangeRateHistory = {
  items: ExchangeRateHistoryItem[];
};

/**
 * `GET /api/exchange-rates/history` — 환율 추이 그래프. USD/KRW 고정(백엔드가 MVP는
 * 이 통화쌍만 다룬다 — `ExchangeRateService` 참고), `period`는 필수 파라미터다.
 */
export function getExchangeRateHistory(period: ExchangeRatePeriod): Promise<ExchangeRateHistory> {
  return request<ExchangeRateHistory>(
    `/api/exchange-rates/history?period=${encodeURIComponent(period)}`,
    { method: "GET" }
  );
}
