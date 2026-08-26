"use client";

import { createContext, useContext, useEffect, useState, type ReactNode } from "react";
import { DEFAULT_USD_KRW_RATE, fetchExchangeRate } from "@/lib/exchange-rate";

const REFRESH_INTERVAL_MS = 60 * 60 * 1000; // 1시간마다 갱신 (docs/erd.md 환율 수집 주기와 동일)

type ExchangeRateState = {
  rate: number;
  updatedAt: Date;
  isLoading: boolean;
};

const ExchangeRateContext = createContext<ExchangeRateState | null>(null);

/**
 * 앱 전체가 같은 환율 값을 보도록 한 곳에서만 조회해서 내려주는 컨텍스트입니다.
 * 랭킹·마이페이지·종목 상세 거래 패널이 각자 다른 환율을 들고 있으면 화면마다
 * 원화 환산액이 미묘하게 달라지는 문제가 생기므로, 이 컨텍스트 하나만 쓰세요.
 */
export function ExchangeRateProvider({ children }: { children: ReactNode }) {
  const [state, setState] = useState<ExchangeRateState>({
    rate: DEFAULT_USD_KRW_RATE,
    updatedAt: new Date(),
    isLoading: true,
  });

  useEffect(() => {
    let cancelled = false;

    async function load() {
      const info = await fetchExchangeRate();
      if (cancelled) return;
      setState({ rate: info.rate, updatedAt: info.updatedAt, isLoading: false });
    }

    load();
    const intervalId = setInterval(load, REFRESH_INTERVAL_MS);
    return () => {
      cancelled = true;
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
