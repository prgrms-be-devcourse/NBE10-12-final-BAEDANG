import { test as base, expect } from '@playwright/test';
import { randomUUID } from 'node:crypto';

export const API = 'http://127.0.0.1:18088';
export const PASSWORD = 'E2e-password-123!';
type User = { email: string; nickname: string; accessToken: string; refreshToken: string; account: { accountId: number } };
type Fixtures = {
  market: 'KR' | 'US';
  scenario: void;
  control: (action: string) => Promise<void>;
  user: User;
};
export const test = base.extend<Fixtures>({
  market: ['KR', { option: true }],
  control: async ({ request }, use) => {
    const key = process.env.E2E_CONTROL_TOKEN;
    if (!key) throw new Error('Run E2E through npm test');
    await use(async action => {
      const response = await request.post(`http://127.0.0.1:18089/${action}`, {
        headers: { 'X-E2E-Key': key }, timeout: 120_000,
      });
      expect(response.ok(), `${action}: ${await response.text()}`).toBeTruthy();
    });
  },
  scenario: [async ({ control, market }, use) => {
    try { await control(`reset?market=${market}`); await use(); }
    finally { await control('clear'); }
  }, { auto: true, timeout: 150_000 }],
  user: async ({ request, scenario }, use) => {
    void scenario;
    const response = await request.post(`${API}/api/auth/signup`, { data: {
      email: `e2e-${randomUUID()}@example.com`, nickname: '통합테스터', password: PASSWORD,
    } });
    expect(response.ok(), await response.text()).toBeTruthy();
    const payload = await response.json();
    await use(payload);
  },
});
export { expect };
