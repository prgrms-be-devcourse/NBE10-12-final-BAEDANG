import { getMarketEvents, type KrMarket, type MarketEventItem } from "./api";

/**
 * 종목 상세 화면에서 쓰는 시장조치(KRX) 상태.
 *
 * 랭킹 배너(`MarketEventsBanner`)는 "오늘 무슨 일이 있었나"를 보여주는 이력이지만,
 * 여기서 필요한 건 "지금 이 종목의 시장이 중단 중인가" 하나다. 그래서 조회 결과에서
 * **활성 이벤트만** 뽑아 서킷브레이커와 사이드카를 분리한다.
 *
 * 차단 여부는 이 결과만으로 결정하지 않는다 — 서버 주문 트랜잭션이 account 잠금 뒤에
 * 권위 있게 다시 판정하고, 이 값은 같은 상태를 화면에 미리 보여주는 용도다.
 */
export type StockMarketEventState = {
  /** 활성 서킷브레이커. 있으면 신규 주문과 기존 지정가 체결이 모두 멈춘다. */
  activeCircuitBreaker: MarketEventItem | null;
  /** 활성 사이드카. 프로그램 매매 호가만 멈추므로 일반 주문은 계속 가능하다. */
  activeSidecars: MarketEventItem[];
};

export const EMPTY_STOCK_MARKET_EVENT_STATE: StockMarketEventState = {
  activeCircuitBreaker: null,
  activeSidecars: [],
};

/**
 * 종목 시장 문자열을 시장조치 API가 받는 시장 코드로 좁힌다.
 * `KR_ETC`·미국 시장은 KIND 시장조치 대상이 아니므로 없는 이벤트를 조회하지 않는다.
 */
export function toKrMarket(market: string | null | undefined): KrMarket | null {
  return market === "KOSPI" || market === "KOSDAQ" ? market : null;
}

/**
 * 오늘 날짜를 KST 기준 `yyyy-MM-dd`로. en-CA 로케일이 이 형식을 그대로 내어준다.
 *
 * 랭킹 배너와 종목 상세가 같은 날짜를 조회해야 하므로 한 곳에서만 만든다 —
 * 두 곳에 각자 두면 한쪽만 KST 처리에서 벗어나도 눈치채기 어렵다.
 */
export function todayInKst(now: Date = new Date()): string {
  return now.toLocaleDateString("en-CA", { timeZone: "Asia/Seoul" });
}

/**
 * 조회 결과에서 활성 이벤트를 분류한다.
 *
 * 최신순 정렬은 서버 정렬(`triggeredAt DESC, eventId DESC`)과 같은 규칙을 여기서도
 * 적용한다 — 응답 순서가 바뀌어도 화면에 보이는 단계/시각이 흔들리면 안 된다.
 */
export function classifyActiveEvents(
  items: readonly MarketEventItem[] | null | undefined,
): StockMarketEventState {
  const byRecency = [...(items ?? [])]
    .filter((item) => item?.active)
    .sort((a, b) => {
      const byTime = Date.parse(b.triggeredAt) - Date.parse(a.triggeredAt);
      return Number.isNaN(byTime) || byTime === 0 ? b.eventId - a.eventId : byTime;
    });

  return {
    activeCircuitBreaker: byRecency.find((item) => item.eventType === "CIRCUIT_BREAKER") ?? null,
    activeSidecars: byRecency.filter((item) => item.eventType === "SIDECAR"),
  };
}

/** 사이드카 배지 문구. 방향을 모르면 방향 없이 보여준다. */
export function sidecarLabel(sidecar: MarketEventItem): string {
  if (sidecar.direction === "BUY") return "사이드카 매수 발동 중";
  if (sidecar.direction === "SELL") return "사이드카 매도 발동 중";
  return "사이드카 발동 중";
}

/** 시장조치 조회. 국내 시장이 아니면 호출하지 않고 빈 상태를 반환한다. */
export async function fetchStockMarketEvents(
  market: string | null | undefined,
  now: Date = new Date(),
): Promise<StockMarketEventState> {
  const krMarket = toKrMarket(market);
  if (!krMarket) return EMPTY_STOCK_MARKET_EVENT_STATE;
  const response = await getMarketEvents(krMarket, todayInKst(now));
  return classifyActiveEvents(response.items);
}
