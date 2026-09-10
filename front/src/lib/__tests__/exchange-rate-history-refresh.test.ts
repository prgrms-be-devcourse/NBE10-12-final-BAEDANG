import { describe, expect, it, vi } from "vitest";
import { createHistoryRefresh } from "../exchange-rate-history-refresh";

describe("환율 이력 재조회", () => {
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
