/**
 * 환율(USD/KRW) 조회. 정책상 거래는 원화로만 제공되고, 미국 종목의 달러 데이터는
 * 프론트에서 환율을 곱해 원화로 환산해 보여주기로 결정했습니다 (건우님 회의 기록,
 * 2026-08-26 — 주식 랭킹·마이페이지의 미국 종목 표시가 대상).
 *
 * <p>환율은 1시간에 한 번 갱신되는 정책이라({@code docs/erd.md} 참고),
 * {@link import("@/components/ExchangeRateProvider").ExchangeRateProvider} 가
 * 이 함수를 최초 1회 + 매 1시간마다 다시 호출해서 값을 갱신합니다.
 *
 * <p>`GET /api/exchange-rates/latest`(back/src/main/java/com/baedang/market)를 호출합니다.
 * 백엔드가 안 떠 있거나 아직 환율 데이터가 없을 때는(EXCHANGE_RATE_NOT_FOUND 등)
 * 배너 자체가 깨지면 안 되므로 기본값으로 대체합니다.
 */

import { getExchangeRateLatest } from "@/lib/api";

export type ExchangeRateInfo = {
  rate: number; // 1 USD당 원화
  changeAmount: number; // 전일 자정(00:00 KST) 대비 등락(원)
  changeRate: number; // 전일 자정(00:00 KST) 대비 등락률(비율, 0.0016 = +0.16%)
  updatedAt: Date;
};

/** 백엔드 호출이 실패했을 때의 대체값 겸 초기 렌더링용 값. */
export const DEFAULT_USD_KRW_RATE = 1398.5;
const DEFAULT_CHANGE_AMOUNT = 2.3;
const DEFAULT_CHANGE_RATE = 0.0016;

export async function fetchExchangeRate(): Promise<ExchangeRateInfo> {
  try {
    const latest = await getExchangeRateLatest();
    return {
      rate: Number(latest.rate),
      changeAmount: Number(latest.changeAmount),
      changeRate: Number(latest.changeRate),
      updatedAt: new Date(latest.rateAt),
    };
  } catch {
    return {
      rate: DEFAULT_USD_KRW_RATE,
      changeAmount: DEFAULT_CHANGE_AMOUNT,
      changeRate: DEFAULT_CHANGE_RATE,
      updatedAt: new Date(),
    };
  }
}
