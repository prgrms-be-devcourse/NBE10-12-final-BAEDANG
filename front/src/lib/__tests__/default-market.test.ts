import { describe, it, expect } from "vitest";
import { pickDefaultMarket } from "../default-market";

describe("pickDefaultMarket", () => {
  it("국내장이 열려 있으면(해외 여부와 무관하게) 국내를 기본값으로 고른다", () => {
    expect(pickDefaultMarket(true, false)).toBe("KR");
    expect(pickDefaultMarket(true, true)).toBe("KR"); // 자국 시장 우선
  });

  it("국내장이 닫혀 있고 해외장이 열려 있으면 해외를 기본값으로 고른다", () => {
    expect(pickDefaultMarket(false, true)).toBe("US");
  });

  it("둘 다 닫혀 있으면(주말 등) 국내를 기본값으로 유지한다", () => {
    expect(pickDefaultMarket(false, false)).toBe("KR");
  });
});
