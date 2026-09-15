import { test, expect, PASSWORD } from '../fixtures/test.js';
import { openNavigation } from '../helpers/navigation.js';

test('로그인 후 새로고침해도 인증을 유지한다 @smoke @responsive', async ({ page, user }) => {
  await page.goto('/login?next=/my');
  await page.getByPlaceholder('you@example.com').fill(user.email);
  await page.getByPlaceholder('비밀번호를 입력하세요').fill(PASSWORD);
  await page.locator('form').getByRole('button', { name: '로그인', exact: true }).click();
  await expect(page).toHaveURL('http://127.0.0.1:13000/my');
  await page.reload();
  await expect(page.getByRole('heading', { name: '내 계좌' })).toBeVisible();
  expect(await page.evaluate(() => localStorage.getItem('trading-auth-user'))).toBeNull();
  expect((await page.context().cookies()).find(cookie => cookie.name === 'baedang_refresh')?.httpOnly).toBe(true);
  await page.goto('/my');
  await expect(page.getByRole('heading', { name: '내 계좌' })).toBeVisible();
  await openNavigation(page);
  const logout = page.waitForResponse(r => r.url().endsWith('/api/auth/logout'));
  await page.getByRole('button', { name: '로그아웃', exact: true }).click();
  expect((await logout).ok()).toBeTruthy();
  await expect.poll(() => page.evaluate(() => localStorage.getItem('trading-auth-user'))).toBeNull();
  await page.goto('/my');
  await expect(page.getByText('보유 종목, 체결 내역, 모의 투자금은 로그인 후에 볼 수 있어요.')).toBeVisible();
});
