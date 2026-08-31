/**
 * fetchExchangeRate의 성공/실패(기본값 대체) 분기 테스트.
 * getExchangeRateLatest가 내부적으로 쓰는 global.fetch를 모킹한다 (api.test.ts와 동일 패턴).
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { fetchExchangeRate, DEFAULT_USD_KRW_RATE } from '../exchange-rate';

function mockFetch(status: number, body: unknown) {
  return vi.spyOn(globalThis, 'fetch').mockResolvedValueOnce({
    ok: status >= 200 && status < 300,
    status,
    json: () => Promise.resolve(body),
  } as Response);
}

beforeEach(() => {
  vi.restoreAllMocks();
});

describe('fetchExchangeRate — 성공', () => {
  it('백엔드 응답을 숫자로 변환해 반환한다', async () => {
    mockFetch(200, {
      baseCurrency: 'USD',
      quoteCurrency: 'KRW',
      rate: '1400.000000',
      changeAmount: '2.000000',
      changeRate: '0.001431',
      rateAt: '2026-08-26T15:00:00+09:00',
    });

    const info = await fetchExchangeRate();

    expect(info.rate).toBe(1400);
    expect(info.changeAmount).toBe(2);
    expect(info.changeRate).toBeCloseTo(0.001431);
    expect(info.updatedAt).toEqual(new Date('2026-08-26T15:00:00+09:00'));
  });
});

describe('fetchExchangeRate — 예상 가능한 실패(EXCHANGE_RATE_NOT_FOUND)', () => {
  it('조용히 기본값으로 대체하고 콘솔에 경고를 남기지 않는다', async () => {
    mockFetch(404, { code: 'EXCHANGE_RATE_NOT_FOUND', message: '환율 정보를 가져올 수 없어요' });
    const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {});

    const info = await fetchExchangeRate();

    expect(info.rate).toBe(DEFAULT_USD_KRW_RATE);
    expect(warnSpy).not.toHaveBeenCalled();
  });
});

describe('fetchExchangeRate — 예상 못한 실패', () => {
  it('네트워크 에러 시 기본값으로 대체하고 콘솔에 경고를 남긴다', async () => {
    vi.spyOn(globalThis, 'fetch').mockRejectedValueOnce(new Error('network down'));
    const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {});

    const info = await fetchExchangeRate();

    expect(info.rate).toBe(DEFAULT_USD_KRW_RATE);
    expect(warnSpy).toHaveBeenCalledTimes(1);
  });

  it('응답 필드가 숫자로 파싱되지 않으면 기본값으로 대체하고 콘솔에 경고를 남긴다', async () => {
    mockFetch(200, {
      baseCurrency: 'USD',
      quoteCurrency: 'KRW',
      rate: 'not-a-number',
      changeAmount: '2.000000',
      changeRate: '0.001431',
      rateAt: '2026-08-26T15:00:00+09:00',
    });
    const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {});

    const info = await fetchExchangeRate();

    expect(info.rate).toBe(DEFAULT_USD_KRW_RATE);
    expect(warnSpy).toHaveBeenCalledTimes(1);
  });

  it('빈 문자열은 Number("")가 0을 반환해도 유효한 값으로 취급하지 않는다', async () => {
    // Number("")는 0이라 Number.isNaN만으로는 못 걸러낸다 — 이 케이스를 잡는 게 이 테스트의 목적이다.
    mockFetch(200, {
      baseCurrency: 'USD',
      quoteCurrency: 'KRW',
      rate: '',
      changeAmount: '2.000000',
      changeRate: '0.001431',
      rateAt: '2026-08-26T15:00:00+09:00',
    });
    const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {});

    const info = await fetchExchangeRate();

    expect(info.rate).toBe(DEFAULT_USD_KRW_RATE);
    expect(warnSpy).toHaveBeenCalledTimes(1);
  });
});
