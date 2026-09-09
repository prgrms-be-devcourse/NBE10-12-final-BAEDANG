import { describe, it, expect } from "vitest";
import { CANDLE_UNIT_DEFAULT_PERIOD, CANDLE_UNIT_PERIODS, toCandleQuery } from "../candle-query";

// 백엔드 CandleQueryPolicy(back/src/main/java/com/baedang/stock/service/CandleQueryPolicy.java)가
// 허용하는 조합과 정확히 같아야 한다 — 여기가 그 조합을 프론트에서 그대로 옮긴 곳이다.
describe("CANDLE_UNIT_PERIODS — 봉 단위별 허용 기간", () => {
  it("각 봉 단위에서 고를 수 있는 기간이 백엔드 허용 조합과 일치한다", () => {
    expect(CANDLE_UNIT_PERIODS["1분봉"]).toEqual(["1일"]);
    expect(CANDLE_UNIT_PERIODS["5분봉"]).toEqual(["1일", "1주일"]);
    expect(CANDLE_UNIT_PERIODS["10분봉"]).toEqual(["1주일"]);
    expect(CANDLE_UNIT_PERIODS["일봉"]).toEqual(["1개월", "6개월", "1년"]);
    expect(CANDLE_UNIT_PERIODS["1주봉"]).toEqual(["6개월", "1년"]);
  });

  it("3년(3Y)은 어떤 봉 단위에도 포함되지 않는다(계획에서 삭제됨)", () => {
    for (const periods of Object.values(CANDLE_UNIT_PERIODS)) {
      expect(periods).not.toContain("3년");
    }
  });
});

describe("toCandleQuery — 유효한 조합 전체", () => {
  it("1분봉은 항상 1D", () => {
    expect(toCandleQuery("1분봉", "1일")).toEqual({ interval: "1m", range: "1D" });
  });

  it("5분봉은 1일 또는 1주일", () => {
    expect(toCandleQuery("5분봉", "1일")).toEqual({ interval: "5m", range: "1D" });
    expect(toCandleQuery("5분봉", "1주일")).toEqual({ interval: "5m", range: "1W" });
  });

  it("10분봉은 항상 1주일", () => {
    expect(toCandleQuery("10분봉", "1주일")).toEqual({ interval: "10m", range: "1W" });
  });

  it("일봉은 1개월/6개월/1년", () => {
    expect(toCandleQuery("일봉", "1개월")).toEqual({ interval: "1d", range: "1M" });
    expect(toCandleQuery("일봉", "6개월")).toEqual({ interval: "1d", range: "6M" });
    expect(toCandleQuery("일봉", "1년")).toEqual({ interval: "1d", range: "1Y" });
  });

  it("1주봉은 6개월 또는 1년", () => {
    expect(toCandleQuery("1주봉", "6개월")).toEqual({ interval: "1w", range: "6M" });
    expect(toCandleQuery("1주봉", "1년")).toEqual({ interval: "1w", range: "1Y" });
  });
});

describe("toCandleQuery — 그 단위에서 유효하지 않은 기간이 들어와도 안전한 기본값으로 대체", () => {
  it("5분봉에 일봉 전용 기간(1개월)이 들어오면 1D로 대체", () => {
    expect(toCandleQuery("5분봉", "1개월")).toEqual({ interval: "5m", range: "1D" });
  });

  it("일봉에 분봉 전용 기간(1일)이 들어오면 6M로 대체", () => {
    expect(toCandleQuery("일봉", "1일")).toEqual({ interval: "1d", range: "6M" });
  });

  it("1주봉에 일봉 전용 기간(1개월)이 들어오면 6M로 대체", () => {
    expect(toCandleQuery("1주봉", "1개월")).toEqual({ interval: "1w", range: "6M" });
  });
});

describe("CANDLE_UNIT_DEFAULT_PERIOD — 각 기본값이 실제로 그 단위의 유효한 기간이다", () => {
  it("모든 봉 단위의 기본 기간이 CANDLE_UNIT_PERIODS 목록 안에 있다", () => {
    for (const unit of Object.keys(CANDLE_UNIT_DEFAULT_PERIOD) as (keyof typeof CANDLE_UNIT_DEFAULT_PERIOD)[]) {
      expect(CANDLE_UNIT_PERIODS[unit]).toContain(CANDLE_UNIT_DEFAULT_PERIOD[unit]);
    }
  });
});
