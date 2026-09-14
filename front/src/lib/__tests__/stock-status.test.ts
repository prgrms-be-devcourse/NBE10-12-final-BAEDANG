import { describe, it, expect } from "vitest";
import { buildStatusBadges, resolveBlockReason } from "../stock-status";
import { EMPTY_STOCK_MARKET_EVENT_STATE, classifyActiveEvents } from "../stock-market-events";
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

const available = { warnings: [{ type: "OVERHEATED", label: "과열종목" }], warningsStatus: "AVAILABLE" } as const;
const noWarnings = { warnings: [], warningsStatus: "AVAILABLE" } as const;
const unknownWarnings = { warnings: [], warningsStatus: "UNAVAILABLE" } as const;

describe("buildStatusBadges — 주문을 막지 않는 상태만 종목명 옆에 붙인다", () => {
  it("유의사항 배지는 서버가 내려준 종류별 문구를 그대로 쓴다", () => {
    const badges = buildStatusBadges(available, EMPTY_STOCK_MARKET_EVENT_STATE);

    expect(badges).toHaveLength(1);
    expect(badges[0].label).toBe("과열종목");
    expect(badges[0].tone).toBe("warn");
  });

  it("서로 다른 종류가 섞이면 각각의 문구로 배지를 만든다", () => {
    const mixed = {
      warnings: [
        { type: "OVERHEATED", label: "과열종목" },
        { type: "INVESTMENT_WARNING", label: "투자경고" },
      ],
      warningsStatus: "AVAILABLE",
    } as const;

    const badges = buildStatusBadges(mixed, EMPTY_STOCK_MARKET_EVENT_STATE);

    expect(badges.map((badge) => badge.label)).toEqual(["과열종목", "투자경고"]);
  });

  it("같은 문구가 중복으로 내려와도 배지는 한 번만 만든다", () => {
    const dup = {
      warnings: [
        { type: "OVERHEATED", label: "과열종목" },
        { type: "OVERHEATED", label: "과열종목" },
      ],
      warningsStatus: "AVAILABLE",
    } as const;

    const badges = buildStatusBadges(dup, EMPTY_STOCK_MARKET_EVENT_STATE);

    expect(badges).toHaveLength(1);
  });

  it("유의사항 조회가 실패했으면 배지를 만들지 않는다", () => {
    // 확인하지 못한 것을 "유의사항 없음"으로도, 없는 경고로도 만들면 안 된다.
    expect(buildStatusBadges(unknownWarnings, EMPTY_STOCK_MARKET_EVENT_STATE)).toEqual([]);
  });

  it("유의사항이 없으면 배지를 만들지 않는다", () => {
    expect(buildStatusBadges(noWarnings, EMPTY_STOCK_MARKET_EVENT_STATE)).toEqual([]);
  });

  it("활성 사이드카는 방향을 포함한 배지로 만든다", () => {
    const events = classifyActiveEvents([
      event({ eventId: 4, eventType: "SIDECAR", direction: "SELL" }),
    ]);

    const badges = buildStatusBadges(noWarnings, events);

    expect(badges.map((badge) => badge.label)).toEqual(["사이드카 매도 발동 중"]);
  });

  it("서킷브레이커는 배지로 만들지 않는다 — 버튼이 이미 같은 사실을 말한다", () => {
    const events = classifyActiveEvents([event({ eventId: 9, eventType: "CIRCUIT_BREAKER", stage: 2 })]);

    expect(buildStatusBadges(noWarnings, events)).toEqual([]);
  });

  it("유의사항과 사이드카가 함께 있으면 둘 다 만든다", () => {
    const events = classifyActiveEvents([event({ eventId: 4, eventType: "SIDECAR", direction: "BUY" })]);

    const badges = buildStatusBadges(available, events);

    expect(badges.map((badge) => badge.label)).toEqual(["과열종목", "사이드카 매수 발동 중"]);
  });
});

describe("resolveBlockReason — 거래를 막는 상태만 버튼 사유가 된다", () => {
  const tradable = { tradable: true, tradableReason: null, market: "KOSPI" } as const;

  it("차단 상태가 없으면 금액·수량 사유를 그대로 쓴다", () => {
    expect(resolveBlockReason({ detail: tradable, events: EMPTY_STOCK_MARKET_EVENT_STATE, amountReason: null })).toBeNull();
    expect(
      resolveBlockReason({ detail: tradable, events: EMPTY_STOCK_MARKET_EVENT_STATE, amountReason: "주문가능금액이 부족해요" }),
    ).toBe("주문가능금액이 부족해요");
  });

  it("거래정지와 정리매매는 종목 사유로 막는다", () => {
    expect(
      resolveBlockReason({
        detail: { tradable: false, tradableReason: "SUSPENDED", market: "KOSPI" },
        events: EMPTY_STOCK_MARKET_EVENT_STATE,
        amountReason: null,
      }),
    ).toBe("거래정지 종목이에요");
    expect(
      resolveBlockReason({
        detail: { tradable: false, tradableReason: "LIQUIDATION", market: "KOSPI" },
        events: EMPTY_STOCK_MARKET_EVENT_STATE,
        amountReason: null,
      }),
    ).toBe("정리매매 종목이에요");
  });

  it("활성 서킷브레이커는 국내 종목의 주문을 막는다", () => {
    const events = classifyActiveEvents([event({ eventType: "CIRCUIT_BREAKER", stage: 1 })]);

    expect(resolveBlockReason({ detail: tradable, events, amountReason: null })).toBe("서킷브레이커 발동 중이에요");
  });

  it("종목 고유 사유가 시장 전체 사유보다 먼저다", () => {
    // 거래정지 종목이면서 CB도 발동된 경우 — 사용자에게 더 정확한 정보는 종목 사유다.
    const events = classifyActiveEvents([event({ eventType: "CIRCUIT_BREAKER", stage: 1 })]);

    expect(
      resolveBlockReason({
        detail: { tradable: false, tradableReason: "SUSPENDED", market: "KOSPI" },
        events,
        amountReason: null,
      }),
    ).toBe("거래정지 종목이에요");
  });

  it("사이드카만 활성이면 주문을 막지 않는다", () => {
    const events = classifyActiveEvents([event({ eventId: 4, eventType: "SIDECAR", direction: "BUY" })]);

    expect(resolveBlockReason({ detail: tradable, events, amountReason: null })).toBeNull();
    expect(resolveBlockReason({ detail: tradable, events, amountReason: "수량은 1주 이상의 정수로 입력해주세요" }))
      .toBe("수량은 1주 이상의 정수로 입력해주세요");
  });

  it("미국 종목은 국내 서킷브레이커로 막지 않는다", () => {
    const events = classifyActiveEvents([event({ eventType: "CIRCUIT_BREAKER", stage: 1 })]);

    expect(
      resolveBlockReason({
        detail: { tradable: true, tradableReason: null, market: "NASDAQ" },
        events,
        amountReason: null,
      }),
    ).toBeNull();
  });

  it("장 마감은 금액 사유보다 먼저 온다", () => {
    expect(
      resolveBlockReason({
        detail: { tradable: false, tradableReason: "MARKET_CLOSED", market: "KOSPI" },
        events: EMPTY_STOCK_MARKET_EVENT_STATE,
        amountReason: "주문가능금액이 부족해요",
      }),
    ).toBe("장 마감 · 거래 시간이 아니에요");
  });

  it("알 수 없는 사유 코드는 일반 문구로 대체한다", () => {
    expect(
      resolveBlockReason({
        detail: { tradable: false, tradableReason: "SOMETHING_NEW", market: "KOSPI" },
        events: EMPTY_STOCK_MARKET_EVENT_STATE,
        amountReason: null,
      }),
    ).toBe("지금은 거래할 수 없어요");
  });
});
