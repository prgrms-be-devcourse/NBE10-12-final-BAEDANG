import { describe, it, expect } from "vitest";
import {
  EMPTY_STOCK_MARKET_EVENT_STATE,
  classifyActiveEvents,
  sidecarLabel,
  todayInKst,
  toKrMarket,
} from "../stock-market-events";
import type { MarketEventItem } from "../api";

function event(overrides: Partial<MarketEventItem>): MarketEventItem {
  return {
    eventId: 1,
    eventType: "CIRCUIT_BREAKER",
    triggeredAt: "2026-09-14T13:28:32+09:00",
    haltUntil: "2026-09-14T13:48:32+09:00",
    publishedAt: "2026-09-14T13:29:00+09:00",
    receivedAt: "2026-09-14T13:29:07+09:00",
    active: true,
    title: "유가증권시장 매매거래 일시중단(1단계 CB 발동)",
    sourceUrl: "https://kind.krx.co.kr/external/2026/09/14/000273/20260914000658/99443.htm",
    ...overrides,
  };
}

describe("toKrMarket — 시장조치 API가 받는 국내 시장만 통과시킨다", () => {
  it("KOSPI와 KOSDAQ는 그대로 쓴다", () => {
    expect(toKrMarket("KOSPI")).toBe("KOSPI");
    expect(toKrMarket("KOSDAQ")).toBe("KOSDAQ");
  });

  it("KR_ETC와 미국 시장은 KIND 대상이 아니라 조회하지 않는다", () => {
    expect(toKrMarket("KR_ETC")).toBeNull();
    expect(toKrMarket("NYSE")).toBeNull();
    expect(toKrMarket("NASDAQ")).toBeNull();
    expect(toKrMarket(null)).toBeNull();
    expect(toKrMarket(undefined)).toBeNull();
  });
});

describe("todayInKst — 랭킹 배너와 같은 KST 날짜를 만든다", () => {
  it("UTC 기준 밤 시각도 KST 날짜로 넘긴다", () => {
    // 2026-09-14T16:30Z = 2026-09-15 01:30 KST
    expect(todayInKst(new Date("2026-09-14T16:30:00Z"))).toBe("2026-09-15");
  });

  it("KST 정오는 같은 날짜를 유지한다", () => {
    expect(todayInKst(new Date("2026-09-14T03:00:00Z"))).toBe("2026-09-14");
  });
});

describe("classifyActiveEvents — 활성 CB와 사이드카를 분리한다", () => {
  it("활성 CIRCUIT_BREAKER는 activeCircuitBreaker로, 활성 SIDECAR는 activeSidecars로 분류한다", () => {
    const state = classifyActiveEvents([
      event({ eventId: 1, eventType: "CIRCUIT_BREAKER", stage: 1 }),
      event({ eventId: 2, eventType: "SIDECAR", direction: "BUY", triggeredAt: "2026-09-14T09:05:00+09:00" }),
    ]);

    expect(state.activeCircuitBreaker?.eventId).toBe(1);
    expect(state.activeSidecars.map((item) => item.eventId)).toEqual([2]);
  });

  it("만료된 이벤트는 두 결과에서 모두 제외한다", () => {
    const state = classifyActiveEvents([
      event({ eventId: 1, active: false }),
      event({ eventId: 2, eventType: "SIDECAR", direction: "SELL", active: false }),
    ]);

    expect(state).toEqual(EMPTY_STOCK_MARKET_EVENT_STATE);
  });

  it("사이드카만 활성이면 CB는 null이고 주문을 막지 않는다", () => {
    const state = classifyActiveEvents([event({ eventType: "SIDECAR", direction: "BUY" })]);

    expect(state.activeCircuitBreaker).toBeNull();
    expect(state.activeSidecars).toHaveLength(1);
  });

  it("복수 활성 이벤트는 응답 순서와 무관하게 최신순으로 고른다", () => {
    // 서버가 triggeredAt DESC로 내려주지만, 순서가 뒤집혀 와도 같은 결과여야 한다.
    const older = event({ eventId: 7, triggeredAt: "2026-09-14T10:00:00+09:00", stage: 1 });
    const newer = event({ eventId: 9, triggeredAt: "2026-09-14T14:00:00+09:00", stage: 2 });

    expect(classifyActiveEvents([older, newer]).activeCircuitBreaker?.eventId).toBe(9);
    expect(classifyActiveEvents([newer, older]).activeCircuitBreaker?.eventId).toBe(9);
  });

  it("같은 시각이면 eventId 역순으로 결정적으로 고른다", () => {
    const at = "2026-09-14T13:28:32+09:00";
    const state = classifyActiveEvents([
      event({ eventId: 3, triggeredAt: at }),
      event({ eventId: 8, triggeredAt: at }),
    ]);

    expect(state.activeCircuitBreaker?.eventId).toBe(8);
  });

  it("빈 응답과 null은 빈 상태로 다룬다", () => {
    expect(classifyActiveEvents([])).toEqual(EMPTY_STOCK_MARKET_EVENT_STATE);
    expect(classifyActiveEvents(null)).toEqual(EMPTY_STOCK_MARKET_EVENT_STATE);
    expect(classifyActiveEvents(undefined)).toEqual(EMPTY_STOCK_MARKET_EVENT_STATE);
  });
});

describe("sidecarLabel — 방향을 문구로 옮긴다", () => {
  it("매수·매도 방향을 구분하고 방향이 없으면 방향 없이 보여준다", () => {
    expect(sidecarLabel(event({ eventType: "SIDECAR", direction: "BUY" }))).toBe("사이드카 매수 발동 중");
    expect(sidecarLabel(event({ eventType: "SIDECAR", direction: "SELL" }))).toBe("사이드카 매도 발동 중");
    expect(sidecarLabel(event({ eventType: "SIDECAR" }))).toBe("사이드카 발동 중");
  });
});
