import { test, expect } from '@playwright/test';

/**
 * app.js 主页(/) auth 围栏。
 *
 * admin-auth.spec 覆盖 /admin(admin.js shell),auth-api.spec 只验后端 API 契约,
 * chat.spec 全靠 globalSetup 预登录从不测未登录/登出/401。本 spec 补 app.js 主页 auth 路径:
 * 1. 未登录访问主页 → init() 调 /api/auth/status 拿 loginUrl 跳转
 * 2. 登录态点顶栏"登出" → doLogout POST /api/auth/logout 跳 loginUrl
 * 3. 任意 API 返回 401+loginUrl → installAuthInterceptor IIFE 拦截跳转
 */

// 显式空 storageState: Playwright newContext 在 use.storageState 存在时会继承 globalSetup 的
// 登录 cookie,导致主页直接进入已登录态。本 spec 要测未登录跳转,必须清空 cookies/origins。
function newUnauthedContext(browser, baseURL) {
  return browser.newContext({ baseURL, storageState: { cookies: [], origins: [] } });
}

test('未登录访问主页 → init() 跳 /login.html', async ({ browser, baseURL }) => {
  const context = await newUnauthedContext(browser, baseURL);
  const page = await context.newPage();
  await page.goto('/');
  // init() 调 /api/auth/status,未登录返回 authenticated=false + loginUrl,跳 loginUrl
  await expect(page).toHaveURL(/\/login\.html/, { timeout: 10_000 });
  await context.close();
});

test('主页登出按钮: 登录态点击 → 跳 /login.html', async ({ browser, baseURL }) => {
  // 登出会销毁服务端 session。若用 page fixture(继承 globalSetup 的 user.json 登录态),
  // 登出后 user.json 对应的服务端 session 被销毁,后续所有 fs/chat spec 共享该 cookie 会全 401。
  // 改用独立 context 手动登录,登出只销毁独立 session,不污染共享 user.json。
  const context = await browser.newContext({ baseURL });
  const page = await context.newPage();
  const loginResp = await context.request.post('/api/auth/login', {
    data: { username: 'admin', password: process.env.AGENT_E2E_ADMIN_PASSWORD! },
  });
  expect(loginResp.ok(), '独立登录应成功').toBeTruthy();
  await page.goto('/');
  // 顶栏登出按钮 v-if="authEnabled",init() 后 authEnabled=true
  const logoutBtn = page.getByRole('button', { name: '登出' });
  await expect(logoutBtn).toBeVisible({ timeout: 10_000 });
  await logoutBtn.click();
  // doLogout POST /api/auth/logout,跳返回的 loginUrl(/login.html)
  await expect(page).toHaveURL(/\/login\.html/, { timeout: 10_000 });
  await context.close();
});

test('401 拦截: API 返回 401+loginUrl → 跳 /login.html', async ({ browser, baseURL }) => {
  // 登录态(newContext 继承 use.storageState)+ mock /api/chat/agent-default 返回 401
  // init() auth 部分先过(/api/auth/status 200 authenticated=true),随后调 agent-default 触发 401 IIFE
  const context = await browser.newContext({ baseURL });
  const page = await context.newPage();
  await page.route('**/api/chat/agent-default', (r) =>
    r.fulfill({
      status: 401,
      contentType: 'application/json',
      body: JSON.stringify({ loginUrl: '/login.html?redirect=/' }),
    }),
  );
  await page.goto('/');
  await expect(page).toHaveURL(/\/login\.html/, { timeout: 10_000 });
  await context.close();
});