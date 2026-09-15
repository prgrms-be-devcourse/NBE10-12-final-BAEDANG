import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { getAccountSummary, login, logoutUser, refreshAccessToken, restoreAuth, setAuthEventListeners, syncAuthTokens, updateNickname, signUp } from '../api';

beforeEach(() => {
  const storage = new Map<string, string>();
  vi.stubGlobal('localStorage', { getItem: (key: string) => storage.get(key) ?? null,
    setItem: (key: string, value: string) => storage.set(key, value) });
  vi.stubGlobal('fetch', vi.fn());
  syncAuthTokens({ accessToken: 'old' });
});
afterEach(() => { setAuthEventListeners({}); vi.useRealTimers(); vi.unstubAllGlobals(); });

it('동시 401 및 선제 갱신은 하나의 진행 중 요청을 공유한다', async () => {
  let resolve!: (response: Response) => void;
  vi.mocked(fetch).mockImplementation(async url => {
    if (url === '/api/auth/refresh') return new Promise<Response>(done => { resolve = done; });
    const count = vi.mocked(fetch).mock.calls.filter(([path]) => path !== '/api/auth/refresh').length;
    return count <= 2 ? Response.json({ code: 'TOKEN_EXPIRED' }, { status: 401 }) : Response.json({ accountId: 1 });
  });
  const first = getAccountSummary();
  const second = getAccountSummary();
  await vi.waitFor(() => expect(resolve).toBeTypeOf('function'));
  const proactive = refreshAccessToken();
  resolve(Response.json({ accessToken: 'new' }));
  await expect(first).resolves.toEqual({ accountId: 1 });
  await expect(second).resolves.toEqual({ accountId: 1 });
  await proactive;
  expect(vi.mocked(fetch).mock.calls.filter(([url]) => url === '/api/auth/refresh')).toHaveLength(1);
});

it('늦은 갱신 응답은 로그아웃한 사용자를 복원하지 않는다', async () => {
  let resolve!: (response: Response) => void;
  vi.mocked(fetch).mockImplementation(async url => url === '/api/auth/refresh'
    ? new Promise<Response>(done => { resolve = done; }) : new Response(null, { status: 204 }));
  const updated = vi.fn();
  setAuthEventListeners({ onAccessTokenRefreshed: updated });
  const refresh = refreshAccessToken().catch(error => error);
  await vi.waitFor(() => expect(resolve).toBeTypeOf('function'));
  const loggingOut = logoutUser();
  resolve(Response.json({ accessToken: 'late' }));
  await loggingOut;
  expect((await refresh).code).toBe('SESSION_REVOKED');
  expect(updated).not.toHaveBeenCalled();
  await expect(getAccountSummary()).rejects.toMatchObject({ code: 'UNAUTHENTICATED' });
});

it('로그아웃 실패 후 복원은 refresh보다 로그아웃을 먼저 재시도한다', async () => {
  vi.mocked(fetch).mockRejectedValueOnce(new Error('offline'));
  await expect(logoutUser()).rejects.toMatchObject({ code: 'NETWORK_ERROR' });
  expect(localStorage.getItem('baedang-auth-stamp')).toMatch(/^logout:/);
  vi.mocked(fetch).mockResolvedValue(new Response(null, { status: 204 }));
  await expect(restoreAuth()).resolves.toBeNull();
  expect(vi.mocked(fetch).mock.calls.map(([url]) => url)).toEqual(['/api/auth/logout', '/api/auth/logout']);
});

it('일시적인 갱신 장애는 로그인 상태를 지우지 않는다', async () => {
  const expired = vi.fn();
  setAuthEventListeners({ onAuthExpired: expired });
  vi.mocked(fetch).mockResolvedValueOnce(Response.json({ code: 'AUTH_UNAVAILABLE' }, { status: 503 }));
  await expect(refreshAccessToken()).rejects.toMatchObject({ code: 'AUTH_UNAVAILABLE' });
  expect(expired).not.toHaveBeenCalled();
});

it('이전 요청의 늦은 401은 새 로그인 상태를 지우지 않는다', async () => {
  let resolve!: (response: Response) => void;
  vi.mocked(fetch).mockImplementation(async url => url === '/api/auth/login'
    ? Response.json({ accessToken: 'new', userId: 2 }) : new Promise<Response>(done => { resolve = done; }));
  const old = getAccountSummary().catch(error => error);
  const user = await login({ email: 'new@example.com', password: 'password' });
  syncAuthTokens(user);
  const expired = vi.fn();
  setAuthEventListeners({ onAuthExpired: expired });
  resolve(Response.json({ code: 'SESSION_REVOKED' }, { status: 401 }));
  await old;
  expect(expired).not.toHaveBeenCalled();
});

it('이전 계정의 만료 요청은 새 계정 권한으로 재시도하지 않는다', async () => {
  let resolve!: (response: Response) => void;
  vi.mocked(fetch).mockImplementation(async url => url === '/api/auth/login'
    ? Response.json({ accessToken: 'new', userId: 2 }) : new Promise<Response>(done => { resolve = done; }));
  const old = getAccountSummary().catch(error => error);
  syncAuthTokens(await login({ email: 'new@example.com', password: 'password' }));
  resolve(Response.json({ code: 'TOKEN_EXPIRED' }, { status: 401 }));
  expect((await old).code).toBe('TOKEN_EXPIRED');
  expect(fetch).toHaveBeenCalledTimes(2);
});

it('로그인 도중 로그아웃하면 늦은 로그인 응답은 상태에 반영하지 않는다', async () => {
  let resolve!: (response: Response) => void;
  vi.mocked(fetch).mockImplementation(async url => url === '/api/auth/login'
    ? new Promise<Response>(done => { resolve = done; }) : new Response(null, { status: 204 }));
  const signingIn = login({ email: 'user@example.com', password: 'password' }).catch(error => error);
  await vi.waitFor(() => expect(resolve).toBeTypeOf('function'));
  const loggingOut = logoutUser();
  resolve(Response.json({ accessToken: 'late', userId: 1 }));
  await loggingOut;
  expect((await signingIn).code).toBe('SESSION_REVOKED');
});

it.each(['other-login', 'same-user-login', 'logout', 'other-tab-stamp'])(
  '%s 이후 늦은 닉네임 응답은 프로필을 갱신하지 않는다', async transition => {
    let resolve!: (response: Response) => void;
    vi.mocked(fetch).mockImplementation(async (_url, init) => init?.method === 'PATCH'
      ? new Promise<Response>(done => { resolve = done; }) : new Response(null, { status: 204 }));
    const updated = vi.fn();
    setAuthEventListeners({ onProfileUpdated: updated });
    const saving = updateNickname('changed').catch(error => error);
    if (transition === 'logout') await logoutUser();
    else if (transition === 'other-tab-stamp') localStorage.setItem('baedang-auth-stamp', 'different-session');
    else syncAuthTokens({ accessToken: transition === 'other-login' ? 'other-user' : 'same-user-new-session' });
    resolve(Response.json({ userId: 1, email: 'a@example.com', nickname: 'changed' }));
    expect((await saving).code).toBe('SESSION_REVOKED');
    expect(updated).not.toHaveBeenCalled();
  });

it('닉네임 갱신 중 회전한 Access는 유지하며 프로필 이벤트에는 토큰을 넣지 않는다', async () => {
  let resolve!: (response: Response) => void;
  vi.mocked(fetch).mockImplementation(async (url, init) => {
    if (init?.method === 'PATCH') return new Promise<Response>(done => { resolve = done; });
    if (url === '/api/auth/refresh') return Response.json({ accessToken: 'rotated' });
    return Response.json({ accountId: 1 });
  });
  const updated = vi.fn();
  setAuthEventListeners({ onProfileUpdated: updated });
  const saving = updateNickname('changed');
  await refreshAccessToken();
  resolve(Response.json({ userId: 1, email: 'a@example.com', nickname: 'changed', accessToken: 'stale' }));
  const profile = await saving;
  expect(profile).toEqual({ userId: 1, email: 'a@example.com', nickname: 'changed' });
  expect(updated).toHaveBeenCalledWith(profile);
  await getAccountSummary();
  expect(fetch).toHaveBeenLastCalledWith(expect.stringContaining('/api/accounts/me'),
    expect.objectContaining({ headers: expect.objectContaining({ Authorization: 'Bearer rotated' }) }));
});

function mockAuthLock() {
  let queue = Promise.resolve();
  vi.stubGlobal('navigator', { locks: { request: (_name: string, work: () => Promise<unknown>) => {
    const next = queue.then(work);
    queue = next.then(() => {}, () => {});
    return next;
  } } });
}
function stallUntilAbort(signal: AbortSignal) {
  return new Promise<never>((_resolve, reject) => {
    signal.addEventListener('abort', () => reject(new DOMException('Aborted', 'AbortError')), { once: true });
  });
}

it.each(['headers', 'body'])('인증 %s 수신이 멈추면 15초 후 실패하고 다음 갱신을 허용한다', async phase => {
  vi.useFakeTimers();
  mockAuthLock();
  const expired = vi.fn();
  setAuthEventListeners({ onAuthExpired: expired });
  vi.mocked(fetch).mockImplementationOnce(async (_url, init) => {
    const stalled = () => stallUntilAbort(init!.signal!);
    return phase === 'headers' ? stalled() : { ok: true, status: 200, json: stalled } as unknown as Response;
  });
  const refresh = refreshAccessToken().catch(error => error);
  await vi.advanceTimersByTimeAsync(0);
  await vi.advanceTimersByTimeAsync(14_999);
  expect(expired).not.toHaveBeenCalled();
  await vi.advanceTimersByTimeAsync(1);
  expect((await refresh).code).toBe('REQUEST_TIMEOUT');
  expect(expired).not.toHaveBeenCalled();
  expect(fetch).toHaveBeenCalledTimes(1);
  vi.mocked(fetch).mockResolvedValueOnce(Response.json({ accountId: 1 }));
  await getAccountSummary();
  expect(fetch).toHaveBeenLastCalledWith(expect.any(String),
    expect.objectContaining({ headers: expect.objectContaining({ Authorization: 'Bearer old' }) }));
  vi.mocked(fetch).mockResolvedValueOnce(Response.json({ accessToken: 'recovered' }));
  await expect(refreshAccessToken()).resolves.toEqual({ accessToken: 'recovered' });
  expect(vi.getTimerCount()).toBe(0);
});

it('멈춘 Refresh의 타임아웃이 Web Lock을 해제해 대기 중 로그아웃을 진행한다', async () => {
  vi.useFakeTimers();
  mockAuthLock();
  vi.mocked(fetch).mockImplementation(async (url, init) => {
    if (url !== '/api/auth/refresh') return new Response(null, { status: 204 });
    return stallUntilAbort(init!.signal!);
  });
  const refresh = refreshAccessToken().catch(error => error);
  await vi.advanceTimersByTimeAsync(0);
  const logout = logoutUser();
  await vi.advanceTimersByTimeAsync(14_999);
  expect(fetch).toHaveBeenCalledTimes(1);
  await vi.advanceTimersByTimeAsync(1);
  expect((await refresh).code).toBe('REQUEST_TIMEOUT');
  await logout;
  expect(vi.mocked(fetch).mock.calls.map(([url]) => url)).toEqual(['/api/auth/refresh', '/api/auth/logout']);
  expect(vi.getTimerCount()).toBe(0);
});

it.each(['login', 'signup', 'logout'])('%s 요청도 15초 제한을 적용한다', async action => {
  vi.useFakeTimers();
  vi.stubGlobal('navigator', {});
  vi.mocked(fetch).mockImplementation(async (_url, init) => stallUntilAbort(init!.signal!));
  const credentials = { email: 'a@example.com', password: 'password', nickname: 'A' };
  const pending = (action === 'logout' ? logoutUser() : action === 'signup' ? signUp(credentials) : login(credentials))
    .catch(error => error);
  await vi.advanceTimersByTimeAsync(15_000);
  expect((await pending).code).toBe('REQUEST_TIMEOUT');
  expect(fetch).toHaveBeenCalledTimes(1);
  if (action === 'logout') expect(localStorage.getItem('baedang-auth-stamp')).toMatch(/^logout:/);
  expect(vi.getTimerCount()).toBe(0);
});
