"use client";

import { createContext, useContext, useEffect, useState, type ReactNode } from "react";
import { INITIAL_EXCHANGE_RATE_STATE, exchangeRateStateAfterRefresh, fetchExchangeRate, type ExchangeRateState } from "@/lib/exchange-rate";
import { createHistoryRefresh } from "@/lib/exchange-rate-history-refresh";

const REFRESH_INTERVAL_MS = 60 * 1000; // DB 환율 수집과 동일하게 1분마다 화면 값을 갱신합니다.

const ExchangeRateContext = createContext<ExchangeRateState | null>(null);

/**
 * 앱 전체가 같은 환율 값을 보도록 한 곳에서만 조회해서 내려주는 컨텍스트입니다.
 * 랭킹·마이페이지·종목 상세 거래 패널이 각자 다른 환율을 들고 있으면 화면마다
 * 원화 환산액이 미묘하게 달라지는 문제가 생기므로, 이 컨텍스트 하나만 쓰세요.
 */
export function ExchangeRateProvider({ children }: { children: ReactNode }) {
  const [state, setState] = useState<ExchangeRateState>(INITIAL_EXCHANGE_RATE_STATE);

  useEffect(() => {
    const request = createHistoryRefresh(fetchExchangeRate,
      (info) => setState((previous) => exchangeRateStateAfterRefresh(previous, info)),
      () => setState((previous) => exchangeRateStateAfterRefresh(previous, null)),
      () => {},
    );
    void request.refresh();
    const intervalId = setInterval(request.refresh, REFRESH_INTERVAL_MS);
    return () => {
      request.dispose();
      clearInterval(intervalId);
    };
  }, []);

  return <ExchangeRateContext.Provider value={state}>{children}</ExchangeRateContext.Provider>;
}

export function useExchangeRate(): ExchangeRateState {
  const ctx = useContext(ExchangeRateContext);
  if (!ctx) throw new Error("useExchangeRate는 ExchangeRateProvider 안에서만 쓸 수 있습니다.");
  return ctx;
}
