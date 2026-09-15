import { test, expect } from '../fixtures/test.js';
import { authenticate, openStock } from '../helpers/trading.js';

test('숨김 상태에서는 환율 폴링을 중단하고 복귀하면 재개한다', async ({ page, user }) => {
  await authenticate(page, user);
  await page.clock.install({ time: new Date('2026-09-14T01:00:00Z') });
  let requests = 0;
  page.on('request', r => { if (r.url().includes('/exchange-rates/latest')) requests++; });
  await page.goto('/my');
  await expect(page.getByRole('heading', { name: '내 계좌' })).toBeVisible();
  await expect.poll(() => requests).toBeGreaterThan(0);
  await page.evaluate(() => {
    Object.defineProperty(document, 'hidden', { configurable: true, get: () => true });
    Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => 'hidden' });
    document.dispatchEvent(new Event('visibilitychange'));
  });
  const before = requests;
  await page.clock.runFor(120_000);
  expect(requests).toBe(before);
  await page.evaluate(() => {
    Object.defineProperty(document, 'hidden', { configurable: true, get: () => false });
    Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => 'visible' });
    document.dispatchEvent(new Event('visibilitychange'));
  });
  await page.clock.runFor(60_001);
  await expect.poll(() => requests).toBeGreaterThan(before);
});

test('이전 입력의 늦은 견적 응답은 최신 입력 결과를 덮어쓰지 않는다', async ({ page, user }) => {
  await authenticate(page, user); await openStock(page);
  let release!: () => void;
  const held = new Promise<void>(resolve => { release = resolve; });
  let captured!: () => void;
  const ready = new Promise<void>(resolve => { captured = resolve; });
  await page.route('**/api/orders/quote/limit?**', async route => {
    const response = await route.fetch();
    if (new URL(route.request().url()).searchParams.get('limitPrice') === '10000') {
      captured(); await held;
    }
    await route.fulfill({ response });
  });
  try {
    await page.getByRole('button', { name: '지정가', exact: true }).click();
    await page.locator('[data-tour="quantity"] input').fill('1');
    await page.getByPlaceholder('예: 72000').fill('10000');
    await ready;
    const latest = page.waitForResponse(r => r.url().includes('/api/orders/quote/limit?') && new URL(r.url()).searchParams.get('limitPrice') === '11000');
    await page.getByPlaceholder('예: 72000').fill('11000');
    await latest;
    await expect(page.getByRole('button', { name: '매수 주문 접수', exact: true })).toBeEnabled();
    const old = page.waitForResponse(r => r.url().includes('/api/orders/quote/limit?') && new URL(r.url()).searchParams.get('limitPrice') === '10000');
    release(); await old;
    await expect(page.getByPlaceholder('예: 72000')).toHaveValue('11000');
    await expect(page.getByText('11,001', { exact: true }).first()).toBeVisible();
  } finally { release(); }
});
