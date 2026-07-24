import { defineConfig, devices } from '@playwright/test';
import { mkdirSync } from 'fs';
import path from 'path';

const repoRoot = path.resolve(__dirname, '..');
mkdirSync(path.join(repoRoot, 'data'), { recursive: true });
const springProfiles = process.platform === 'win32'
  ? 'e2e,e2e-harness'
  : 'e2e,e2e-linux,e2e-harness,e2e-harness-linux';
const configuredJavaHome = process.env.JAVA_HOME || '';
const jdk21Home = process.platform === 'win32'
  ? (configuredJavaHome && /jdk-?2[1-9]/i.test(configuredJavaHome)
      ? configuredJavaHome : 'C:\\Program Files\\Java\\jdk-21.0.9')
  : (configuredJavaHome && !configuredJavaHome.includes('jdk8')
      ? configuredJavaHome : '/usr/lib/jvm/java-21-openjdk-amd64');
const pathSeparator = process.platform === 'win32' ? ';' : ':';
const webServerEnv = {
  ...process.env,
  JAVA_HOME: jdk21Home,
  PATH: `${path.join(jdk21Home, 'bin')}${pathSeparator}${process.env.PATH || ''}`,
  GIT_CRED_ENC_KEY: process.env.GIT_CRED_ENC_KEY
    || 'MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=',
} as Record<string, string>;

export default defineConfig({
  testDir: './e2e',
  testMatch: ['harness.spec.ts'],
  timeout: 180_000,
  fullyParallel: false,
  forbidOnly: !!process.env.CI,
  retries: 0,
  workers: 1,
  reporter: [['list'], ['html', { open: 'never', outputFolder: 'playwright-report-harness' }]],
  globalSetup: './e2e/global-setup.ts',
  use: {
    baseURL: 'http://localhost:18100',
    testIdAttribute: 'data-test',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'retain-on-failure',
    actionTimeout: 10_000,
    navigationTimeout: 15_000,
    storageState: path.join(__dirname, '.auth', 'user.json'),
  },
  projects: [
    { name: 'chromium', use: { ...devices['Desktop Chrome'], viewport: { width: 1600, height: 900 } } },
  ],
  webServer: {
    command: `mvn -f "${path.join(repoRoot, 'pom.xml')}" org.springframework.boot:spring-boot-maven-plugin:run -Dspring-boot.run.profiles=${springProfiles} -Dspring-boot.run.jvmArguments=-Dfile.encoding=UTF-8`,
    cwd: repoRoot,
    env: webServerEnv,
    url: 'http://localhost:18100/',
    reuseExistingServer: !process.env.CI,
    timeout: 120_000,
    stdout: 'pipe',
    stderr: 'pipe',
  },
});
