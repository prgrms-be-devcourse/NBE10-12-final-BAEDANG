import { test, expect, PASSWORD } from '../fixtures/test.js';

test('비밀번호 찾기 요청 후 로그인으로 돌아갈 수 있다', async ({ page, user }) => {
  await page.goto('/login');
  await page.getByRole('link', { name: '비밀번호를 잊으셨나요?' }).click();
  await expect(page).toHaveURL(/\/forgot-password$/);
  await page.getByPlaceholder('you@example.com').fill(user.email);
  const response = page.waitForResponse(r => r.url().endsWith('/api/auth/password/forgot') && r.request().method() === 'POST');
  await page.getByRole('button', { name: '재설정 메일 보내기' }).click();
  expect((await response).ok()).toBeTruthy();
  await expect(page.getByRole('heading', { name: '메일함을 확인해주세요' })).toBeVisible();
  await page.getByRole('link', { name: '로그인으로 돌아가기' }).click();
  await expect(page).toHaveURL(/\/login$/);
});

test('토큰 없는 재설정 링크는 재요청을 안내한다', async ({ page }) => {
  await page.goto('/reset-password');
  await expect(page.getByRole('heading', { name: '재설정 링크가 올바르지 않아요' })).toBeVisible();
  await expect(page.getByRole('button', { name: '비밀번호 변경하기' })).toHaveCount(0);
  await page.getByRole('link', { name: '비밀번호 찾기로 가기' }).click();
  await expect(page).toHaveURL(/\/forgot-password$/);
});

test('비밀번호 불일치와 유효하지 않은 재설정 토큰을 처리한다', async ({ page }) => {
  await page.goto('/reset-password?token=e2e-invalid-token');
  await page.getByPlaceholder('8자 이상 입력하세요').fill(PASSWORD);
  await page.getByPlaceholder('비밀번호를 다시 입력하세요').fill(`${PASSWORD}different`);
  let submitted = 0;
  page.on('request', r => { if (r.url().endsWith('/api/auth/password/reset') && r.method() === 'POST') submitted++; });
  await page.getByRole('button', { name: '비밀번호 변경하기' }).click();
  await expect(page.getByText('비밀번호가 서로 달라요.', { exact: true }).first()).toBeVisible();
  expect(submitted).toBe(0);
  await page.getByPlaceholder('비밀번호를 다시 입력하세요').fill(PASSWORD);
  const response = page.waitForResponse(r => r.url().endsWith('/api/auth/password/reset') && r.request().method() === 'POST');
  await page.getByRole('button', { name: '비밀번호 변경하기' }).click();
  const failed = await response;
  expect(failed.status()).toBe(400);
  const error = await failed.json();
  expect(error.code).toBe('PASSWORD_RESET_TOKEN_INVALID');
  await expect(page.getByText(error.message, { exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: '비밀번호 변경하기' })).toBeEnabled();
});
