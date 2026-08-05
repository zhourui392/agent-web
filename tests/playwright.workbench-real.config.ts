import { randomBytes } from 'crypto';
import { defineConfig, devices } from '@playwright/test';
import { mkdirSync } from 'fs';
import { tmpdir } from 'os';
import path from 'path';

const repoRoot = path.resolve(__dirname, '..');
mkdirSync(path.join(repoRoot, 'data'), { recursive: true });
const springProfiles = process.platform === 'win32'
  ? 'e2e,e2e-workbench'
  : 'e2e,e2e-linux,e2e-workbench';
const jdk21Home = process.env.JAVA_HOME && !process.env.JAVA_HOME.includes('jdk8')
  ? process.env.JAVA_HOME
  : process.platform === 'win32'
    ? 'C:\\Program Files\\Java\\jdk-21.0.9'
    : '/usr/lib/jvm/java-21-openjdk-amd64';
const pathSeparator = process.platform === 'win32' ? ';' : ':';
const userStorageState = path.join(__dirname, '.auth', 'user.json');
const backendPort = '18109';
const frontendPort = '5186';
const e2eDatabasePath = path.join(
  tmpdir(),
  `agent-web-workbench-e2e-${process.pid}-${randomBytes(12).toString('hex')}.db`,
);
const e2eAdminPassword = process.env.AGENT_E2E_ADMIN_PASSWORD
  || `Workbench-${randomBytes(24).toString('base64url')}`;
process.env.AGENT_E2E_ADMIN_PASSWORD = e2eAdminPassword;

/**
 * Controller → SQLite → 公共 Runtime Stub → SSE → Vue 的真实 Workbench 浏览器链路。
 *
 * @author alex
 * @since 2026-08-01
 */
export default defineConfig({
  testDir: './e2e',
  testMatch: 'workbench-real.spec.ts',
  fullyParallel: false,
  forbidOnly: !!process.env.CI,
  retries: 0,
  workers: 1,
  reporter: [['list']],
  globalSetup: './e2e/global-setup.ts',
  use: {
    baseURL: `http://127.0.0.1:${frontendPort}`,
    testIdAttribute: 'data-test',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    actionTimeout: 10_000,
    navigationTimeout: 20_000,
    storageState: userStorageState,
  },
  projects: [{
    name: 'chromium',
    use: { ...devices['Desktop Chrome'], viewport: { width: 1600, height: 900 } },
  }],
  webServer: {
    command: `bash ${path.join(repoRoot, 'tests', 'scripts', 'e2e-start.sh')}`,
    cwd: repoRoot,
    env: {
      ...process.env,
      JAVA_HOME: jdk21Home,
      PATH: `${path.join(jdk21Home, 'bin')}${pathSeparator}${process.env.PATH || ''}`,
      GIT_CRED_ENC_KEY: process.env.GIT_CRED_ENC_KEY
        || randomBytes(32).toString('base64'),
      VITE_API_PROXY_TARGET: `http://127.0.0.1:${backendPort}`,
      VITE_HOST: '127.0.0.1',
      VITE_PORT: frontendPort,
      SERVER_PORT: backendPort,
      MANAGEMENT_SERVER_PORT: '0',
      SPRING_DATASOURCE_URL: `jdbc:sqlite:${e2eDatabasePath}`,
      AGENT_E2E_SKIP_SHARED_DATABASE_CLEANUP: 'true',
      SPRING_PROFILES: springProfiles,
      AGENT_BOOTSTRAP_ADMIN_PASSWORD: e2eAdminPassword,
      AGENT_E2E_ADMIN_PASSWORD: e2eAdminPassword,
      AGENT_E2E_WORKBENCH_RUNTIME_KEY: randomBytes(32).toString('hex'),
      AGENT_E2E_WORKBENCH_CODEX_COMMAND: path.join(
        repoRoot,
        'tests',
        'e2e',
        'fixtures',
        process.platform === 'win32' ? 'workbench-runtime-stub.cmd' : 'workbench-runtime-stub.sh',
      ),
    },
    url: `http://127.0.0.1:${backendPort}/api/auth/status`,
    reuseExistingServer: false,
    timeout: 180_000,
    stdout: 'pipe',
    stderr: 'pipe',
  },
});
