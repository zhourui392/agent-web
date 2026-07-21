import { defineConfig, devices } from '@playwright/test';
import path from 'path';

const repoRoot = path.resolve(__dirname, '..');
const springProfiles = process.platform === 'win32' ? 'e2e,qa-prefix' : 'e2e,e2e-linux,qa-prefix';
const jdk21Home = process.env.JAVA_HOME && !process.env.JAVA_HOME.includes('jdk8')
  ? process.env.JAVA_HOME
  : '/usr/local/jdk-21';
const webServerEnv = process.platform === 'win32'
  ? { ...process.env, GIT_CRED_ENC_KEY: process.env.GIT_CRED_ENC_KEY || 'MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=' } as Record<string, string>
  : {
      ...process.env,
      JAVA_HOME: jdk21Home,
      PATH: `${jdk21Home}/bin:${process.env.PATH || ''}`,
      GIT_CRED_ENC_KEY: process.env.GIT_CRED_ENC_KEY || 'MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=',
    } as Record<string, string>;

export default defineConfig({
  testDir: './e2e',
  testMatch: ['qa-prefix.spec.ts'],
  fullyParallel: false,
  workers: 1,
  reporter: [['list'], ['html', { open: 'never', outputFolder: 'playwright-report-qa-prefix' }]],
  use: {
    baseURL: 'http://localhost:18100/qa',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'retain-on-failure',
    actionTimeout: 10_000,
    navigationTimeout: 15_000,
  },
  projects: [
    { name: 'chromium', use: { ...devices['Desktop Chrome'], viewport: { width: 1600, height: 900 } } },
  ],
  webServer: {
    command: `mvn -f "${path.join(repoRoot, 'pom.xml')}" org.springframework.boot:spring-boot-maven-plugin:run -Dspring-boot.run.profiles=${springProfiles} -Dspring-boot.run.jvmArguments=-Dfile.encoding=UTF-8`,
    cwd: repoRoot,
    env: webServerEnv,
    url: 'http://localhost:18100/qa/',
    reuseExistingServer: !process.env.CI,
    timeout: 120_000,
    stdout: 'pipe',
    stderr: 'pipe',
  },
});
