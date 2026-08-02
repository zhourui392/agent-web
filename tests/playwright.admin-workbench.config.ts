import { defineConfig, devices } from '@playwright/test';
import path from 'path';

const repoRoot = path.resolve(__dirname, '..');

/**
 * 独立 Admin Workbench MPA 的浏览器安全合同。
 *
 * 所有 API 都由 spec 拦截，不启动 Maven、真实数据库或 CLI。
 *
 * @author alex
 * @since 2026-08-02
 */
export default defineConfig({
  testDir: './e2e',
  testMatch: 'admin-workbench.spec.ts',
  fullyParallel: false,
  forbidOnly: !!process.env.CI,
  retries: 0,
  workers: 1,
  reporter: [['list']],
  use: {
    baseURL: 'http://127.0.0.1:5176',
    testIdAttribute: 'data-test',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    actionTimeout: 10_000,
    navigationTimeout: 15_000,
  },
  projects: [{
    name: 'chromium',
    use: {
      ...devices['Desktop Chrome'],
      viewport: { width: 1600, height: 900 },
    },
  }],
  webServer: {
    command: 'npm run dev -- --host 127.0.0.1 --port 5176',
    cwd: path.join(repoRoot, 'frontend'),
    url: 'http://127.0.0.1:5176/admin/workbenches.html',
    reuseExistingServer: true,
    timeout: 60_000,
    stdout: 'pipe',
    stderr: 'pipe',
  },
});
