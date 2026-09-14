import { test, expect } from '../fixtures/test.js';
import { authenticate, openStock, submit, get } from '../helpers/trading.js';
import { openNavigation, expectNoHorizontalOverflow } from '../helpers/navigation.js';

test('모바일 메뉴에서 랭킹과 종목 상세로 이동한다 @smoke @responsive', async ({ page }) => {
  await page.context().addCookies([{ name: 'iv_intro_seen', value: '1', url: 'http://127.0.0.1:13000' }]);
  await page.goto('/');
  await openNavigation(page);
  await expectNoHorizontalOverflow(page);
  await page.getByRole('button', { name: '랭킹', exact: true }).click();
  await expect(page).toHaveURL(/\/rankings$/);
  await expect(page.getByRole('button', { name: '메뉴 열기', exact: true })).toHaveAttribute('aria-expanded', 'false');
  const stock = page.getByRole('link', { name: /테스트전자 005930/ });
  await expect(stock).toBeVisible();
  await expectNoHorizontalOverflow(page);
  await stock.click();
  await expect(page).toHaveURL(/\/stocks\/005930/);
  const quantity = page.locator('[data-tour="quantity"] input');
  await quantity.scrollIntoViewIfNeeded();
  await expect(quantity).toBeInViewport();
  await expectNoHorizontalOverflow(page);
});

test('모바일 매수 후 보유 카드와 체결 내역을 확인한다 @responsive', async ({ page, request, user }) => {
  await authenticate(page, user); await openStock(page);
  expect((await submit(page, 'market')).status).toBe('FILLED');
  await page.goto('/my');
  await expect(page.getByText('보유수량 1', { exact: true })).toBeVisible();
  await expect(page.getByText('테스트전자').filter({ visible: true })).toBeVisible();
  await expectNoHorizontalOverflow(page);
  await page.getByRole('button', { name: '체결 내역', exact: true }).click();
  const ledger = await get(request, user, '/api/accounts/me/ledger');
  const execution = ledger.items.find((item: { entryType: string }) => item.entryType === 'BUY');
  expect(execution).toBeTruthy();
  await expect(page.getByText(execution.memo, { exact: true }).filter({ visible: true })).toBeVisible();
  await expectNoHorizontalOverflow(page);
});
