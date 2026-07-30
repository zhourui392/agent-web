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
  VITE_API_PROXY_TARGET: 'http://localhost:18099',
} as Record<string, string>;
const userStorageState = path.join(__dirname, '.auth', 'user.json');

export default defineConfig({
  testDir: './e2e',
  fullyParallel: false,         // E2E 共享 Spring Boot 实例 + SQLite,串行更稳
  forbidOnly: !!process.env.CI,
  retries: 0,                   // Phase 0: 不重试,暴露稳定性问题
  workers: 1,
  reporter: [['list'], ['html', { open: 'never', outputFolder: 'playwright-report' }]],
  globalSetup: './e2e/global-setup.ts',
  // harness.spec.ts 测 harness 开启态,归 playwright.harness.config.ts(18100 + e2e-harness profile);
  // 默认 config(18099, agent.harness.enabled: false) 下跑会因 harness API 不可用而全失败,故排除。
  testIgnore: ['harness.spec.ts'],

  use: {
    baseURL: 'http://localhost:5174',
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

  // 自动启停 vite preview (前端) + Spring Boot (后端 API)
  // 前端分离后 Spring Boot 不再提供静态文件，vite preview 提供 frontend/dist/ 并代理 /api 到后端。
  webServer: {
    command: `bash ${path.join(repoRoot, 'tests', 'scripts', 'e2e-start.sh')}`,
    cwd: repoRoot,
    env: { ...webServerEnv, SPRING_PROFILES: springProfiles },
    url: 'http://localhost:5174/',
    reuseExistingServer: !process.env.CI,
    timeout: 120_000,
    stdout: 'pipe',
    stderr: 'pipe',
  },
});
