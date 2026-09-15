import type { Page } from '@playwright/test';
import { expect } from '../fixtures/test.js';

export async function openNavigation(page: Page) {
  const toggle = page.getByRole('button', { name: '메뉴 열기', exact: true });
  if (await toggle.isVisible()) {
    await toggle.click();
    await expect(page.getByRole('button', { name: '메뉴 닫기', exact: true })).toHaveAttribute('aria-expanded', 'true');
  }
}

export async function expectNoHorizontalOverflow(page: Page) {
  await expect.poll(() => page.evaluate(() =>
    document.documentElement.scrollWidth - document.documentElement.clientWidth,
  )).toBeLessThanOrEqual(1);
}
