import { getExchangeRateLatest } from "@/lib/api";

export type ExchangeRateInfo = {
  rate: number;
  changeAmount: number;
  changeRate: number;
  updatedAt: Date;
};

export type ExchangeRateState = {
  rate: number | null;
  changeAmount: number | null;
  changeRate: number | null;
  updatedAt: Date | null;
  isLoading: boolean;
  hasError: boolean;
};

export const INITIAL_EXCHANGE_RATE_STATE: ExchangeRateState = {
  rate: null, changeAmount: null, changeRate: null, updatedAt: null,
  isLoading: true, hasError: false,
};

/** 실패 시 마지막 정상 환율과 원본 시각을 보존합니다. 최초 실패는 값 없음 상태입니다. */
export function exchangeRateStateAfterRefresh(previous: ExchangeRateState, info: ExchangeRateInfo | null): ExchangeRateState {
  return info
    ? { ...info, isLoading: false, hasError: false }
    : { ...previous, isLoading: false, hasError: true };
}

function parseFiniteNumber(value: unknown): number {
  if (typeof value !== "string" || value.trim() === "") return NaN;
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : NaN;
}

/** 실패를 호출부에 전달합니다. 임의 환율이나 현재 시각으로 대체하지 않습니다. */
export async function fetchExchangeRate(signal?: AbortSignal): Promise<ExchangeRateInfo> {
  const latest = await getExchangeRateLatest("USD", "KRW", signal);
  const rate = parseFiniteNumber(latest.rate);
  const changeAmount = parseFiniteNumber(latest.changeAmount);
  const changeRate = parseFiniteNumber(latest.changeRate);
  const updatedAt = new Date(latest.validFrom);
  if ([rate, changeAmount, changeRate].some((n) => !Number.isFinite(n))
      || rate <= 0 || typeof latest.validFrom !== "string" || !Number.isFinite(updatedAt.getTime())) {
    throw new Error("환율 응답 형식이 올바르지 않아요");
  }
  return { rate, changeAmount, changeRate, updatedAt };
}
