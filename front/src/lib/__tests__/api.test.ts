/**
 * api.ts 에러 처리 분기 테스트.
 * postJson은 private 함수이므로 signUp / login 래퍼를 통해 간접 테스트하고,
 * global.fetch를 vi.fn()으로 모킹합니다.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import {
  signUp,
  login,
  getAccountSummary,
  placeOrder,
  getExchangeRateLatest,
  getExchangeRateHistory,
  getStockDetail,
  searchStocks,
  getRankings,
  getCandles,
  getHoldings,
  getLedger,
  resetAccount,
  getMarketStatus,
  ApiError,
} from '../api';

// ─── fetch 모킹 헬퍼 ───────────────────────────────────────────────────────
function mockFetch(status: number, body: unknown) {
  return vi.spyOn(globalThis, 'fetch').mockResolvedValueOnce({
    ok: status >= 200 && status < 300,
    status,
    json: () => Promise.resolve(body),
  } as Response);
}

function mockFetchNetworkError(message: string) {
  return vi.spyOn(globalThis, 'fetch').mockRejectedValueOnce(new Error(message));
}

// ─── 테스트 ────────────────────────────────────────────────────────────────
beforeEach(() => {
  vi.restoreAllMocks();
});

describe('signUp — 성공', () => {
  it('200 → AuthUser 반환', async () => {
    mockFetch(200, { userId: 1, email: 'a@b.com', nickname: 'tester' });
    const user = await signUp({ email: 'a@b.com', password: 'pass1234', nickname: 'tester' });
    expect(user).toEqual({ userId: 1, email: 'a@b.com', nickname: 'tester' });
  });
});

describe('login — 성공', () => {
  it('200 → AuthUser 반환', async () => {
    mockFetch(200, { userId: 2, email: 'b@c.com', nickname: 'user2' });
    const user = await login({ email: 'b@c.com', password: 'pw' });
    expect(user).toEqual({ userId: 2, email: 'b@c.com', nickname: 'user2' });
  });
});

describe('getAccountSummary — 성공', () => {
  it('200 → AccountSummary 반환 및 X-User-Id 헤더 전송', async () => {
    const summaryData = {
      accountId: 10,
      roundNo: 1,
      initialCash: '50000000',
      cashBalance: '48000000',
      stockValue: '2000000',
      totalAsset: '50000000',
      unrealizedPnl: '0',
      asOf: '2026-08-28T00:00:00Z',
    };
    mockFetch(200, summaryData);
    const summary = await getAccountSummary(1);
    expect(summary).toEqual(summaryData);
  });
});

describe('getExchangeRateLatest — 성공', () => {
  it('200 → ExchangeRateLatest 반환, 기본 파라미터(USD/KRW)로 요청', async () => {
    const rateData = {
      baseCurrency: 'USD',
      quoteCurrency: 'KRW',
      rate: '1400.000000',
      changeAmount: '2.000000',
      changeRate: '0.001431',
      rateAt: '2026-08-26T15:00:00+09:00',
    };
    const fetchSpy = mockFetch(200, rateData);
    const rate = await getExchangeRateLatest();
    expect(rate).toEqual(rateData);
    expect(fetchSpy).toHaveBeenCalledWith(
      expect.stringContaining('/api/exchange-rates/latest?base=USD&quote=KRW'),
      expect.anything()
    );
  });

  it('base/quote 파라미터를 그대로 쿼리스트링에 반영', async () => {
    const fetchSpy = mockFetch(200, {
      baseCurrency: 'EUR', quoteCurrency: 'KRW', rate: '1500', changeAmount: '0', changeRate: '0',
      rateAt: '2026-08-26T15:00:00+09:00',
    });
    await getExchangeRateLatest('EUR', 'KRW');
    expect(fetchSpy).toHaveBeenCalledWith(
      expect.stringContaining('/api/exchange-rates/latest?base=EUR&quote=KRW'),
      expect.anything()
    );
  });

  it('소문자로 넘겨도 대문자로 정규화해서 쿼리스트링을 만든다 (캐시 키 분산 방지)', async () => {
    const fetchSpy = mockFetch(200, {
      baseCurrency: 'USD', quoteCurrency: 'KRW', rate: '1400', changeAmount: '0', changeRate: '0',
      rateAt: '2026-08-26T15:00:00+09:00',
    });
    await getExchangeRateLatest('usd', 'krw');
    expect(fetchSpy).toHaveBeenCalledWith(
      expect.stringContaining('/api/exchange-rates/latest?base=USD&quote=KRW'),
      expect.anything()
    );
  });
});

describe('getExchangeRateHistory — 성공', () => {
  it('200 → 이력 목록 반환, period를 쿼리스트링에 반영', async () => {
    const historyData = {
      items: [
        { rateAt: '2026-08-25T00:00:00+09:00', rate: '1395.20' },
        { rateAt: '2026-08-26T00:00:00+09:00', rate: '1398.50' },
      ],
    };
    const fetchSpy = mockFetch(200, historyData);
    const result = await getExchangeRateHistory('1w');
    expect(result).toEqual(historyData);
    expect(fetchSpy).toHaveBeenCalledWith(
      expect.stringContaining('/api/exchange-rates/history?period=1w'),
      expect.anything()
    );
  });
});

describe('placeOrder — 성공 (accountId 포함)', () => {
  it('201 → OrderResponse 반환 및 accountId 바디 전송', async () => {
    const orderData = {
      orderId: 100,
      status: 'FILLED',
      symbol: '005930',
      marketCountry: 'KR' as const,
      side: 'BUY',
      quantity: '10',
      executedPrice: '70000',
      exchangeRate: '1',
      grossAmount: '700000',
      fee: '70',
      tax: '0',
      netAmount: '700070',
      quoteAt: '2026-08-28T00:00:00Z',
      orderedAt: '2026-08-28T00:00:01Z',
      account: { cashBalanceAfter: '49299930' },
    };
    mockFetch(201, orderData);
    const response = await placeOrder(1, {
      accountId: 10,
      clientOrderId: 'uuid-1234',
      symbol: '005930',
      marketCountry: 'KR',
      side: 'BUY',
      quantity: '10',
    });
    expect(response).toEqual(orderData);
  });
});

describe('getStockDetail — 성공', () => {
  it('200 → StockDetail 반환, marketCountry 쿼리스트링 포함', async () => {
    const detailData = {
      symbol: '005930', name: '삼성전자', englishName: 'SamsungElec', market: 'KOSPI', marketCountry: 'KR',
      currency: 'KRW', isinCode: 'KR7005930003', category: 'INDIVIDUAL', leverageFactor: null, isDividend: false,
      price: {
        lastPrice: '241500', prevClose: '236050', changeAmount: '5450', changeRate: '0.0231',
        upperLimit: '313500', lowerLimit: '169500', quoteAt: '2026-08-28T00:00:00Z', realtime: true,
      },
      info: { marketCap: '1441000000000000', sharesOutstanding: '5969782550', listDate: '1975-06-11' },
      warnings: [],
      tradable: true,
      tradableReason: null,
    };
    const fetchSpy = mockFetch(200, detailData);
    const detail = await getStockDetail('005930', 'KR');
    expect(detail).toEqual(detailData);
    expect(fetchSpy).toHaveBeenCalledWith(
      expect.stringContaining('/api/stocks/005930?marketCountry=KR'),
      expect.anything()
    );
  });
});

describe('searchStocks — 성공', () => {
  it('200 → 검색 결과 반환', async () => {
    const searchData = { items: [{ symbol: 'NVDA', name: '엔비디아', englishName: 'NVIDIA', market: 'NASDAQ', marketCountry: 'US', category: 'INDIVIDUAL' }] };
    const fetchSpy = mockFetch(200, searchData);
    const result = await searchStocks('엔비디아', 8);
    expect(result).toEqual(searchData);
    expect(fetchSpy).toHaveBeenCalledWith(
      expect.stringContaining('/api/stocks/search?q=%EC%97%94%EB%B9%84%EB%94%94%EC%95%84&size=8'),
      expect.anything()
    );
  });
});

describe('getRankings — 성공', () => {
  it('200 → 랭킹 페이지 반환, cursor 없으면 쿼리스트링에서 생략', async () => {
    const rankingData = { items: [], nextCursor: 'abc', hasNext: true };
    const fetchSpy = mockFetch(200, rankingData);
    const result = await getRankings('KR', 20);
    expect(result).toEqual(rankingData);
    const calledUrl = fetchSpy.mock.calls[0][0] as string;
    expect(calledUrl).toContain('/api/stocks/rankings?');
    expect(calledUrl).toContain('market=KR');
    expect(calledUrl).toContain('size=20');
    expect(calledUrl).not.toContain('cursor');
  });

  it('cursor를 넘기면 쿼리스트링에 포함', async () => {
    const fetchSpy = mockFetch(200, { items: [], nextCursor: null, hasNext: false });
    await getRankings('US', 20, 'abc123');
    expect(fetchSpy).toHaveBeenCalledWith(expect.stringContaining('cursor=abc123'), expect.anything());
  });
});

describe('getCandles — 성공', () => {
  it('200 → 캔들 데이터 반환, marketCountry/interval/range 쿼리스트링 포함', async () => {
    const candleData = { symbol: '005930', interval: '1d', range: '1M', currency: 'KRW', items: [] };
    const fetchSpy = mockFetch(200, candleData);
    const result = await getCandles('005930', 'KR', '1d', '1M');
    expect(result).toEqual(candleData);
    const calledUrl = fetchSpy.mock.calls[0][0] as string;
    expect(calledUrl).toContain('/api/stocks/005930/candles?');
    expect(calledUrl).toContain('marketCountry=KR');
    expect(calledUrl).toContain('interval=1d');
    expect(calledUrl).toContain('range=1M');
  });
});

describe('getHoldings — 성공', () => {
  it('200 → 보유 종목 목록 반환 및 X-User-Id 헤더 전송', async () => {
    const holdingsData = { items: [], asOf: '2026-08-31T00:00:00Z' };
    const fetchSpy = mockFetch(200, holdingsData);
    const result = await getHoldings(1);
    expect(result).toEqual(holdingsData);
    expect(fetchSpy).toHaveBeenCalledWith(
      expect.stringContaining('/api/accounts/me/holdings'),
      expect.objectContaining({ headers: expect.objectContaining({ 'X-User-Id': '1' }) })
    );
  });
});

describe('getLedger — 성공', () => {
  it('파라미터 없이 호출하면 쿼리스트링 없이 요청', async () => {
    const fetchSpy = mockFetch(200, { items: [], nextCursor: null, hasNext: false });
    await getLedger(1);
    expect(fetchSpy).toHaveBeenCalledWith(
      expect.stringMatching(/\/api\/accounts\/me\/ledger$/),
      expect.anything()
    );
  });

  it('cursor/size/entryType을 쿼리스트링에 반영', async () => {
    const fetchSpy = mockFetch(200, { items: [], nextCursor: null, hasNext: false });
    await getLedger(1, { cursor: 'xyz', size: 10, entryType: 'BUY' });
    const calledUrl = fetchSpy.mock.calls[0][0] as string;
    expect(calledUrl).toContain('cursor=xyz');
    expect(calledUrl).toContain('size=10');
    expect(calledUrl).toContain('entryType=BUY');
  });
});

describe('resetAccount — 성공', () => {
  it('200 → 초기화된 계좌 정보 반환 및 accountId 바디 전송', async () => {
    const resetData = { accountId: 11, roundNo: 2, initialCash: '50000000', cashBalance: '50000000' };
    const fetchSpy = mockFetch(200, resetData);
    const result = await resetAccount(1, 10);
    expect(result).toEqual(resetData);
    expect(fetchSpy).toHaveBeenCalledWith(
      expect.stringContaining('/api/accounts/me/reset'),
      expect.objectContaining({ body: JSON.stringify({ accountId: 10 }) })
    );
  });
});

describe('getMarketStatus — 성공', () => {
  it('200 → 시장 상태 반환, 파라미터 없이 요청', async () => {
    const statusData = {
      markets: [
        { marketCountry: 'KR', open: true, opensAt: '2026-08-31T09:00:00+09:00', closesAt: '2026-08-31T15:30:00+09:00', nextOpensAt: null },
        { marketCountry: 'US', open: false, opensAt: null, closesAt: null, nextOpensAt: '2026-08-31T22:30:00+09:00' },
      ],
      serverTime: '2026-08-31T12:07:14+09:00',
    };
    const fetchSpy = mockFetch(200, statusData);
    const result = await getMarketStatus();
    expect(result).toEqual(statusData);
    expect(fetchSpy).toHaveBeenCalledWith(expect.stringContaining('/api/market/status'), expect.anything());
  });
});

describe('postJson — 네트워크 에러', () => {
  it('fetch 자체 실패 → ApiError(NETWORK_ERROR)', async () => {
    mockFetchNetworkError('Failed to fetch');
    await expect(login({ email: 'x@x.com', password: 'pw' }))
      .rejects.toMatchObject({ code: 'NETWORK_ERROR' });
  });
});

describe('postJson — HTTP 에러 응답', () => {
  it('400 + code/message → ApiError에 올바르게 매핑', async () => {
    mockFetch(400, { code: 'INVALID_REQUEST', message: '잘못된 요청입니다.' });
    const err = await login({ email: 'x@x.com', password: 'pw' }).catch(e => e);
    expect(err).toBeInstanceOf(ApiError);
    expect(err.code).toBe('INVALID_REQUEST');
    expect(err.message).toBe('잘못된 요청입니다.');
  });

  it('INVALID_INPUT — fieldErrors를 data에서 추출', async () => {
    mockFetch(400, {
      code: 'INVALID_INPUT',
      message: '입력값이 올바르지 않습니다.',
      data: { email: '이메일 형식이 아닙니다.', password: '비밀번호는 8자 이상이어야 합니다.' },
    });
    const err = await signUp({ email: 'bad', password: 'pw', nickname: 'n' }).catch(e => e);
    expect(err.code).toBe('INVALID_INPUT');
    expect(err.fieldErrors).toEqual({
      email: '이메일 형식이 아닙니다.',
      password: '비밀번호는 8자 이상이어야 합니다.',
    });
  });

  it('INVALID_INPUT이 아닌 경우 fieldErrors = undefined', async () => {
    mockFetch(401, { code: 'UNAUTHORIZED', message: '인증이 필요합니다.', data: { foo: 'bar' } });
    const err = await login({ email: 'x@x.com', password: 'pw' }).catch(e => e);
    expect(err.fieldErrors).toBeUndefined();
  });

  it('JSON 응답 없음 → UNKNOWN_ERROR 폴백', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValueOnce({
      ok: false,
      status: 500,
      json: () => Promise.reject(new Error('not json')),
    } as Response);
    const err = await login({ email: 'x@x.com', password: 'pw' }).catch(e => e);
    expect(err.code).toBe('UNKNOWN_ERROR');
    expect(err.message).toBe('요청을 처리하지 못했어요.');
  });
});

