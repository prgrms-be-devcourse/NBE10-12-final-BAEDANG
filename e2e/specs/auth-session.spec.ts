import { test, expect, API, PASSWORD } from '../fixtures/test.js';
import { authenticate } from '../helpers/trading.js';
import { openNavigation } from '../helpers/navigation.js';

const authHeaders = { Origin: 'http://127.0.0.1:13000', 'X-Auth-Request': '1' };

test('인증 쿠키는 HttpOnly이고 위조 Origin 갱신을 거절한다', async ({ page, user }) => {
  await authenticate(page, user);
  const refresh = await page.request.post('/api/auth/refresh', { headers: authHeaders, data: {} });
  expect(refresh.ok()).toBeTruthy();
  expect((await refresh.json()).refreshToken).toBeUndefined();
  expect((await page.context().cookies()).find(cookie => cookie.name === 'baedang_refresh')?.httpOnly).toBe(true);
  expect(await page.evaluate(() => document.cookie)).not.toContain('baedang_refresh');
  expect(await page.evaluate(() => localStorage.getItem('trading-auth-user'))).toBeNull();
  const forged = await page.request.post('/api/auth/refresh', {
    headers: { ...authHeaders, Origin: 'https://untrusted.example.com' }, data: {},
  });
  expect(forged.status()).toBe(403);
});

test('다중 탭 동시 복원과 만료 후 갱신이 세션을 유지한다', async ({ page, context, user, control }) => {
  await authenticate(page, user);
  const other = await context.newPage();
  await Promise.all([page.goto('/my'), other.goto('/my')]);
  await expect(page.getByRole('heading', { name: '내 계좌' })).toBeVisible();
  await expect(other.getByRole('heading', { name: '내 계좌' })).toBeVisible();
  await control('advance?seconds=901');
  await Promise.all([page.reload(), other.reload()]);
  await expect(page.getByRole('heading', { name: '내 계좌' })).toBeVisible();
  await expect(other.getByRole('heading', { name: '내 계좌' })).toBeVisible();
  await openNavigation(page);
  const logout = page.waitForResponse(response => response.url().endsWith('/api/auth/logout'));
  await page.getByRole('button', { name: '로그아웃', exact: true }).click();
  expect((await logout).ok()).toBeTruthy();
  await expect(other.getByText('보유 종목, 체결 내역, 모의 투자금은 로그인 후에 볼 수 있어요.')).toBeVisible();
  await other.reload();
  await expect(other.getByText('보유 종목, 체결 내역, 모의 투자금은 로그인 후에 볼 수 있어요.')).toBeVisible();
});

test('로그아웃 후 기존 Access는 거절하고 별도 기기 세션은 유지한다', async ({ page, request, user }) => {
  await authenticate(page, user);
  const tokens = await (await page.request.post('/api/auth/refresh', { headers: authHeaders, data: {} })).json();
  expect((await page.request.post('/api/auth/logout', { headers: authHeaders, data: {} })).ok()).toBeTruthy();
  const old = await request.get(`${API}/api/users/me`, { headers: { Authorization: `Bearer ${tokens.accessToken}` } });
  expect(old.status()).toBe(401);
  expect((await old.json()).code).toBe('SESSION_REVOKED');
  expect((await request.get(`${API}/api/users/me`, { headers: { Authorization: `Bearer ${user.accessToken}` } })).ok()).toBeTruthy();
});

test('비밀번호 변경은 브라우저와 별도 기기의 세션을 모두 폐기한다', async ({ page, request, user }) => {
  await authenticate(page, user);
  const tokens = await (await page.request.post('/api/auth/refresh', { headers: authHeaders, data: {} })).json();
  const changed = await request.put(`${API}/api/users/me/password`, {
    headers: { Authorization: `Bearer ${tokens.accessToken}` },
    data: { currentPassword: PASSWORD, newPassword: 'Changed-password-123!' },
  });
  expect(changed.ok()).toBeTruthy();
  for (const token of [tokens.accessToken, user.accessToken]) {
    expect((await request.get(`${API}/api/users/me`, { headers: { Authorization: `Bearer ${token}` } })).status()).toBe(401);
  }
  await page.reload();
  await expect(page.getByText('보유 종목, 체결 내역, 모의 투자금은 로그인 후에 볼 수 있어요.')).toBeVisible();
});

test('서버 재시작 후에도 세션과 유예 후속 토큰을 복구한다', async ({ page, request, user, control }) => {
  await authenticate(page, user);
  const initial = (await page.context().cookies()).find(cookie => cookie.name === 'baedang_refresh')!.value;
  const refresh = await page.request.post('/api/auth/refresh', { headers: authHeaders, data: {} });
  expect(refresh.ok()).toBeTruthy();
  const successor = (await page.context().cookies()).find(cookie => cookie.name === 'baedang_refresh')!.value;
  await control('restart');
  const replay = await request.post(`${API}/api/auth/refresh`, { data: { refreshToken: initial } });
  expect(replay.ok()).toBeTruthy();
  expect((await replay.json()).refreshToken).toBe(successor);
  await page.reload();
  await expect(page.getByRole('heading', { name: '내 계좌' })).toBeVisible();
});
