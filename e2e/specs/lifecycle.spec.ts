import { test, expect, API } from '../fixtures/test.js';
import { authenticate, openStock, submit, get, cancel } from '../helpers/trading.js';

test('부분 체결 후 취소는 체결 이력을 유지하고 잔량을 해제한다', async ({ page, request, user, control }) => {
  await authenticate(page, user); await openStock(page);
  const order = await submit(page, 'limit', '매수', '20', '11000');
  await control('publish'); await control('liquidity'); await control('tick');
  const partial = await get(request, user, `/api/orders/${order.orderId}`);
  expect(partial.status).toBe('PARTIALLY_FILLED');
  expect(Number(partial.filledQuantity)).toBeGreaterThan(0);
  expect(Number(partial.filledQuantity)).toBeLessThan(20);
  const canceled = await cancel(request, user, order.orderId);
  expect(canceled.status).toBe('CANCELED');
  expect(canceled.filledQuantity).toBe(partial.filledQuantity);
  expect(Number(canceled.reservedCash)).toBe(0);
  await page.goto('/my');
  await expect(page.getByText('테스트전자').first()).toBeVisible();
});
test('정규장 종료 후 지정가가 만료된다', async ({ page, request, user, control }) => {
  await authenticate(page, user); await openStock(page);
  const order = await submit(page, 'limit');
  await control('advance?seconds=19800'); await control('expire');
  const expired = await get(request, user, `/api/orders/${order.orderId}`);
  expect(expired.status).toBe('EXPIRED'); expect(Number(expired.reservedCash)).toBe(0);
  await page.goto('/my');
  await page.getByRole('button', { name: '주문 내역', exact: true }).click();
  await expect(page.getByText('만료', { exact: true }).first()).toBeVisible();
});
test('CB는 기존 지정가를 보류하지만 취소는 허용한다', async ({ page, request, user, control }) => {
  await authenticate(page, user); await openStock(page);
  const order = await submit(page, 'limit', '매수', '1', '11000');
  await control('publish'); await control('halt'); await control('tick');
  const pending = await get(request, user, `/api/orders/${order.orderId}`);
  expect(pending.status).toBe('PENDING'); expect(pending.reservedCash).toBe(order.reservedCash);
  expect((await cancel(request, user, order.orderId)).status).toBe('CANCELED');
  await page.goto('/my'); await page.getByRole('button', { name: '주문 내역', exact: true }).click();
  await expect(page.getByText('취소됨', { exact: true }).first()).toBeVisible();
});
test('CB 발동 중 신규 주문은 거절 안내를 표시한다', async ({ page, user, control }) => {
  await authenticate(page, user); await openStock(page); await control('halt');
  await page.locator('[data-tour="quantity"] input').fill('1');
  const response = page.waitForResponse(r => r.url().endsWith('/api/orders/market') && r.request().method() === 'POST');
  await page.getByRole('button', { name: '매수하기', exact: true }).click();
  expect((await (await response).json()).code).toBe('MARKET_TRADING_HALTED');
  await expect(page.getByText(/서킷브레이커|시장.*중단/).first()).toBeVisible();
});
test('계좌 초기화는 새 회차를 만들고 이전 보유를 분리한다', async ({ page, request, user }) => {
  await authenticate(page, user); await openStock(page); await submit(page, 'market');
  await page.goto('/my');
  await page.getByRole('button', { name: '포트폴리오 초기화', exact: true }).click();
  await page.getByRole('button', { name: '초기화할게요', exact: true }).click();
  await expect(page.getByText('2회차', { exact: true })).toBeVisible();
  const account = await get(request, user, '/api/accounts/me');
  expect(account.accountId).not.toBe(user.account.accountId);
  expect(Number(account.cashBalance)).toBe(50_000_000);
});
test('같은 주문 요청을 재전송해도 한 번만 체결한다', async ({ page, request, user }) => {
  await authenticate(page, user); await openStock(page);
  const sent = page.waitForRequest(r => r.url().endsWith('/api/orders/market') && r.method() === 'POST');
  const order = await submit(page, 'market');
  const data = (await sent).postDataJSON();
  const replay = await request.post(`${API}/api/orders/market`, { headers: { Authorization: `Bearer ${user.accessToken}` }, data });
  expect(replay.ok()).toBeTruthy(); expect((await replay.json()).orderId).toBe(order.orderId);
  expect(Number((await get(request, user, '/api/accounts/me')).cashBalance)).toBe(49_989_999);
});
test('만료된 access token은 실제 refresh API로 복구된다', async ({ page, user, control }) => {
  await authenticate(page, user); await control('advance?seconds=901');
  const refresh = page.waitForResponse(r => r.url().endsWith('/api/auth/refresh'));
  await page.goto('/my');
  expect((await refresh).ok()).toBeTruthy();
  await expect(page.getByRole('heading', { name: '내 계좌' })).toBeVisible();
  const stored = await page.evaluate(() => JSON.parse(localStorage.getItem('trading-auth-user') ?? '{}'));
  expect(stored.accessToken).not.toBe(user.accessToken);
});

test('주문 버튼 연속 클릭은 요청과 체결을 한 번만 만든다', async ({ page, request, user }) => {
  await authenticate(page, user); await openStock(page);
  await page.locator('[data-tour="quantity"] input').fill('1');
  const button = page.getByRole('button', { name: '매수하기', exact: true });
  await expect(button).toBeEnabled();
  let sent = 0;
  page.on('request', r => { if (r.url().endsWith('/api/orders/market') && r.method() === 'POST') sent++; });
  const response = page.waitForResponse(r => r.url().endsWith('/api/orders/market') && r.request().method() === 'POST');
  await button.click({ clickCount: 2 });
  expect((await response).ok()).toBeTruthy();
  await expect(button).toBeEnabled();
  expect(sent).toBe(1);
  expect(Number((await get(request, user, '/api/accounts/me')).cashBalance)).toBe(49_989_999);
});
