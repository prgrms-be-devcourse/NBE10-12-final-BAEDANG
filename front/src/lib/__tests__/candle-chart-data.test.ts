import { describe, it, expect } from "vitest";
import { toCandlestickData, toVolumeData } from "../candle-chart-data";
import type { Candle } from "../api";

function candle(overrides: Partial<Candle> = {}): Candle {
  return {
    at: "2026-08-28T00:00:00+09:00",
    open: "100",
    high: "110",
    low: "90",
    close: "105",
    volume: "1000",
    ...overrides,
  };
}

describe("toCandlestickData", () => {
  it("문자열 OHLC를 숫자로, 시각을 초 단위 유닉스 타임스탬프로 변환한다", () => {
    const result = toCandlestickData([candle()]);

    expect(result).toEqual([
      {
        time: Math.floor(new Date("2026-08-28T00:00:00+09:00").getTime() / 1000),
        open: 100,
        high: 110,
        low: 90,
        close: 105,
      },
    ]);
  });

  it("여러 봉을 순서 그대로 변환한다", () => {
    const items = [
      candle({ at: "2026-08-27T00:00:00+09:00", close: "100" }),
      candle({ at: "2026-08-28T00:00:00+09:00", close: "105" }),
    ];

    const result = toCandlestickData(items);

    expect(result).toHaveLength(2);
    expect(result[0].close).toBe(100);
    expect(result[1].close).toBe(105);
    expect(result[0].time).toBeLessThan(result[1].time);
  });

  it("시각이나 가격이 숫자로 파싱되지 않는 봉은 건너뛴다", () => {
    const items = [
      candle({ open: "not-a-number" }),
      candle({ at: "invalid-date" }),
      candle({ close: "105" }), // 정상
    ];

    const result = toCandlestickData(items);

    expect(result).toHaveLength(1);
    expect(result[0].close).toBe(105);
  });

  it("빈 배열이면 빈 배열을 반환한다", () => {
    expect(toCandlestickData([])).toEqual([]);
  });
});

describe("toVolumeData", () => {
  it("종가가 시가보다 높으면(상승) upColor를 쓴다", () => {
    const result = toVolumeData([candle({ open: "100", close: "105", volume: "500" })], "red", "blue");

    expect(result).toEqual([{ time: expect.any(Number), value: 500, color: "red" }]);
  });

  it("종가가 시가보다 낮으면(하락) downColor를 쓴다", () => {
    const result = toVolumeData([candle({ open: "105", close: "100", volume: "500" })], "red", "blue");

    expect(result[0].color).toBe("blue");
  });

  it("종가와 시가가 같으면(보합) upColor를 쓴다", () => {
    const result = toVolumeData([candle({ open: "100", close: "100", volume: "500" })], "red", "blue");

    expect(result[0].color).toBe("red");
  });

  it("거래량이 없거나(null) 숫자가 아니면 그 봉은 건너뛴다", () => {
    const items = [candle({ volume: "not-a-number" }), candle({ volume: "700" })];

    const result = toVolumeData(items, "red", "blue");

    expect(result).toHaveLength(1);
    expect(result[0].value).toBe(700);
  });

  it("거래량이 음수로 오는 비정상 값은 0으로 자른다", () => {
    const result = toVolumeData([candle({ volume: "-10" })], "red", "blue");

    expect(result[0].value).toBe(0);
  });
});
