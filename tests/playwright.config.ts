import { defineConfig, devices } from '@playwright/test';
import { mkdirSync } from 'fs';
import path from 'path';

// 仓库根目录 (tests/ 的父目录)
const repoRoot = path.resolve(__dirname, '..');
mkdirSync(path.join(repoRoot, 'data'), { recursive: true });

// e2e profile 默认是 Windows 配置 (cmd /c echo / .cmd stub),
// Linux/Mac 必须叠加 e2e-linux 覆盖 cli.{claude,codex}.exec/args.
// 注: Node 里 'win32' 是所有 Windows (含 x64) 的统一标识, 没有 'win64'; darwin/linux 走同一分支
const springProfiles = process.platform === 'win32' ? 'e2e' : 'e2e,e2e-linux';
// JAVA_HOME 兜底: Spring Boot 3.3 需 JDK21+, 本机默认 JAVA_HOME 可能指向 8/17(win32 曾因此
// UnsupportedClassVersionError)。已指向 21+ 版本号则沿用, 否则回落各平台默认安装路径
const jdk21Home = process.platform === 'win32'
  ? (process.env.JAVA_HOME && /jdk-?2[1-9]/i.test(process.env.JAVA_HOME)
      ? process.env.JAVA_HOME
      : 'C:\\Program Files\\Java\\jdk-21.0.9')
  : (process.env.JAVA_HOME && !process.env.JAVA_HOME.includes('jdk8')
      ? process.env.JAVA_HOME
      : '/usr/local/jdk-21');
const pathSeparator = process.platform === 'win32' ? ';' : ':';
const webServerEnv = {
  ...process.env,
  JAVA_HOME: jdk21Home,
  PATH: `${path.join(jdk21Home, 'bin')}${pathSeparator}${process.env.PATH || ''}`,
  GIT_CRED_ENC_KEY: process.env.GIT_CRED_ENC_KEY || 'MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=',
} as Record<string, string>;
const userStorageState = path.join(__dirname, '.auth', 'user.json');

export default defineConfig({
  testDir: './e2e',
  testIgnore: ['qa-prefix.spec.ts'],
  fullyParallel: false,         // E2E 共享 Spring Boot 实例 + SQLite,串行更稳
  forbidOnly: !!process.env.CI,
  retries: 0,                   // Phase 0: 不重试,暴露稳定性问题
  workers: 1,
  reporter: [['list'], ['html', { open: 'never', outputFolder: 'playwright-report' }]],
  globalSetup: './e2e/global-setup.ts',

  use: {
    baseURL: 'http://localhost:18099',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'retain-on-failure',
    actionTimeout: 10_000,
    navigationTimeout: 15_000,
    storageState: userStorageState,
  },

  projects: [
    // 视口宽度需 >1500px: app.css 在 max-width:1500px 下隐藏顶栏按钮文字(.hidden-mobile),
    // 否则诊断历史/定时任务等按钮无可访问名, getByRole(name) 定位不到。
    { name: 'chromium', use: { ...devices['Desktop Chrome'], viewport: { width: 1600, height: 900 } } },
  ],

  // 自动启停 Spring Boot (e2e profile)
  // 用显式 plugin goal 避免 prefix lookup 在仓库根之外失败
  webServer: {
    command: `mvn -f "${path.join(repoRoot, 'pom.xml')}" org.springframework.boot:spring-boot-maven-plugin:run -Dspring-boot.run.profiles=${springProfiles} -Dspring-boot.run.jvmArguments=-Dfile.encoding=UTF-8`,
    cwd: repoRoot,
    env: webServerEnv,
    url: 'http://localhost:18099/',
    reuseExistingServer: !process.env.CI,
    timeout: 120_000,
    stdout: 'pipe',
    stderr: 'pipe',
  },
});
