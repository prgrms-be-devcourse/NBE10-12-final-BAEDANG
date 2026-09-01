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

  it("응답이 시간 역순으로 뒤섞여 와도 오름차순으로 정렬한다", () => {
    // lightweight-charts는 오름차순이 아니면 렌더링을 통째로 멈춘다(제미나이 리뷰, PR #82).
    const items = [
      candle({ at: "2026-08-29T00:00:00+09:00", close: "110" }),
      candle({ at: "2026-08-27T00:00:00+09:00", close: "100" }),
      candle({ at: "2026-08-28T00:00:00+09:00", close: "105" }),
    ];

    const result = toCandlestickData(items);

    expect(result.map((p) => p.close)).toEqual([100, 105, 110]);
    expect(result[0].time).toBeLessThan(result[1].time);
    expect(result[1].time).toBeLessThan(result[2].time);
  });

  it("같은 시각이 중복되면 먼저 온 값만 남긴다", () => {
    const items = [
      candle({ at: "2026-08-28T00:00:00+09:00", close: "100" }),
      candle({ at: "2026-08-28T00:00:00+09:00", close: "999" }), // 같은 시각 — 무시돼야 함
      candle({ at: "2026-08-29T00:00:00+09:00", close: "110" }),
    ];

    const result = toCandlestickData(items);

    expect(result).toHaveLength(2);
    expect(result[0].close).toBe(100);
    expect(result[1].close).toBe(110);
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

  it("응답이 뒤섞여 와도 오름차순으로 정렬하고 중복 시각은 먼저 온 값만 남긴다", () => {
    const items = [
      candle({ at: "2026-08-29T00:00:00+09:00", volume: "300" }),
      candle({ at: "2026-08-27T00:00:00+09:00", volume: "100" }),
      candle({ at: "2026-08-27T00:00:00+09:00", volume: "999" }), // 중복 시각 — 무시돼야 함
    ];

    const result = toVolumeData(items, "red", "blue");

    expect(result.map((p) => p.value)).toEqual([100, 300]);
  });
});
