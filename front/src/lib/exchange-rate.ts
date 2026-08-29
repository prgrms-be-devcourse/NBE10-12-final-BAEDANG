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
 * 배너 자체가 깨지면 안 되므로 기본값으로 대체합니다. 다만 "데이터가 아직 없다"처럼
 * 예상 가능한 실패와 그 외의 예상 못한 에러(응답 형식이 깨졌다 등)는 구분해서,
 * 후자는 콘솔에 남겨 조용히 묻히지 않게 합니다.
 */

import { ApiError, getExchangeRateLatest } from "@/lib/api";

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

const DEFAULT_INFO: Omit<ExchangeRateInfo, "updatedAt"> = {
  rate: DEFAULT_USD_KRW_RATE,
  changeAmount: DEFAULT_CHANGE_AMOUNT,
  changeRate: DEFAULT_CHANGE_RATE,
};

export async function fetchExchangeRate(): Promise<ExchangeRateInfo> {
  try {
    const latest = await getExchangeRateLatest();
    const rate = Number(latest.rate);
    const changeAmount = Number(latest.changeAmount);
    const changeRate = Number(latest.changeRate);
    // 백엔드 응답 필드가 숫자로 파싱 안 되면(형식 오류 등) 조용히 NaN을 내보내는 대신
    // 명시적으로 실패시켜 아래 catch의 기본값 대체 경로를 타게 한다.
    if ([rate, changeAmount, changeRate].some((n) => Number.isNaN(n))) {
      throw new Error(`환율 응답 형식이 올바르지 않아요: ${JSON.stringify(latest)}`);
    }
    return { rate, changeAmount, changeRate, updatedAt: new Date(latest.rateAt) };
  } catch (err) {
    // 아직 데이터가 없는 경우(EXCHANGE_RATE_NOT_FOUND — 서비스 초기 등)는 예상 가능한
    // 실패라 조용히 기본값으로 대체하지만, 그 외 예상 못한 에러는 콘솔에 남겨서
    // "배너가 계속 기본값만 보여준다"는 증상의 원인을 나중에 추적할 수 있게 한다.
    const expected = err instanceof ApiError && err.code === "EXCHANGE_RATE_NOT_FOUND";
    if (!expected) console.warn("환율 조회 실패, 기본값으로 대체합니다.", err);
    return { ...DEFAULT_INFO, updatedAt: new Date() };
  }
}
