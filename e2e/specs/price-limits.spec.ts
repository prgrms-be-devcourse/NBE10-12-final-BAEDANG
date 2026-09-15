import { test, expect } from '../fixtures/test.js';
import { authenticate, openStock, submit, get } from '../helpers/trading.js';

for (const price of ['9000', '11000']) {
  test(`국내 가격 경계 ${price} 접수 @smoke`, async ({ page, user }) => {
    await authenticate(page, user); await openStock(page);
    expect((await submit(page, 'limit', '매수', '1', price)).status).toBe('PENDING');
  });
}
test('범위 밖 가격과 잘못된 호가 단위는 접수를 막는다 @smoke', async ({ page, user }) => {
  await authenticate(page, user); await openStock(page);
  await page.getByRole('button', { name: '지정가', exact: true }).click();
  await page.locator('[data-tour="quantity"] input').fill('1');
  for (const price of ['8990', '11010', '10001']) {
    const quote = page.waitForResponse(r => r.url().includes('/api/orders/quote/limit?') && new URL(r.url()).searchParams.get('limitPrice') === price);
    await page.getByPlaceholder('예: 72000').fill(price);
    const result = await (await quote).json();
    expect(result.acceptable).toBe(false);
    await expect(page.locator('[data-tour="submit"]')).toBeDisabled();
  }
});
test.describe('미국', () => {
  test.use({ market: 'US' });
  test('NULL 상하한가에서도 시장가 주문이 가능하다 @smoke', async ({ page, user }) => {
    await authenticate(page, user); await openStock(page, 'US');
    await expect(page.getByText('가격 제한 없음').first()).toBeVisible();
    expect((await submit(page, 'market')).status).toBe('FILLED');
  });
});
test('상하한가 데이터 복구 후 견적과 접수를 재개한다', async ({ page, user, control }) => {
  await control('limits?available=false');
  await authenticate(page, user); await openStock(page);
  await page.getByRole('button', { name: '지정가', exact: true }).click();
  await page.locator('[data-tour="quantity"] input').fill('1');
  const unavailable = page.waitForResponse(r => r.url().includes('/api/orders/quote/limit?') && new URL(r.url()).searchParams.get('limitPrice') === '10000');
  await page.getByPlaceholder('예: 72000').fill('10000');
  expect((await (await unavailable).json()).acceptable).toBe(false);
  await expect(page.locator('[data-tour="submit"]')).toBeDisabled();
  await control('limits?available=true');
  await page.getByPlaceholder('예: 72000').fill('10010');
  await expect(page.getByRole('button', { name: '매수 주문 접수', exact: true })).toBeEnabled({ timeout: 15_000 });
});
test('상한가에서 매도 호가가 없는 상태를 표시하고 체결을 기다린다', async ({ page, request, user, control }) => {
  await control('upper'); await control('publish');
  await authenticate(page, user); await openStock(page);
  await expect(page.getByText('매도 호가 없음 · 매수 체결 대기')).toBeVisible();
  const order = await submit(page, 'limit', '매수', '1', '11000');
  await control('tick');
  expect((await get(request, user, `/api/orders/${order.orderId}`)).status).toBe('PENDING');
});
