import type { Page, APIRequestContext } from '@playwright/test';
import { expect, API } from '../fixtures/test.js';

export async function authenticate(page: Page, user: object) {
  await page.context().addCookies([{ name: 'iv_intro_seen', value: '1', url: 'http://127.0.0.1:13000' }]);
  await page.goto('/login');
  await page.evaluate(value => {
    localStorage.setItem('trading-auth-user', JSON.stringify(value));
    localStorage.setItem('stockDetailTourSeen_v1', '1');
  }, user);
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
  return result.json();
}
export async function get(request: APIRequestContext, user: { accessToken: string; refreshToken?: string }, path: string) {
  let response = await request.get(`${API}${path}`, { headers: { Authorization: `Bearer ${user.accessToken}` } });
  if (response.status() === 401 && user.refreshToken) {
    const refresh = await request.post(`${API}/api/auth/refresh`, { data: { refreshToken: user.refreshToken } });
    expect(refresh.ok()).toBeTruthy();
    user.accessToken = (await refresh.json()).accessToken;
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
