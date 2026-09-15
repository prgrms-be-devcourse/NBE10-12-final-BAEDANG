import { defineConfig, devices } from '@playwright/test';

export default defineConfig({
  testDir: './specs',
  fullyParallel: false,
  workers: 1,
  retries: 0,
  forbidOnly: !!process.env.CI,
  timeout: 60_000,
  expect: { timeout: 10_000 },
  reporter: [['list'], ['html', { open: 'never' }]],
  use: {
    baseURL: 'http://127.0.0.1:13000',
    timezoneId: 'Asia/Seoul',
    locale: 'ko-KR',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  projects: [
    { name: 'chromium', testIgnore: '**/responsive.spec.ts', use: { ...devices['Desktop Chrome'] } },
    {
      name: 'mobile-chromium',
      grep: /@responsive/,
      use: { ...devices['Pixel 5'], viewport: { width: 375, height: 812 } },
    },
  ],
});
