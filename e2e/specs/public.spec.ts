import { test, expect, PASSWORD } from '../fixtures/test.js';
import { randomUUID } from 'node:crypto';

test('회원가입 화면에서 약관 동의 후 새 계좌를 생성한다 @smoke', async ({ page }) => {
  await page.goto('/signup?next=/my');
  await page.getByPlaceholder('you@example.com').fill(`signup-${randomUUID()}@example.com`);
  await page.getByPlaceholder('2~20자').fill('가입테스터');
  await page.getByPlaceholder('8자 이상 입력하세요').fill(PASSWORD);
  await page.getByPlaceholder('비밀번호를 다시 입력하세요').fill(PASSWORD);
  await page.locator('form input[type="checkbox"]').click();
  await page.getByRole('checkbox', { name: '위 이용약관과 개인정보 처리방침을 모두 확인했으며 동의합니다.' }).click();
  await expect(page.locator('form input[type="checkbox"]')).toBeChecked();
  await page.getByRole('button', { name: '모의 투자금 받고 시작하기' }).click();
  await expect(page).toHaveURL(/\/my$/);
  await expect(page.getByRole('heading', { name: '내 계좌' })).toBeVisible();
  await expect(page.getByText('1회차', { exact: true })).toBeVisible();
});
test('비로그인 사용자는 종목을 조회하고 주문 시 로그인 안내를 받는다 @smoke', async ({ page }) => {
  await page.context().addCookies([{ name: 'iv_intro_seen', value: '1', url: 'http://127.0.0.1:13000' }]);
  await page.goto('/rankings');
  await expect(page.getByText('테스트전자').filter({ visible: true }).first()).toBeVisible();
  await page.getByRole('link', { name: /테스트전자 005930/ }).click();
  await expect(page).toHaveURL(/\/stocks\/005930/);
  await expect(page.getByText('비로그인 상태에서 누르면 회원가입으로 안내돼요')).toBeVisible();
});
