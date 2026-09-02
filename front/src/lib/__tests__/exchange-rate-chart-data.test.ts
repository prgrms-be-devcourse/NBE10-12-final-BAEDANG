import { describe, it, expect } from "vitest";
import { TickMarkType, type UTCTimestamp } from "lightweight-charts";
import { formatTickMark, isTimeVisible, toLinePoints } from "../exchange-rate-chart-data";
import type { ExchangeRateHistoryItem } from "../api";

/** 로컬 타임존에 상관없이 항상 같은 로컬 날짜/시각이 되도록 초 단위 타임스탬프를 만든다. */
function localTime(year: number, month: number, day: number, hour = 0, minute = 0): UTCTimestamp {
  return (new Date(year, month - 1, day, hour, minute, 0).getTime() / 1000) as UTCTimestamp;
}

function item(rateAt: string, rate: string): ExchangeRateHistoryItem {
  return { rateAt, rate };
}

describe("isTimeVisible", () => {
  it("1일 기간에서는 시:분 표시가 필요하다", () => {
    expect(isTimeVisible("1d")).toBe(true);
  });

  it("1일보다 넓은 기간에서는 시:분 표시가 필요 없다", () => {
    expect(isTimeVisible("1w")).toBe(false);
    expect(isTimeVisible("1m")).toBe(false);
    expect(isTimeVisible("3m")).toBe(false);
    expect(isTimeVisible("1y")).toBe(false);
  });
});

describe("toLinePoints", () => {
  it("1일 기간에서는 시간 단위 원본 그대로 반환한다", () => {
    const items = [
      item("2026-09-01T00:00:00Z", "1370.00"),
      item("2026-09-01T01:00:00Z", "1371.00"),
      item("2026-09-01T02:00:00Z", "1372.00"),
    ];

    const points = toLinePoints(items, "1d");

    expect(points).toHaveLength(3);
    expect(points.map((p) => p.value)).toEqual([1370, 1371, 1372]);
  });

  it("1개월 기간에서는 같은 날짜의 여러 시간 단위 값을 하루 한 점으로 묶는다", () => {
    const items = [
      item("2026-08-01T00:00:00Z", "1360.00"),
      item("2026-08-01T09:00:00Z", "1365.00"),
      item("2026-08-01T23:00:00Z", "1368.00"), // 8/1의 마지막 값 — 대표값이어야 함
      item("2026-08-02T05:00:00Z", "1370.00"),
    ];

    const points = toLinePoints(items, "1m");

    expect(points).toHaveLength(2);
    expect(points[0].value).toBe(1368); // 8/1 하루 중 가장 나중 값
    expect(points[1].value).toBe(1370); // 8/2
  });

  it("1년 기간에서는 같은 주의 값을 한 점으로 묶는다", () => {
    const items = [
      item("2026-01-05T00:00:00Z", "1350.00"), // 같은 주
      item("2026-01-06T00:00:00Z", "1352.00"), // 같은 주 — 나중 값이 대표값
      item("2026-02-16T00:00:00Z", "1380.00"), // 다른 주
    ];

    const points = toLinePoints(items, "1y");

    expect(points).toHaveLength(2);
    expect(points[0].value).toBe(1352);
    expect(points[1].value).toBe(1380);
  });

  it("입력 순서가 뒤섞이거나 시각이 중복돼도 오름차순으로 정렬하고 같은 버킷은 최신 원본 값을 취한다", () => {
    const items = [
      item("2026-08-02T05:00:00Z", "1370.00"),
      item("2026-08-01T23:00:00Z", "1368.00"),
      item("2026-08-01T09:00:00Z", "1365.00"), // 8/1 버킷 안에서는 더 나중이 아니므로 무시돼야 함
    ];

    const points = toLinePoints(items, "1m");

    expect(points.map((p) => p.value)).toEqual([1368, 1370]);
    expect(points[0].time).toBeLessThan(points[1].time);
  });

  it("숫자로 변환할 수 없는 값은 건너뛴다", () => {
    const items = [item("2026-09-01T00:00:00Z", "not-a-number"), item("2026-09-01T01:00:00Z", "1371.00")];

    const points = toLinePoints(items, "1d");

    expect(points).toHaveLength(1);
    expect(points[0].value).toBe(1371);
  });
});

describe("formatTickMark", () => {
  it("연/월/일 눈금은 월이 바뀌는 지점이 아니어도 항상 'M월 d일' 형식으로 표기한다", () => {
    const time = localTime(2026, 8, 31);

    expect(formatTickMark(time, TickMarkType.DayOfMonth)).toBe("8월 31일");
    expect(formatTickMark(time, TickMarkType.Month)).toBe("8월 31일");
    expect(formatTickMark(time, TickMarkType.Year)).toBe("8월 31일");
  });

  it("월이 바뀌는 지점의 눈금도 날짜까지 함께 표기한다", () => {
    const time = localTime(2026, 9, 1);

    expect(formatTickMark(time, TickMarkType.Month)).toBe("9월 1일");
  });

  it("시:분 눈금은 시간만 HH:mm으로 표기한다", () => {
    const time = localTime(2026, 9, 1, 8, 59);

    expect(formatTickMark(time, TickMarkType.Time)).toBe("08:59");
    expect(formatTickMark(time, TickMarkType.TimeWithSeconds)).toBe("08:59");
  });
});
