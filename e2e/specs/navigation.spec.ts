import { test, expect, PASSWORD } from '../fixtures/test.js';
import { expectNoHorizontalOverflow } from '../helpers/navigation.js';

test('소개 화면에서 시작하고 재방문해도 소개 화면을 표시한다 @smoke @responsive', async ({ page }) => {
  await page.goto('/');
  await expect(page).toHaveURL(/\/intro$/);
  await expect(page.getByText('Enter 키를 누르면 SKIP이 가능합니다.', { exact: true })).toBeVisible();
  // 마지막 섹션의 CTA는 단순 노출이 아니라 페이지 스크롤 진행률로 활성화됩니다.
  await page.evaluate(() => window.scrollTo(0, document.documentElement.scrollHeight));
  await expect(page.getByRole('link', { name: '시작하기', exact: true })).toHaveCSS('opacity', '1');
  await page.getByRole('link', { name: '시작하기', exact: true }).click();
  await expect(page).toHaveURL(/\/main$/);
  await expectNoHorizontalOverflow(page);
  await page.goto('/');
  await expect(page).toHaveURL(/\/intro$/);
  await expect(page.getByRole('link', { name: '시작하기', exact: true })).toBeAttached();
});

test('소개 화면은 Enter로 건너뛸 수 있다', async ({ page }) => {
  await page.goto('/intro');
  await expect(page.getByText('Enter 키를 누르면 SKIP이 가능합니다.', { exact: true })).toBeVisible();
  await page.keyboard.press('Enter');
  await expect(page).toHaveURL(/\/main$/);
});

test('다음 경로 없이 로그인하면 메인으로 이동한다', async ({ page, user }) => {
  await page.goto('/login');
  await page.getByPlaceholder('you@example.com').fill(user.email);
  await page.getByPlaceholder('비밀번호를 입력하세요').fill(PASSWORD);
  await page.locator('form').getByRole('button', { name: '로그인', exact: true }).click();
  await expect(page).toHaveURL(/\/main$/);
  await expect.poll(() => page.evaluate(() => JSON.parse(localStorage.getItem('trading-auth-user') ?? 'null')?.email)).toBe(user.email);
});
