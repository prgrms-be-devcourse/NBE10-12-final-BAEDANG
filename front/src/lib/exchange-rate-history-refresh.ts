/** 한 기간의 조회 생애주기: 중복 요청과 해제 후 늦은 응답 반영을 막습니다. */
export function createHistoryRefresh<T>(
  fetchHistory: () => Promise<T>,
  onSuccess: (value: T) => void,
  onError: () => void,
  onSettled: () => void,
) {
  let disposed = false;
  let pending = false;
  return {
    async refresh() {
      if (disposed || pending) return;
      pending = true;
      try {
        const value = await fetchHistory();
        if (!disposed) onSuccess(value);
      } catch {
        if (!disposed) onError();
      } finally {
        pending = false;
        if (!disposed) onSettled();
      }
    },
    dispose() { disposed = true; },
  };
}
