import { defineConfig, devices } from '@playwright/test';
import path from 'path';

const repoRoot = path.resolve(__dirname, '..');

/**
 * Workbench browser contract suite.
 *
 * All /api routes are fulfilled by the spec so this profile never starts Maven or a real CLI.
 * The repository's normal playwright.config.ts remains the real-backend E2E entry point.
 */
export default defineConfig({
  testDir: './e2e',
  testMatch: 'workbench.spec.ts',
  fullyParallel: false,
  forbidOnly: !!process.env.CI,
  retries: 0,
  workers: 1,
  reporter: [['list']],
  use: {
    baseURL: 'http://127.0.0.1:5175',
    testIdAttribute: 'data-test',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    actionTimeout: 10_000,
    navigationTimeout: 15_000,
  },
  projects: [
    {
      name: 'chromium',
      use: {
        ...devices['Desktop Chrome'],
        viewport: { width: 1600, height: 900 },
      },
    },
  ],
  webServer: {
    command: 'npm run dev -- --host 127.0.0.1 --port 5175',
    cwd: path.join(repoRoot, 'frontend'),
    url: 'http://127.0.0.1:5175/workbench.html',
    reuseExistingServer: true,
    timeout: 60_000,
    stdout: 'pipe',
    stderr: 'pipe',
  },
});
