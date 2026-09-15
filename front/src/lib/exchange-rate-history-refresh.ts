/** 한 기간의 조회 생애주기: 중복 요청과 해제 후 늦은 응답 반영을 막습니다. */
export function createHistoryRefresh<T>(
  fetchHistory: (signal: AbortSignal) => Promise<T>,
  onSuccess: (value: T) => void,
  onError: () => void,
  onSettled: () => void,
  timeoutMs = 10_000,
) {
  let disposed = false;
  let pending = false;
  let controller: AbortController | null = null;
  return {
    async refresh() {
      if (disposed || pending) return;
      pending = true;
      const current = new AbortController();
      controller = current;
      const aborted = new Promise<never>((_, reject) => {
        current.signal.addEventListener("abort", () => reject(new Error("환율 조회 취소 또는 시간 초과")), { once: true });
      });
      const timer = setTimeout(() => current.abort(), timeoutMs);
      try {
        const value = await Promise.race([fetchHistory(current.signal), aborted]);
        if (!disposed) onSuccess(value);
      } catch {
        if (!disposed) onError();
      } finally {
        clearTimeout(timer);
        controller = null;
        pending = false;
        if (!disposed) onSettled();
      }
    },
    dispose() { disposed = true; controller?.abort(); },
  };
}
