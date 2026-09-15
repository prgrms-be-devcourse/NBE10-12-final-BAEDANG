import { test, expect } from '../fixtures/test.js';
import { authenticate, get } from '../helpers/trading.js';
import { expectNoHorizontalOverflow } from '../helpers/navigation.js';

test('찜한 종목을 유지하고 상세 이동과 찜 해제를 구분한다 @responsive', async ({ page, request, user }) => {
  await authenticate(page, user);
  await page.goto('/rankings');
  const ranked = page.getByRole('link', { name: /테스트전자 005930/ });
  await ranked.getByRole('button', { name: '찜하기', exact: true }).click();
  await expect(ranked.getByRole('button', { name: '찜 해제하기' })).toBeVisible();
  await expect(page).toHaveURL(/\/rankings$/);
  await expect.poll(async () => (await get(request, user, '/api/stocks/likes')).items.length).toBe(1);

  await page.goto('/my');
  await page.getByRole('button', { name: '관심 종목', exact: true }).click();
  const liked = page.getByRole('link', { name: /테스트전자 005930/ });
  await expect(liked).toBeVisible();
  await page.reload();
  await page.getByRole('button', { name: '관심 종목', exact: true }).click();
  await expect(liked).toBeVisible();
  await expectNoHorizontalOverflow(page);
  await liked.click();
  await expect(page).toHaveURL(/\/stocks\/005930\?marketCountry=KR$/);

  await page.goto('/my');
  await page.getByRole('button', { name: '관심 종목', exact: true }).click();
  await liked.getByRole('button', { name: '찜 해제하기' }).click();
  await expect(liked).toHaveCount(0);
  await expect(page.getByText('찜한 종목이 없어요', { exact: true })).toBeVisible();
  await expect(page).toHaveURL(/\/my$/);
  await expect.poll(async () => (await get(request, user, '/api/stocks/likes')).items.length).toBe(0);
});
