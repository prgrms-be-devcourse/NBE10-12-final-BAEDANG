import type { CandleInterval, CandleRange } from "./api";

/**
 * 캔들 봉 단위 · 기간 화면 표기값과, 백엔드가 실제로 허용하는 조합
 * (`back/src/main/java/com/baedang/stock/service/CandleQueryPolicy.java`)을 그대로
 * 옮긴 매핑. 정책이 바뀌면 이 파일과 백엔드 `CandleQueryPolicy`를 같이 고쳐야 한다.
 *
 * <p>허용 조합(3Y는 계획에서 삭제됨):
 * 1분봉→1일, 5분봉→1일/1주일, 10분봉→1주일, 일봉→1개월/6개월/1년, 1주봉→6개월/1년.
 */
export type CandleUnit = "1분봉" | "5분봉" | "10분봉" | "일봉" | "1주봉";
export type CandlePeriod = "1일" | "1주일" | "1개월" | "6개월" | "1년";

/** 각 봉 단위에서 선택 가능한 기간 목록(화면에 보여줄 순서 그대로). 길이가 1이면
 * 기간을 고를 필요가 없다는 뜻이라, 호출부는 이 경우 기간 토글 자체를 숨긴다. */
export const CANDLE_UNIT_PERIODS: Readonly<Record<CandleUnit, readonly CandlePeriod[]>> = {
  "1분봉": ["1일"],
  "5분봉": ["1일", "1주일"],
  "10분봉": ["1주일"],
  "일봉": ["1개월", "6개월", "1년"],
  "1주봉": ["6개월", "1년"],
};

/** 봉 단위를 바꿀 때, 이전 기간이 새 단위에서 유효하지 않을 수 있어(예: 일봉의
 * "1년"은 5분봉에 없다) 되돌아갈 기본 기간. */
export const CANDLE_UNIT_DEFAULT_PERIOD: Readonly<Record<CandleUnit, CandlePeriod>> = {
  "1분봉": "1일",
  "5분봉": "1일",
  "10분봉": "1주일",
  "일봉": "6개월",
  "1주봉": "6개월",
};

/** 화면 표기값(봉 단위·기간)을 백엔드 쿼리 파라미터(interval·range)로 변환한다.
 * `period`가 그 단위에서 유효하지 않은 값으로 들어와도(있을 수 없는 상태 전이 등)
 * 죽지 않도록 각 단위의 기본 기간으로 안전하게 대체한다. */
export function toCandleQuery(unit: CandleUnit, period: CandlePeriod): { interval: CandleInterval; range: CandleRange } {
  switch (unit) {
    case "1분봉":
      return { interval: "1m", range: "1D" };
    case "5분봉":
      return { interval: "5m", range: period === "1주일" ? "1W" : "1D" };
    case "10분봉":
      return { interval: "10m", range: "1W" };
    case "1주봉":
      return { interval: "1w", range: period === "1년" ? "1Y" : "6M" };
    case "일봉":
    default:
      return { interval: "1d", range: period === "1개월" ? "1M" : period === "1년" ? "1Y" : "6M" };
  }
}
