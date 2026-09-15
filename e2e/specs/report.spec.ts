import { test, expect, API, PASSWORD } from '../fixtures/test.js';
import { authenticate, openStock, submit } from '../helpers/trading.js';

test('공개된 투자 성향 이미지를 확대하고 닫는다', async ({ page, request, user, control }) => {
  await authenticate(page, user);
  await openStock(page);
  await submit(page, 'market');
  // 시나리오 기준일의 한국 10시에서 뉴욕 정규장 10시까지 13시간 이동합니다.
  await control('advance?seconds=46800');
  await openStock(page, 'US');
  await submit(page, 'market');
  // 운영 리포트의 4주 잠금 정책을 그대로 사용하고, 제어 API의 하루 상한을 지킵니다.
  for (let day = 0; day < 28; day++) await control('advance?seconds=86400');
  const login = await request.post(`${API}/api/auth/login`, { data: { email: user.email, password: PASSWORD } });
  expect(login.ok()).toBeTruthy();
  await authenticate(page, await login.json());
  const response = page.waitForResponse(r => r.url().endsWith('/api/reports/me'));
  await page.goto('/my');
  const report = await (await response).json();
  expect(report.locked).toBe(false);
  expect(report.typeCode).toBeTruthy();
  const image = page.getByRole('img', { name: / 이미지$/ });
  await expect(image).toHaveCount(1);
  await image.click();
  await expect(image).toHaveCount(2);
  await page.getByRole('button', { name: '닫기', exact: true }).click();
  await expect(image).toHaveCount(1);
});
