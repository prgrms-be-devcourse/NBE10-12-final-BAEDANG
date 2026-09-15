"use client";

import { createContext, useContext, useEffect, useState, type ReactNode } from "react";
import { getMarketStatus, type MarketCountry, type MarketStatusItem } from "@/lib/api";
import { useVisiblePolling } from "@/lib/useVisiblePolling";

// 시장 개장 여부는 AGENTS.md 원칙대로 절대 하드코딩하지 않고, 항상 백엔드
// /api/market/status(내부적으로 /market-calendar 캐시 기반이라 DST도 자동
// 반영된다)를 그대로 따른다. 개장·마감 전환을 놓치지 않을 정도로만(1분) 다시
// 조회한다 — 이 값 자체가 자주 바뀌는 데이터가 아니라서 더 잦은 폴링은 불필요하다.
const REFRESH_INTERVAL_MS = 60 * 1000;

type MarketStatusState = {
  markets: MarketStatusItem[];
  isLoading: boolean;
};

const MarketStatusContext = createContext<MarketStatusState | null>(null);

/**
 * 국내/해외 시장이 지금 정규장인지, 앱 전체가 같은 값을 보도록 한 곳에서만
 * 조회해서 내려주는 컨텍스트.
 *
 * <p>랭킹·종목 상세·마이페이지의 시세/차트 폴링이 "장 시간대에만 폴링한다"는
 * 규칙을 지키려면 다들 이 값(`useMarketStatus().isOpen(...)`)을 참조해서
 * `useVisiblePolling`의 `enabled`를 결정한다 — 장이 닫혀 있으면 어차피
 * quote_snapshot/minute_candle 자체가 그 주기로 갱신되지 않으므로, 폴링해봐야
 * 더 신선한 데이터를 받을 수 없다.
 */
export function MarketStatusProvider({ children }: { children: ReactNode }) {
  const [state, setState] = useState<MarketStatusState>({ markets: [], isLoading: true });

  function load() {
    return getMarketStatus()
      .then((status) => setState({ markets: status.markets, isLoading: false }))
      .catch(() => {
        // 최초 로딩 자체가 실패하면 로딩 상태만 풀어서 화면이 갇히지 않게 한다.
        // 이미 값을 받아온 적이 있다면(폴링 중 일시 실패) 마지막 상태를 그대로 둔다.
        setState((prev) => (prev.isLoading ? { markets: [], isLoading: false } : prev));
      });
  }

  useEffect(() => {
    load();
  }, []);

  useVisiblePolling(load, REFRESH_INTERVAL_MS, true);

  return <MarketStatusContext.Provider value={state}>{children}</MarketStatusContext.Provider>;
}

export function useMarketStatus(): MarketStatusState & { isOpen: (marketCountry: MarketCountry) => boolean } {
  const ctx = useContext(MarketStatusContext);
  if (!ctx) throw new Error("useMarketStatus는 MarketStatusProvider 안에서만 쓸 수 있습니다.");
  return {
    ...ctx,
    isOpen: (marketCountry) => ctx.markets.find((m) => m.marketCountry === marketCountry)?.open ?? false,
  };
}
