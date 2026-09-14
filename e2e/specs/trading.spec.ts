import { test, expect } from '../fixtures/test.js';
import { authenticate, openStock, submit, get, cancel } from '../helpers/trading.js';

test('시장가 매수와 매도를 실제 계좌에 반영한다 @smoke', async ({ page, request, user }) => {
  await authenticate(page, user); await openStock(page);
  const bought = await submit(page, 'market');
  expect(bought.status).toBe('FILLED');
  const holdings = await get(request, user, '/api/accounts/me/holdings');
  expect(holdings.items.find((item: { symbol: string }) => item.symbol === '005930').quantity).toBe('1');
  const sold = await submit(page, 'market', '매도');
  expect(sold.status).toBe('FILLED');
  const account = await get(request, user, '/api/accounts/me');
  expect(Number(account.cashBalance)).toBeLessThan(50_000_000);
  await page.goto('/my');
  await expect(page.getByRole('heading', { name: '내 계좌' })).toBeVisible();
});

test('지정가 접수 후 취소하면 예약금을 해제한다 @smoke', async ({ page, request, user }) => {
  await authenticate(page, user); await openStock(page);
  const order = await submit(page, 'limit');
  expect(order.status).toBe('PENDING');
  expect(Number(order.reservedCash)).toBeGreaterThan(0);
  await page.goto('/my');
  await page.getByRole('button', { name: '주문 내역', exact: true }).click();
  await page.getByRole('button', { name: '상세', exact: true }).click();
  await page.getByRole('button', { name: '주문 취소', exact: true }).click();
  await expect(page.getByText('취소됨', { exact: true }).first()).toBeVisible();
  const canceled = await get(request, user, `/api/orders/${order.orderId}`);
  expect(canceled.status).toBe('CANCELED');
  expect(Number(canceled.reservedCash)).toBe(0);
  expect(Number((await get(request, user, '/api/accounts/me')).cashBalance)).toBe(50_000_000);
});

test('실제 V2 호가와 워커로 지정가를 체결한다 @smoke', async ({ page, request, user, control }) => {
  await authenticate(page, user); await openStock(page);
  const order = await submit(page, 'limit', '매수', '1', '11000');
  await control('publish'); await control('tick');
  const filled = await get(request, user, `/api/orders/${order.orderId}`);
  expect(filled.status).toBe('FILLED');
  expect(filled.filledQuantity).toBe('1');
  await page.goto('/my');
  await expect(page.getByText('테스트전자').first()).toBeVisible();
});

test('지정가 매도 예약수량은 취소 후 다시 주문할 수 있다 @smoke', async ({ page, request, user }) => {
  await authenticate(page, user); await openStock(page);
  await submit(page, 'market', '매수', '2');
  const order = await submit(page, 'limit', '매도', '2', '11000');
  const query = '/api/orders/quote/limit?symbol=005930&marketCountry=KR&side=SELL&quantity=2&limitPrice=11000&limitCurrency=KRW';
  const reserved = await get(request, user, query);
  expect(reserved.availableQuantity).toBe('0');
  expect(reserved.acceptable).toBe(false);
  await cancel(request, user, order.orderId);
  const released = await get(request, user, query);
  expect(released.availableQuantity).toBe('2');
  expect(released.acceptable).toBe(true);
  expect((await submit(page, 'limit', '매도', '2', '11000')).status).toBe('PENDING');
});
