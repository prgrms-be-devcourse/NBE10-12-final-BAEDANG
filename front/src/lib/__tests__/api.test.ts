/**
 * api.ts 에러 처리 분기 테스트.
 * postJson은 private 함수이므로 signUp / login 래퍼를 통해 간접 테스트하고,
 * global.fetch를 vi.fn()으로 모킹합니다.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { signUp, login, ApiError } from '../api';

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
