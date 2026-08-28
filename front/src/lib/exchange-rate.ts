/**
 * 환율(USD/KRW) 조회. 정책상 거래는 원화로만 제공되고, 미국 종목의 달러 데이터는
 * 프론트에서 환율을 곱해 원화로 환산해 보여주기로 결정했습니다 (건우님 회의 기록,
 * 2026-08-26 — 주식 랭킹·마이페이지의 미국 종목 표시가 대상).
 *
 * <p>환율은 1시간에 한 번 갱신되는 정책이라({@code docs/erd.md} 참고),
 * {@link import("@/components/ExchangeRateProvider").ExchangeRateProvider} 가
 * 이 함수를 최초 1회 + 매 1시간마다 다시 호출해서 값을 갱신합니다.
 *
 * <p>⚠️ <b>아직 백엔드에 `GET /exchange-rates/latest`를 노출하는 컨트롤러가 없어서</b>
 * (Port/Adapter 레이어까지만 구현돼 있음) 지금은 목값을 비동기로 흉내냅니다.
 * 실제 엔드포인트가 준비되면 이 함수 내부만 `fetch` 호출로 바꾸면 되고, 이 함수를
 * 쓰는 화면 코드(랭킹·마이페이지·상세 거래 패널)는 손댈 필요가 없습니다.
 *
 * <p>건우님 메모에 있던 "응답속도 테스트 후 백엔드-프론트 트레이드오프 논의"는
 * 실제 엔드포인트가 붙는 시점에 이 함수의 응답 시간을 재보고 진행하면 됩니다.
 */

export type ExchangeRateInfo = {
  rate: number; // 1 USD당 원화
  changeAmount: number; // 전일 대비 등락(원)
  changeRate: number; // 전일 대비 등락률(비율, 0.0016 = +0.16%)
  updatedAt: Date;
};

/** 실제 API가 없을 때의 기본값 겸 초기 렌더링용 값. */
export const DEFAULT_USD_KRW_RATE = 1398.5;
const DEFAULT_CHANGE_AMOUNT = 2.3;
const DEFAULT_CHANGE_RATE = 0.0016;

export async function fetchExchangeRate(): Promise<ExchangeRateInfo> {
  // 실제 네트워크 호출처럼 약간의 지연을 흉내낸다 — 응답속도 체감 테스트용.
  await new Promise((resolve) => setTimeout(resolve, 150));
  return {
    rate: DEFAULT_USD_KRW_RATE,
    changeAmount: DEFAULT_CHANGE_AMOUNT,
    changeRate: DEFAULT_CHANGE_RATE,
    updatedAt: new Date(),
  };
}
