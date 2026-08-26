/**
 * 목데이터 모음. 백엔드 API가 준비되기 전까지 화면 인터랙션을 검증하기 위한 것으로,
 * `docs/api-spec.md`의 응답 형태(필드명, 단위)를 최대한 그대로 따라갑니다.
 * 실제 API 연동은 백엔드 엔드포인트가 준비된 뒤 별도 이슈에서 진행합니다.
 */

export type MarketCountry = "KR" | "US";
export type StockCategory = "개별주" | "배당주" | "ETF";

export type RankingItem = {
  rank: number;
  symbol: string;
  name: string;
  market: string;
  category: StockCategory;
  lastPrice: number;
  changeAmount: number;
  changeRate: number;
  tradingAmount: number;
  currency: "KRW" | "USD";
};

const KR_NAMED: Omit<RankingItem, "rank" | "tradingAmount">[] = [
  { symbol: "005930", name: "삼성전자", market: "KOSPI", category: "개별주", lastPrice: 241500, changeAmount: 5450, changeRate: 0.0231, currency: "KRW" },
  { symbol: "000660", name: "SK하이닉스", market: "KOSPI", category: "개별주", lastPrice: 1429000, changeAmount: 15000, changeRate: 0.0108, currency: "KRW" },
  { symbol: "069500", name: "KODEX 200", market: "KOSPI", category: "ETF", lastPrice: 48120, changeAmount: 380, changeRate: 0.008, currency: "KRW" },
  { symbol: "105560", name: "KB금융", market: "KOSPI", category: "배당주", lastPrice: 167900, changeAmount: -1200, changeRate: -0.0071, currency: "KRW" },
  { symbol: "035420", name: "NAVER", market: "KOSPI", category: "개별주", lastPrice: 212000, changeAmount: 3000, changeRate: 0.0143, currency: "KRW" },
  { symbol: "207940", name: "삼성바이오로직스", market: "KOSPI", category: "개별주", lastPrice: 985000, changeAmount: -4000, changeRate: -0.0041, currency: "KRW" },
  { symbol: "005380", name: "현대차", market: "KOSPI", category: "배당주", lastPrice: 231500, changeAmount: 1500, changeRate: 0.0065, currency: "KRW" },
  { symbol: "051910", name: "LG화학", market: "KOSPI", category: "개별주", lastPrice: 318000, changeAmount: -2500, changeRate: -0.0078, currency: "KRW" },
];

const US_NAMED: Omit<RankingItem, "rank" | "tradingAmount">[] = [
  { symbol: "NVDA", name: "엔비디아", market: "NASDAQ", category: "개별주", lastPrice: 182.4, changeAmount: 4.2, changeRate: 0.0236, currency: "USD" },
  { symbol: "AAPL", name: "애플", market: "NASDAQ", category: "배당주", lastPrice: 231.1, changeAmount: -1.8, changeRate: -0.0077, currency: "USD" },
  { symbol: "TSLA", name: "테슬라", market: "NASDAQ", category: "개별주", lastPrice: 264.5, changeAmount: 9.1, changeRate: 0.0357, currency: "USD" },
  { symbol: "MSFT", name: "마이크로소프트", market: "NASDAQ", category: "배당주", lastPrice: 468.3, changeAmount: 2.1, changeRate: 0.0045, currency: "USD" },
  { symbol: "SPY", name: "SPDR S&P 500", market: "NYSE", category: "ETF", lastPrice: 612.7, changeAmount: 3.4, changeRate: 0.0056, currency: "USD" },
  { symbol: "AMZN", name: "아마존", market: "NASDAQ", category: "개별주", lastPrice: 214.9, changeAmount: -0.6, changeRate: -0.0028, currency: "USD" },
];

function pad(list: Omit<RankingItem, "rank" | "tradingAmount">[], country: MarketCountry, total: number): Omit<RankingItem, "rank" | "tradingAmount">[] {
  const padded = [...list];
  const market = country === "KR" ? "KOSDAQ" : "NYSE";
  for (let i = list.length; i < total; i++) {
    const n = i + 1;
    padded.push({
      symbol: country === "KR" ? String(100000 + n) : `US${n}`,
      name: country === "KR" ? `국내종목${n}` : `US Stock ${n}`,
      market,
      category: n % 5 === 0 ? "ETF" : n % 3 === 0 ? "배당주" : "개별주",
      lastPrice: country === "KR" ? 10000 + n * 137 : 20 + n * 0.7,
      changeAmount: (n % 2 === 0 ? 1 : -1) * (country === "KR" ? n * 13 : n * 0.11),
      changeRate: (n % 2 === 0 ? 1 : -1) * (0.001 + (n % 20) * 0.001),
      currency: country === "KR" ? "KRW" : "USD",
    });
  }
  return padded;
}

function buildRankings(country: MarketCountry): RankingItem[] {
  const base = country === "KR" ? KR_NAMED : US_NAMED;
  const padded = pad(base, country, 100);
  // 거래대금 내림차순이 되도록 순위가 낮을수록 큰 값을 부여한다 (실제론 서버가 정렬해서 내려줌).
  return padded.map((item, i) => ({
    ...item,
    rank: i + 1,
    tradingAmount: Math.round((100 - i) * (country === "KR" ? 12_400_000_000 : 9_400_000_000)),
  }));
}

export const KR_RANKINGS: RankingItem[] = buildRankings("KR");
export const US_RANKINGS: RankingItem[] = buildRankings("US");

/** 랭킹 페이지 검색창에서 쓰는 전체 종목 유니버스 (실제로는 ~8,500종목, 여기선 랭킹 종목만). */
export const SEARCHABLE_STOCKS = [...KR_RANKINGS, ...US_RANKINGS];

export type StockDetail = {
  symbol: string;
  name: string;
  englishName: string;
  market: string;
  marketCountry: MarketCountry;
  currency: "KRW" | "USD";
  category: StockCategory;
  lastPrice: number;
  prevClose: number;
  changeAmount: number;
  changeRate: number;
  upperLimit: number | null;
  lowerLimit: number | null;
  marketCap: string;
  sharesOutstanding: string;
  tradable: boolean;
  tradableReason: string | null;
  warning: string | null;
  description: string;
};

const STOCK_DETAILS: Record<string, StockDetail> = {
  "005930": {
    symbol: "005930",
    name: "삼성전자",
    englishName: "SamsungElec",
    market: "KOSPI",
    marketCountry: "KR",
    currency: "KRW",
    category: "개별주",
    lastPrice: 241500,
    prevClose: 236050,
    changeAmount: 5450,
    changeRate: 0.0231,
    upperLimit: 313500,
    lowerLimit: 169500,
    marketCap: "1,441조",
    sharesOutstanding: "5,969,782,550",
    tradable: true,
    tradableReason: null,
    warning: "투자경고 종목으로 지정되어 있습니다. 매수 전 확인하세요.",
    description:
      "개별주는 특정 기업 한 곳의 지분을 사는 것입니다. 그 회사가 잘되면 오르고 어려워지면 내립니다. 여러 기업에 나눠 담는 ETF보다 변동이 크기 때문에, 한 종목에 자산을 몰아넣지 않는 것이 중요합니다.",
  },
  NVDA: {
    symbol: "NVDA",
    name: "엔비디아",
    englishName: "NVIDIA",
    market: "NASDAQ",
    marketCountry: "US",
    currency: "USD",
    category: "개별주",
    lastPrice: 182.4,
    prevClose: 178.2,
    changeAmount: 4.2,
    changeRate: 0.0236,
    upperLimit: null,
    lowerLimit: null,
    marketCap: "4.5조 달러",
    sharesOutstanding: "24,530,000,000",
    tradable: false,
    tradableReason: "MARKET_CLOSED",
    warning: null,
    description:
      "개별주는 특정 기업 한 곳의 지분을 사는 것입니다. 미국 종목은 한국 시간 기준 밤~새벽에만 거래할 수 있습니다.",
  },
  "069500": {
    symbol: "069500",
    name: "KODEX 200",
    englishName: "KODEX200",
    market: "KOSPI",
    marketCountry: "KR",
    currency: "KRW",
    category: "ETF",
    lastPrice: 48120,
    prevClose: 47740,
    changeAmount: 380,
    changeRate: 0.008,
    upperLimit: 62050,
    lowerLimit: 33430,
    marketCap: "68,200억",
    sharesOutstanding: "141,760,000",
    tradable: true,
    tradableReason: null,
    warning: null,
    description:
      "ETF는 여러 기업에 나눠 투자하는 상품입니다. 한 기업이 흔들려도 전체 영향은 희석돼서, 개별주보다 변동이 작습니다.",
  },
};

const DEFAULT_DETAIL: StockDetail = {
  symbol: "000000",
  name: "샘플 종목",
  englishName: "Sample",
  market: "KOSPI",
  marketCountry: "KR",
  currency: "KRW",
  category: "개별주",
  lastPrice: 50000,
  prevClose: 49500,
  changeAmount: 500,
  changeRate: 0.0101,
  upperLimit: 64000,
  lowerLimit: 34500,
  marketCap: "—",
  sharesOutstanding: "—",
  tradable: true,
  tradableReason: null,
  warning: null,
  description: "개별주는 특정 기업 한 곳의 지분을 사는 것입니다.",
};

export function getStockDetail(symbol: string): StockDetail {
  const found = STOCK_DETAILS[symbol.toUpperCase()] ?? STOCK_DETAILS[symbol];
  if (found) return found;
  const fromRanking = SEARCHABLE_STOCKS.find((s) => s.symbol === symbol);
  if (fromRanking) {
    return {
      ...DEFAULT_DETAIL,
      symbol: fromRanking.symbol,
      name: fromRanking.name,
      englishName: fromRanking.name,
      market: fromRanking.market,
      marketCountry: fromRanking.market === "NASDAQ" || fromRanking.market === "NYSE" ? "US" : "KR",
      currency: fromRanking.currency,
      category: fromRanking.category,
      lastPrice: fromRanking.lastPrice,
      prevClose: fromRanking.lastPrice - fromRanking.changeAmount,
      changeAmount: fromRanking.changeAmount,
      changeRate: fromRanking.changeRate,
    };
  }
  return { ...DEFAULT_DETAIL, symbol };
}

/** 차트용 목 캔들 — 실제로는 daily_candle/minute_candle에서 옵니다. */
export function getMockChartPoints(seed: string): number[] {
  let x = Array.from(seed).reduce((acc, c) => acc + c.charCodeAt(0), 0);
  const points: number[] = [];
  let v = 50;
  for (let i = 0; i < 24; i++) {
    x = (x * 1103515245 + 12345) & 0x7fffffff;
    v += ((x % 100) - 48) / 10;
    points.push(v);
  }
  return points;
}

export type Holding = {
  symbol: string;
  name: string;
  market: MarketCountry;
  currency: "KRW" | "USD";
  quantity: number;
  avgBuyPrice: number;
  lastPrice: number;
};

export const MOCK_HOLDINGS: Holding[] = [
  { symbol: "005930", name: "삼성전자", market: "KR", currency: "KRW", quantity: 6, avgBuyPrice: 228000, lastPrice: 241500 },
  { symbol: "NVDA", name: "엔비디아", market: "US", currency: "USD", quantity: 3, avgBuyPrice: 168.2, lastPrice: 182.4 },
];

export type LedgerEntry = {
  type: "매수" | "매도" | "초기지급";
  memo: string;
  amount: number;
  balanceAfter: number;
  occurredAt: string;
};

export const MOCK_LEDGER: LedgerEntry[] = [
  { type: "매수", memo: "삼성전자 10주 @ 241,500 (수수료 포함)", amount: -2415242, balanceAfter: 47584758, occurredAt: "08-11 12:37:02" },
  { type: "매도", memo: "엔비디아 2주 @ $182.30 · 환율 1,398.50 (수수료·SEC Fee 포함)", amount: 509828, balanceAfter: 48094586, occurredAt: "08-11 09:14:33" },
  { type: "초기지급", memo: "모의 투자금 지급 · 1회차", amount: 50000000, balanceAfter: 50000000, occurredAt: "08-10 21:02:11" },
];

export const INITIAL_CASH = 50_000_000;

/** 마이페이지 계좌 요약과 별개로, 상세 페이지 거래 패널에서 쓰는 목 주문가능금액. */
export const AVAILABLE_CASH = 48_240_000;

export function getHolding(symbol: string): Holding | undefined {
  return MOCK_HOLDINGS.find((h) => h.symbol === symbol);
}

/** docs/api-spec.md / AGENTS.md 에 명시된 확정 수수료·세율. */
export const FEE_RATE = 0.0001; // 0.01%, 매수·매도 공통
export const KR_TAX_RATE = 0.002; // 국내 매도세 0.2%
export const US_TAX_RATE = 0.0000206; // 미국 SEC Fee
export const US_TAX_MIN_USD = 0.01;
