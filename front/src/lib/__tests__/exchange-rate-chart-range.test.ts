import { describe, expect, it } from "vitest";
import type { UTCTimestamp } from "lightweight-charts";
import { nextExchangeRateRange } from "../exchange-rate-chart-data";

describe("환율 차트 조회 범위", () => {
  it.each([
    [100, 200, 200, 260, 160, 260],
    [180, 200, 200, 260, 240, 260],
    [100, 150, 200, 260, 100, 150],
    [100, 200, 200, 200, 100, 200],
    [100, 200, 200, 190, 100, 200],
  ])("최신 구간만 이동하고 확대 폭/과거 범위를 유지한다 (%s~%s)", (from, to, previous, next, expectedFrom, expectedTo) => {
    expect(nextExchangeRateRange(
      { from: from as UTCTimestamp, to: to as UTCTimestamp },
      previous as UTCTimestamp, next as UTCTimestamp,
    )).toEqual({ from: expectedFrom, to: expectedTo });
  });
});
