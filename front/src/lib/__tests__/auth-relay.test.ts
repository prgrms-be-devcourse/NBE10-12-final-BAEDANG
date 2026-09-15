import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { NextRequest } from 'next/server';
import { POST } from '../../app/api/auth/[action]/route';
import { POST as forgot } from '../../app/api/auth/password/forgot/route';
import { POST as reset } from '../../app/api/auth/password/reset/route';

function send(action = 'refresh', headers: Record<string, string> = {}, body = {}) {
  return POST(new NextRequest(`https://app.example.com/api/auth/${action}`, {
    method: 'POST', headers: { Origin: 'https://app.example.com', 'X-Auth-Request': '1',
      'Content-Type': 'application/json', Cookie: 'baedang_refresh=server-cookie', ...headers },
    body: JSON.stringify(body),
  }), { params: Promise.resolve({ action }) });
}
beforeEach(() => {
  vi.stubEnv('AUTH_BACKEND_URL', 'https://api.example.com');
  vi.stubEnv('AUTH_PUBLIC_ORIGIN', 'https://app.example.com');
  vi.stubGlobal('fetch', vi.fn());
});
afterEach(() => { vi.unstubAllGlobals(); vi.unstubAllEnvs(); });

describe('인증 중계의 쿠키 및 CSRF 경계', () => {
  const passwordRoutes = [{ action: 'forgot', handler: forgot }, { action: 'reset', handler: reset }];
  function passwordRequest(action: string, origin = 'https://app.example.com') {
    return new NextRequest(`https://app.example.com/api/auth/password/${action}`, {
      method: 'POST', headers: { Origin: origin, 'X-Auth-Request': '1', 'Content-Type': 'application/json',
        Cookie: 'baedang_refresh=existing-session' },
      body: JSON.stringify(action === 'forgot' ? { email: 'user@example.com' } : { token: 'reset-token', newPassword: 'NewPassword123!' }),
    });
  }
  it.each(passwordRoutes)('$action 경로는 빈 성공 응답을 전달하고 쿠키를 변경하지 않는다', async ({ action, handler }) => {
    vi.mocked(fetch).mockResolvedValue(new Response(null, { status: 200 }));
    const request = passwordRequest(action);
    const body = await request.clone().text();
    const response = await handler(request);
    expect(response.status).toBe(200);
    expect(await response.text()).toBe('');
    expect(response.headers.get('set-cookie')).toBeNull();
    expect(response.headers.get('cache-control')).toBe('no-store');
    expect(fetch).toHaveBeenCalledWith(new URL(`https://api.example.com/api/auth/password/${action}`),
      expect.objectContaining({ body, redirect: 'error', cache: 'no-store' }));
  });
  it.each(passwordRoutes)('$action 경로에도 Origin 검증을 적용한다', async ({ action, handler }) => {
    expect((await handler(passwordRequest(action, 'https://attacker.example')))).toHaveProperty('status', 403);
    expect(fetch).not.toHaveBeenCalled();
  });
  it('잘못된 재설정 토큰의 오류와 장애 응답을 보존한다', async () => {
    vi.mocked(fetch).mockResolvedValue(Response.json({ code: 'PASSWORD_RESET_TOKEN_INVALID', message: '잘못된 링크' }, { status: 400 }));
    const response = await reset(passwordRequest('reset'));
    expect(response.status).toBe(400);
    expect((await response.json()).code).toBe('PASSWORD_RESET_TOKEN_INVALID');
    expect(response.headers.get('set-cookie')).toBeNull();
    vi.mocked(fetch).mockRejectedValue(new Error('network'));
    expect((await reset(passwordRequest('reset'))).status).toBe(503);
  });
  it('Refresh는 쿠키에서만 읽고 JSON에서 제거하며 보안 쿠키를 설정한다', async () => {
    vi.mocked(fetch).mockResolvedValue(Response.json({ accessToken: 'access', refreshToken: 'successor', expiresAt: '2030-01-01T00:00:00Z' }));
    const response = await send('refresh', {}, { refreshToken: 'untrusted-body' });
    expect(await response.json()).toEqual({ accessToken: 'access', expiresAt: '2030-01-01T00:00:00Z' });
    expect(fetch).toHaveBeenCalledWith(new URL('https://api.example.com/api/auth/refresh'),
      expect.objectContaining({ body: JSON.stringify({ refreshToken: 'server-cookie' }), cache: 'no-store', redirect: 'error' }));
    const cookie = response.cookies.get('baedang_refresh');
    expect(cookie).toMatchObject({ value: 'successor', httpOnly: true, secure: true, sameSite: 'lax', path: '/api/auth' });
    expect(response.headers.get('cache-control')).toBe('no-store');
  });
  it.each(['login', 'signup', 'refresh', 'logout'])('%s도 다른 Origin이나 전용 헤더 누락은 거절한다', async action => {
    expect((await send(action, { Origin: 'https://attacker.example' })).status).toBe(403);
    expect((await send(action, { 'X-Auth-Request': '' })).status).toBe(403);
    expect(fetch).not.toHaveBeenCalled();
  });
  it('외부 HTTP 백엔드와 임의 경로는 허용하지 않는다', async () => {
    vi.stubEnv('AUTH_BACKEND_URL', 'http://203.0.113.10');
    expect((await send()).status).toBe(503);
    expect((await send('password')).status).toBe(404);
    expect(fetch).not.toHaveBeenCalled();
  });
  it('일시 장애나 늦은 401은 Refresh 쿠키를 삭제하지 않는다', async () => {
    vi.mocked(fetch).mockRejectedValueOnce(new Error('connection failure'));
    const failed = await send();
    expect(failed.status).toBe(503);
    expect(failed.headers.get('set-cookie')).toBeNull();
    vi.mocked(fetch).mockResolvedValue(Response.json({ code: 'SESSION_REVOKED' }, { status: 401 }));
    expect((await send()).headers.get('set-cookie')).toBeNull();
  });
  it('로그아웃 성공 또는 이미 만료된 세션은 동일 속성으로 쿠키를 삭제한다', async () => {
    vi.mocked(fetch).mockResolvedValue(Response.json({ code: 'TOKEN_EXPIRED' }, { status: 401 }));
    const response = await send('logout');
    expect(response.status).toBe(204);
    expect(response.cookies.get('baedang_refresh')).toMatchObject({ value: '', maxAge: 0, path: '/api/auth', httpOnly: true, secure: true });
  });
});
