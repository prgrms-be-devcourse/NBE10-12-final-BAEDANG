import { describe, expect, it, vi } from "vitest";
import { ApiError, type LimitOrderQuoteResponse } from "../api";
import { canRetryLimitQuote, createLimitQuoteRefresh, EMPTY_LIMIT_QUOTE, type LimitQuoteState } from "../limit-quote-refresh";

function quote(reason: string | null = null): LimitOrderQuoteResponse {
  return {
    requestedLimitPrice: "10000", requestedLimitCurrency: "KRW", limitPrice: "10000",
    acceptanceExchangeRate: "1", acceptable: reason === null, reason,
    availableCash: "1000000", availableQuantity: "10", expiresAt: "2026-09-14T06:30:00Z",
    limitEstimate: { grossAmount: "10000", fee: "1", tax: "0", netAmount: "10001", reservedCash: "10001" },
    executionPreview: {
      status: "UNAVAILABLE", reason: "NO_BOOK", bookVersion: null, revision: null,
      quoteAt: null, generatedAt: null, evaluatedAt: "2026-09-14T01:00:00Z",
      expectedFilledQuantity: null, remainingQuantity: null, avgExecutionPrice: null,
      grossAmountKrw: null, feeKrw: null, taxKrw: null, netAmountKrw: null,
      remainingReservedCash: null, releasedCash: null,
    },
  };
}

describe("지정가 미리보기 오류 복구", () => {
  it.each(["PRICE_LIMIT_UNAVAILABLE", "QUOTE_OUT_OF_PRICE_LIMIT"])(
    "%s 응답 후 입력 변경 없이 복구하고 정상화되면 재조회를 중단한다", async (code) => {
      const fetchQuote = vi.fn().mockResolvedValueOnce(quote(code)).mockResolvedValue(quote());
      let state: LimitQuoteState = EMPTY_LIMIT_QUOTE;
      const request = createLimitQuoteRefresh(fetchQuote, (next) => { state = next; });
      await request.refresh();
      expect(state.quote?.acceptable).toBe(false);
      expect(canRetryLimitQuote(state)).toBe(true);

      await request.retry();
      expect(state.quote?.acceptable).toBe(true);
      expect(state.error).toBeNull();
      expect(state.loading).toBe(false);
      expect(canRetryLimitQuote(state)).toBe(false);
      await request.retry();
      expect(fetchQuote).toHaveBeenCalledTimes(2);
    },
  );

  it.each(["PRICE_LIMIT_UNAVAILABLE", "QUOTE_OUT_OF_PRICE_LIMIT"])(
    "HTTP 오류 %s의 코드와 문구를 보존하고 다음 조회에서 복구한다", async (code) => {
      const fetchQuote = vi.fn().mockRejectedValueOnce(new ApiError(code, "시세 확인 중"))
        .mockResolvedValue(quote());
      let state: LimitQuoteState = EMPTY_LIMIT_QUOTE;
      const request = createLimitQuoteRefresh(fetchQuote, (next) => { state = next; });
      await request.refresh();
      expect(state.error).toEqual({ code, message: "시세 확인 중" });
      expect(canRetryLimitQuote(state)).toBe(true);
      await request.retry();
      expect(state.quote?.acceptable).toBe(true);
      expect(state.error).toBeNull();
      await request.retry();
      expect(fetchQuote).toHaveBeenCalledTimes(2);
    },
  );

  it.each(["PRICE_OUT_OF_RANGE", "INVALID_TICK_SIZE", "INSUFFICIENT_CASH", "MARKET_CLOSED"])(
    "%s는 응답 사유와 HTTP 오류 모두 자동 재조회하지 않는다", async (code) => {
      for (const result of [() => Promise.resolve(quote(code)), () => Promise.reject(new ApiError(code, code))]) {
        const fetchQuote = vi.fn(result);
        let state: LimitQuoteState = EMPTY_LIMIT_QUOTE;
        const request = createLimitQuoteRefresh(fetchQuote, (next) => { state = next; });
        await request.refresh();
        expect(canRetryLimitQuote(state)).toBe(false);
        await request.retry();
        expect(fetchQuote).toHaveBeenCalledOnce();
      }
    },
  );

  it("복구 중 입력 오류로 바뀌면 재조회를 중단한다", async () => {
    const fetchQuote = vi.fn().mockResolvedValueOnce(quote("QUOTE_OUT_OF_PRICE_LIMIT"))
      .mockResolvedValue(quote("PRICE_OUT_OF_RANGE"));
    const request = createLimitQuoteRefresh(fetchQuote, vi.fn());
    await request.refresh();
    await request.retry();
    await request.retry();
    expect(fetchQuote).toHaveBeenCalledTimes(2);
  });

  it("최초 조회와 복구 조회가 진행 중이면 중복 호출하지 않는다", async () => {
    let resolve!: (value: LimitOrderQuoteResponse) => void;
    const fetchQuote = vi.fn(() => new Promise<LimitOrderQuoteResponse>((done) => { resolve = done; }));
    const request = createLimitQuoteRefresh(fetchQuote, vi.fn());
    const first = request.refresh();
    await request.refresh();
    await request.retry();
    expect(fetchQuote).toHaveBeenCalledOnce();
    resolve(quote("QUOTE_OUT_OF_PRICE_LIMIT"));
    await first;

    const recovery = request.retry();
    await request.retry();
    await request.refresh();
    expect(fetchQuote).toHaveBeenCalledTimes(2);
    resolve(quote());
    await recovery;
  });

  it.each([true, false])("입력 변경 후 이전 요청의 늦은 응답을 무시한다 (성공=%s)", async (success) => {
    let resolve!: (value: LimitOrderQuoteResponse) => void;
    let reject!: (error: Error) => void;
    let state: LimitQuoteState = EMPTY_LIMIT_QUOTE;
    const oldFetch = vi.fn(() => new Promise<LimitOrderQuoteResponse>((done, fail) => { resolve = done; reject = fail; }));
    const onChange = (next: LimitQuoteState) => { state = next; };
    const old = createLimitQuoteRefresh(oldFetch, onChange);
    const pending = old.refresh();
    old.dispose();

    const nextQuote = { ...quote(), limitPrice: "11000" };
    const next = createLimitQuoteRefresh(vi.fn().mockResolvedValue(nextQuote), onChange);
    await next.refresh();
    if (success) resolve(quote("PRICE_OUT_OF_RANGE"));
    else reject(new ApiError("PRICE_LIMIT_UNAVAILABLE", "이전 요청 오류"));
    await pending;
    await old.refresh();
    await old.retry();
    expect(oldFetch).toHaveBeenCalledOnce();
    expect(state).toEqual({ quote: nextQuote, error: null, loading: false });
  });

  it("일반 통신 오류는 문구만 표시하고 자동 재조회하지 않는다", async () => {
    let state: LimitQuoteState = EMPTY_LIMIT_QUOTE;
    const fetchQuote = vi.fn().mockRejectedValue(new Error("network"));
    const request = createLimitQuoteRefresh(fetchQuote, (next) => { state = next; });
    await request.refresh();
    expect(state.error).toEqual({ code: null, message: "미리보기를 불러오지 못했어요." });
    expect(state.loading).toBe(false);
    await request.retry();
    expect(fetchQuote).toHaveBeenCalledOnce();
  });
});
