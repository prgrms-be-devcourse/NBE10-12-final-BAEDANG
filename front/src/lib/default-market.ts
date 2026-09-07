import type { MarketCountry } from "@/lib/api";

/**
 * 랭킹 화면에 처음 들어왔을 때 기본으로 보여줄 시장 탭을 정한다.
 *
 * <p>국내장 시간대엔 국내(KR), 해외장 시간대엔 해외(US)가 기본이어야 한다는
 * 요구사항을 그대로 옮긴 것 — 국내장이 열려 있으면 무조건 국내를 우선하고
 * (자국 시장이 홈), 국내장이 닫혀 있을 때만 해외장이 열려 있는지로 판단한다.
 * 두 시장이 모두 닫혀 있으면(예: 주말) 국내를 기본값으로 유지한다 — 실제
 * 거래 시간대(KR 09:00~15:30, US를 KST로 환산한 시간대)는 서로 겹치지 않아
 * "둘 다 열려 있음"은 정상적으로는 나오지 않는 경우지만, 나오더라도 국내를
 * 우선한다.
 */
export function pickDefaultMarket(isKrOpen: boolean, isUsOpen: boolean): MarketCountry {
  return !isKrOpen && isUsOpen ? "US" : "KR";
}
