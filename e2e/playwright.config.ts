import { defineConfig, devices } from '@playwright/test';

export default defineConfig({
  testDir: './tests',
  fullyParallel: false, // serial — Grails shares session state across tests
  retries: 1, // tolerate cold-start flakes on first request after fresh boot (CI); real failures still fail twice
  use: {
    baseURL: process.env.BASE_URL || 'http://localhost',
    trace: 'retain-on-failure',
  },
  projects: [
    { name: 'chromium', use: { ...devices['Desktop Chrome'] } },
  ],
});
