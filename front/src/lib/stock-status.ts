import type { StockDetail } from "./api";
import { sidecarLabel, toKrMarket, type StockMarketEventState } from "./stock-market-events";

/**
 * 종목명 옆에 붙는 상태 배지. **주문을 막지 않는 정보성 상태만** 여기 모은다.
 *
 * 거래를 실제로 막는 상태(거래정지·정리매매·서킷브레이커·장 마감)는 종목명 옆이 아니라
 * 매수/매도 버튼에 사유로 표시한다 — 사용자가 "왜 못 누르지"를 버튼에서 바로 알 수
 * 있어야 하고, 같은 사실을 두 곳에 다른 문구로 쓰면 어느 쪽이 정본인지 헷갈린다.
 */
export type StatusBadge = {
  /** React key 겸 안정적인 식별자. */
  key: string;
  /** 사용자에게 보이는 문구. 색만으로 의미를 전달하지 않는다. */
  label: string;
  /** 경고 계열(거래유의)인지 중립 계열(시장조치)인지 — 색 토큰 선택에만 쓴다. */
  tone: "warn" | "neutral";
};

/**
 * 배지 목록을 만든다.
 *
 * <p>유의사항은 {@code warningsStatus}가 `UNAVAILABLE`이면 표시하지 않는다 — 조회 실패를
 * "유의사항 없음"으로 오인해 경고를 조용히 감추면 안 되지만, 실패했다고 없는 경고를
 * 만들어낼 수도 없다.
 *
 * <p>서킷브레이커는 배지로 만들지 않는다. 버튼이 이미 `서킷브레이커 발동 중이에요`로
 * 막혀 있어서, 배지까지 붙이면 같은 사실이 두 번 보인다.
 */
export function buildStatusBadges(
  detail: Pick<StockDetail, "warnings" | "warningsStatus">,
  events: StockMarketEventState,
): StatusBadge[] {
  const badges: StatusBadge[] = [];

  if (detail.warningsStatus === "AVAILABLE" && detail.warnings.length > 0) {
    // 원천 경고 코드(OVERHEATED·INVESTMENT_WARNING·VI_STATIC 등)는 서로 다른 유형이지만
    // 초보 투자자 화면에서는 구분이 의미를 만들지 않아 하나로 보여준다.
    badges.push({ key: "warnings", label: "거래유의종목", tone: "warn" });
  }

  for (const sidecar of events.activeSidecars) {
    badges.push({
      key: `sidecar-${sidecar.eventId}`,
      label: sidecarLabel(sidecar),
      tone: "neutral",
    });
  }

  return badges;
}

/**
 * 매수/매도 버튼을 막는 사유. **차단 상태만** 여기 들어온다.
 *
 * <p>우선순위는 `거래정지 → 정리매매 → 서킷브레이커 → 장 마감 → 금액·수량` 순서다.
 * 종목 고유 사유가 시장 전체 사유보다 먼저다 — 둘 다 참일 때 사용자가 조치할 수 있는
 * 것은 없지만, "내 종목이 정지됐다"가 "시장이 멈췄다"보다 더 정확한 정보다.
 *
 * <p>서킷브레이커 조회가 실패했거나 아직 안 왔으면 여기서 막지 않는다 — 화면이 먼저
 * 막아버리면 서버가 허용하는 주문을 클라이언트가 거부하게 된다. 그 경우 서버 응답의
 * `MARKET_TRADING_HALTED`가 최종 방어선이다.
 */
export function resolveBlockReason(params: {
  detail: Pick<StockDetail, "tradable" | "tradableReason" | "market">;
  events: StockMarketEventState;
  amountReason: string | null;
}): string | null {
  const { detail, events, amountReason } = params;

  if (detail.tradableReason === "SUSPENDED") return "거래정지 종목이에요";
  if (detail.tradableReason === "LIQUIDATION") return "정리매매 종목이에요";

  // KOSPI/KOSDAQ만 시장조치 대상이다 — 미국 종목에 국내 CB를 들이대지 않는다.
  if (events.activeCircuitBreaker && toKrMarket(detail.market)) {
    return "서킷브레이커 발동 중이에요";
  }

  if (!detail.tradable && detail.tradableReason) {
    return TRADABLE_REASON_LABEL[detail.tradableReason] ?? "지금은 거래할 수 없어요";
  }
  if (!detail.tradable) return "지금은 거래할 수 없어요";

  return amountReason;
}

/**
 * 거래 불가 사유 문구. 종목 상세에서 쓰는 것과 같은 표를 공유한다 —
 * 사유 코드가 늘어났을 때 두 곳이 다른 말을 하면 안 된다.
 */
export const TRADABLE_REASON_LABEL: Record<string, string> = {
  MARKET_CLOSED: "장 마감 · 거래 시간이 아니에요",
  NOT_IN_UNIVERSE: "이 종목은 아직 거래를 지원하지 않아요",
  SUSPENDED: "거래정지 종목이에요",
  LIQUIDATION: "정리매매 종목이에요",
  QUOTE_NOT_FOUND: "시세 정보가 아직 없어요",
  PRICE_LIMIT_UNAVAILABLE: "당일 상하한가를 확인 중이에요. 잠시 후 다시 시도해주세요",
  PRICE_OUT_OF_RANGE: "주문 가격은 당일 하한가와 상한가 사이여야 해요",
  INVALID_TICK_SIZE: "주문 가격이 호가 단위에 맞지 않아요",
  QUOTE_OUT_OF_PRICE_LIMIT: "현재가를 다시 확인 중이에요. 잠시 후 다시 시도해주세요",
};
