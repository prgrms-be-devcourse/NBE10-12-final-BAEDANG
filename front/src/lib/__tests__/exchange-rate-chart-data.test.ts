import { describe, it, expect } from "vitest";
import { TickMarkType, type UTCTimestamp } from "lightweight-charts";
import { formatCrosshairTime, formatTickMark, isTimeVisible, toLinePoints } from "../exchange-rate-chart-data";
import type { ExchangeRateHistoryItem, ExchangeRatePeriod } from "../api";

/** 실행 환경과 무관하게 KST 날짜/시각을 나타내는 UTC timestamp를 만듭니다. */
function localTime(year: number, month: number, day: number, hour = 0, minute = 0): UTCTimestamp {
  return (Date.UTC(year, month - 1, day, hour - 9, minute) / 1000) as UTCTimestamp;
}

function item(validFrom: string, rate: string): ExchangeRateHistoryItem {
  return { validFrom, rate };
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
  it.each<[ExchangeRatePeriod, number]>([
    ["1d", 60], ["1w", 1800], ["1m", 7200], ["3m", 21600], ["1y", 86400],
  ])("%s 버킷은 KST 자정에 정렬되며 경계 직전과 직후를 구분한다", (period, seconds) => {
    const start = localTime(2026, 9, 1);
    const items = [0, seconds - 1, seconds].map((offset, index) =>
      item(new Date((start + offset) * 1000).toISOString(), String(1370 + index)));
    expect(toLinePoints(items, period)).toEqual([
      { time: start, value: 1371 }, { time: start + seconds, value: 1372 },
    ]);
  });
  it("1일 기간에서는 같은 시간 안의 분별 점을 보존한다", () => {
    const items = [
      item("2026-09-01T00:00:00Z", "1370.00"),
      item("2026-09-01T00:01:00Z", "1371.00"),
      item("2026-09-01T00:02:00Z", "1372.00"),
    ];

    const points = toLinePoints(items, "1d");

    expect(points).toHaveLength(3);
    expect(points.map((p) => p.value)).toEqual([1370, 1371, 1372]);
    expect(points[1].time - points[0].time).toBe(60);
  });

  it("1개월 기간에서는 KST 2시간 버킷의 마지막 값을 선택한다", () => {
    const items = [
      item("2026-08-01T00:00:00+09:00", "1360.00"),
      item("2026-08-01T01:00:00+09:00", "1365.00"),
      item("2026-08-01T01:59:00+09:00", "1368.00"),
      item("2026-08-01T02:00:00+09:00", "1370.00"),
    ];

    const points = toLinePoints(items, "1m");

    expect(points).toHaveLength(2);
    expect(points).toEqual([
      { time: localTime(2026, 8, 1), value: 1368 },
      { time: localTime(2026, 8, 1, 2), value: 1370 },
    ]);
  });

  it("1년 기간에서는 KST 자정 경계로 하루 한 점을 선택한다", () => {
    const items = [
      item("2026-01-05T00:00:00Z", "1350.00"),
      item("2026-01-05T14:59:00Z", "1352.00"),
      item("2026-01-05T15:00:00Z", "1380.00"),
    ];

    const points = toLinePoints(items, "1y");

    expect(points).toHaveLength(2);
    expect(points[0].value).toBe(1352);
    expect(points[1].value).toBe(1380);
    expect(points[1].time).toBe(localTime(2026, 1, 6));
  });

  it("입력 순서가 뒤섞이거나 시각이 중복돼도 오름차순으로 정렬하고 같은 버킷은 최신 원본 값을 취한다", () => {
    const items = [
      item("2026-08-02T05:00:00Z", "1370.00"),
      item("2026-08-01T14:00:00Z", "1368.00"),
      item("2026-08-01T13:00:00Z", "1365.00"),
    ];

    const points = toLinePoints(items, "1y");

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
  it.each<ExchangeRatePeriod>(["1w", "1m", "3m", "1y"])("%s 십자선은 시각 없이 KST 날짜만 표시한다", (period) => {
    expect(formatCrosshairTime(localTime(2026, 9, 1, 12, 30), period)).toBe("2026-09-01");
  });
  it("UTC 날짜가 전날이어도 축과 십자선은 KST 자정으로 표시한다", () => {
    const time = (Date.parse("2026-08-31T15:00:00Z") / 1000) as UTCTimestamp;
    expect(formatTickMark(time, TickMarkType.Time)).toBe("00:00");
    expect(formatTickMark(time, TickMarkType.DayOfMonth)).toBe("9월 1일");
    expect(formatCrosshairTime(time, "1d")).toBe("2026-09-01 00:00");
  });
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
