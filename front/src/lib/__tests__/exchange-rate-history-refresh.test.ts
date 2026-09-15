import { afterEach, describe, expect, it, vi } from "vitest";
import { createHistoryRefresh } from "../exchange-rate-history-refresh";

describe("환율 이력 재조회", () => {
  afterEach(() => vi.useRealTimers());

  it("시간 초과로 요청을 취소하고 다음 주기에 복구하며 늦은 응답은 무시한다", async () => {
    vi.useFakeTimers();
    let resolve!: (value: number) => void;
    const fetch = vi.fn<(signal: AbortSignal) => Promise<number>>()
      .mockImplementationOnce(() => new Promise((done) => { resolve = done; }))
      .mockResolvedValueOnce(2);
    const success = vi.fn();
    const error = vi.fn();
    const request = createHistoryRefresh(fetch, success, error, vi.fn());
    const pending = request.refresh();
    await vi.advanceTimersByTimeAsync(10_000);
    await pending;
    expect(fetch.mock.calls[0][0].aborted).toBe(true);
    expect(error).toHaveBeenCalledOnce();
    await request.refresh();
    resolve(1);
    await Promise.resolve();
    expect(success).toHaveBeenCalledExactlyOnceWith(2);
  });

  it("종료 시 응답을 기다리지 않고 취소한다", async () => {
    const fetch = vi.fn<(signal: AbortSignal) => Promise<number>>(() => new Promise(() => {}));
    const settled = vi.fn();
    const request = createHistoryRefresh(fetch, vi.fn(), vi.fn(), settled);
    const pending = request.refresh();
    request.dispose();
    await pending;
    expect(fetch.mock.calls[0][0].aborted).toBe(true);
    expect(settled).not.toHaveBeenCalled();
  });
  it("진행 중 요청을 중복 실행하지 않고 완료 후 다시 조회한다", async () => {
    let resolve!: (value: number) => void;
    const fetch = vi.fn(() => new Promise<number>((done) => { resolve = done; }));
    const success = vi.fn();
    const settled = vi.fn();
    const request = createHistoryRefresh(fetch, success, vi.fn(), settled);
    const first = request.refresh();
    await request.refresh();
    expect(fetch).toHaveBeenCalledTimes(1);
    resolve(1);
    await first;
    expect(success).toHaveBeenCalledWith(1);
    const second = request.refresh();
    resolve(2);
    await second;
    expect(success).toHaveBeenLastCalledWith(2);
    expect(settled).toHaveBeenCalledTimes(2);
  });

  it("실패 시 데이터를 덮어쓰지 않고 다음 조회에서 회복한다", async () => {
    const fetch = vi.fn().mockRejectedValueOnce(new Error()).mockResolvedValueOnce([1400]);
    const success = vi.fn();
    const error = vi.fn();
    const request = createHistoryRefresh(fetch, success, error, vi.fn());
    await request.refresh();
    expect(success).not.toHaveBeenCalled();
    expect(error).toHaveBeenCalledOnce();
    await request.refresh();
    expect(success).toHaveBeenCalledWith([1400]);
  });

  it.each([true, false])("기간 변경/종료 후 늦은 응답을 무시한다 (성공=%s)", async (ok) => {
    let resolve!: () => void;
    let reject!: () => void;
    const fetch = vi.fn(() => new Promise<void>((done, fail) => { resolve = done; reject = fail; }));
    const success = vi.fn();
    const error = vi.fn();
    const settled = vi.fn();
    const request = createHistoryRefresh(fetch, success, error, settled);
    const pending = request.refresh();
    request.dispose();
    if (ok) resolve(); else reject();
    await pending;
    await request.refresh();
    expect(fetch).toHaveBeenCalledOnce();
    expect(success).not.toHaveBeenCalled();
    expect(error).not.toHaveBeenCalled();
    expect(settled).not.toHaveBeenCalled();
  });
});
