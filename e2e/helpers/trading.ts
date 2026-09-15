import type { Page, APIRequestContext } from '@playwright/test';
import { expect, API, PASSWORD } from '../fixtures/test.js';

export async function authenticate(page: Page, user: { email: string }) {
  // 검증용 API와 브라우저는 독립 세션을 사용합니다. 브라우저에는 HttpOnly 쿠키만 심습니다.
  const response = await page.request.post('/api/auth/login', {
    headers: { Origin: 'http://127.0.0.1:13000', 'X-Auth-Request': '1' },
    data: { email: user.email, password: PASSWORD },
  });
  expect(response.ok(), await response.text()).toBeTruthy();
  await page.goto('/my');
  await expect(page.getByRole('heading', { name: '내 계좌' })).toBeVisible();
  await page.evaluate(() => localStorage.setItem('stockDetailTourSeen_v1', '1'));
}
export async function openStock(page: Page, country = 'KR') {
  await page.goto(`/stocks/${country === 'KR' ? '005930' : 'AAPL'}?marketCountry=${country}`);
  await expect(page.getByText(country === 'KR' ? '테스트전자' : '테스트애플').first()).toBeVisible();
}
export async function submit(page: Page, type: 'market' | 'limit', side = '매수', quantity = '1', price = '10000') {
  await page.getByRole('button', { name: type === 'market' ? '시장가' : '지정가', exact: true }).click();
  await page.getByRole('button', { name: side, exact: true }).click();
  await page.locator('[data-tour="quantity"] input').fill(quantity);
  if (type === 'limit') await page.getByPlaceholder('예: 72000').fill(price);
  const button = page.getByRole('button', { name: type === 'market' ? `${side}하기` : `${side} 주문 접수`, exact: true });
  await expect(button).toBeEnabled();
  const response = page.waitForResponse(r => r.url().endsWith(`/api/orders/${type}`) && r.request().method() === 'POST');
  await button.click();
  const result = await response;
  expect(result.ok(), await result.text()).toBeTruthy();
  const confirmation = page.getByRole('heading', {
    name: type === 'market' ? '거래가 체결됐어요' : '주문이 접수됐어요', exact: true,
  });
  await expect(confirmation).toBeVisible();
  await confirmation.locator('..').getByRole('button', { name: '확인', exact: true }).click();
  await expect(confirmation).toBeHidden();
  return result.json();
}
export async function get(request: APIRequestContext, user: { accessToken: string; refreshToken?: string }, path: string) {
  let response = await request.get(`${API}${path}`, { headers: { Authorization: `Bearer ${user.accessToken}` } });
  if (response.status() === 401 && user.refreshToken) {
    const refresh = await request.post(`${API}/api/auth/refresh`, { data: { refreshToken: user.refreshToken } });
    expect(refresh.ok()).toBeTruthy();
    const tokens = await refresh.json();
    user.accessToken = tokens.accessToken;
    user.refreshToken = tokens.refreshToken;
    response = await request.get(`${API}${path}`, { headers: { Authorization: `Bearer ${user.accessToken}` } });
  }
  expect(response.ok(), await response.text()).toBeTruthy();
  return response.json();
}
export async function cancel(request: APIRequestContext, user: { accessToken: string }, id: number) {
  const response = await request.patch(`${API}/api/orders/${id}`, {
    headers: { Authorization: `Bearer ${user.accessToken}` }, data: { status: 'CANCELED' },
  });
  expect(response.ok(), await response.text()).toBeTruthy();
  return response.json();
}
