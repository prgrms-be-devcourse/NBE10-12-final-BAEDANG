import { describe, it, expect, vi, beforeEach } from "vitest";
import { fetchExchangeRate, exchangeRateStateAfterRefresh, INITIAL_EXCHANGE_RATE_STATE } from "../exchange-rate";

const response = {
  baseCurrency: "USD", quoteCurrency: "KRW", rate: "1400.000000",
  changeAmount: "2.000000", changeRate: "0.001431", validFrom: "2026-08-26T15:00:00+09:00",
};
function mockFetch(status: number, body: unknown) {
  vi.spyOn(globalThis, "fetch").mockResolvedValueOnce({
    ok: status >= 200 && status < 300, status, json: () => Promise.resolve(body),
  } as Response);
}
beforeEach(() => vi.restoreAllMocks());

describe("환율 조회", () => {
  it("정상 환율과 원본 시각을 반환한다", async () => {
    mockFetch(200, response);
    expect(await fetchExchangeRate()).toEqual({
      rate: 1400, changeAmount: 2, changeRate: 0.001431, updatedAt: new Date(response.validFrom),
    });
  });
  it("환율 미적재 오류를 임의 값으로 대체하지 않는다", async () => {
    mockFetch(404, { code: "EXCHANGE_RATE_NOT_FOUND", message: "환율 정보 없음" });
    await expect(fetchExchangeRate()).rejects.toThrow();
  });
  it("네트워크 실패를 호출부에 전달한다", async () => {
    vi.spyOn(globalThis, "fetch").mockRejectedValueOnce(new Error("network down"));
    await expect(fetchExchangeRate()).rejects.toThrow();
  });
  it.each([
    { rate: "" }, { rate: "not-a-number" }, { rate: "Infinity" },
    { rate: "0" }, { rate: "-1" }, { changeAmount: "" },
    { changeRate: "Infinity" }, { validFrom: "invalid" }, { validFrom: null },
  ])("비정상 응답 %j를 거절한다", async (invalid) => {
    mockFetch(200, { ...response, ...invalid });
    await expect(fetchExchangeRate()).rejects.toThrow("환율 응답 형식");
  });
});

describe("화면 환율 상태", () => {
  it("최초 실패는 값 없음으로 남기고 재조회 성공 시 회복한다", () => {
    const failed = exchangeRateStateAfterRefresh(INITIAL_EXCHANGE_RATE_STATE, null);
    expect(failed).toEqual({
      rate: null, changeAmount: null, changeRate: null, updatedAt: null, isLoading: false, hasError: true,
    });
    const info = { rate: 1400, changeAmount: 2, changeRate: 0.001431, updatedAt: new Date(response.validFrom) };
    expect(exchangeRateStateAfterRefresh(failed, info)).toEqual({ ...info, isLoading: false, hasError: false });
  });
  it("후속 실패는 마지막 정상값과 원본 시각을 보존하고 복구 시 오류를 해제한다", () => {
    const info = { rate: 1400, changeAmount: 2, changeRate: 0.001431, updatedAt: new Date(response.validFrom) };
    const loaded = exchangeRateStateAfterRefresh(INITIAL_EXCHANGE_RATE_STATE, info);
    const failed = exchangeRateStateAfterRefresh(loaded, null);
    expect(failed).toEqual({ ...info, isLoading: false, hasError: true });
    const next = { ...info, rate: 1401, updatedAt: new Date("2026-08-26T06:01:00Z") };
    expect(exchangeRateStateAfterRefresh(failed, next)).toEqual({ ...next, isLoading: false, hasError: false });
  });
});
