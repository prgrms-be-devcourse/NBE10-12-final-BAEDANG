import { ApiError, type LimitOrderQuoteResponse } from "./api";

export type LimitQuoteState = {
  quote: LimitOrderQuoteResponse | null;
  error: { code: string | null; message: string } | null;
  loading: boolean;
};

export const EMPTY_LIMIT_QUOTE: LimitQuoteState = { quote: null, error: null, loading: false };

/** 입력을 고칠 필요 없이 시세 데이터 복구로 해소될 수 있는 오류만 재조회합니다. */
export function canRetryLimitQuote(state: LimitQuoteState): boolean {
  const code = state.error?.code ?? (state.quote?.acceptable === false ? state.quote.reason : null);
  return code === "PRICE_LIMIT_UNAVAILABLE" || code === "QUOTE_OUT_OF_PRICE_LIMIT";
}

/** 하나의 주문 입력에 대한 조회 생애주기. 입력이 바뀌면 dispose하여 늦은 응답을 무시합니다. */
export function createLimitQuoteRefresh(
  fetchQuote: () => Promise<LimitOrderQuoteResponse>,
  onChange: (state: LimitQuoteState) => void,
) {
  let state = EMPTY_LIMIT_QUOTE;
  let disposed = false;
  let pending = false;

  function publish(next: LimitQuoteState) {
    state = next;
    if (!disposed) onChange(next);
  }

  async function refresh() {
    if (disposed || pending) return;
    pending = true;
    publish({ ...state, loading: true });
    try {
      const quote = await fetchQuote();
      publish({ quote, error: null, loading: false });
    } catch (error) {
      publish({ quote: null, loading: false, error: {
        code: error instanceof ApiError ? error.code : null,
        message: error instanceof ApiError ? error.message : "미리보기를 불러오지 못했어요.",
      } });
    } finally {
      pending = false;
    }
  }

  return {
    refresh,
    // React가 폴링을 해제하기 전 호출되더라도 정상화된 요청은 재실행하지 않습니다.
    retry: () => canRetryLimitQuote(state) ? refresh() : Promise.resolve(),
    dispose: () => { disposed = true; },
  };
}
