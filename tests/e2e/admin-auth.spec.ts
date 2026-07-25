import { test, expect } from '@playwright/test';

test('admin 登录壳: 数据库账户登录 → ADMIN 进大盘 → 退出回登录态', async ({ browser, baseURL }) => {
  // 显式空 storageState: Playwright newContext 在 use.storageState 存在时会继承 globalSetup 的
  // 登录 cookie,导致页面直接进入已登录态,"前往登录"按钮不出现。本 spec 要测未登录 → 登录壳流程,
  // 必须从未登录态开始,故清空 cookies/origins 覆盖继承。
  const context = await browser.newContext({ baseURL, storageState: { cookies: [], origins: [] } });
  const page = await context.newPage();

  await page.goto('/admin');
  await page.getByRole('button', { name: '前往登录' }).click();
  await expect(page.getByPlaceholder('请输入用户名')).toBeVisible({ timeout: 10_000 });

  await page.getByPlaceholder('请输入用户名').fill('admin');
  await page.getByPlaceholder('请输入密码').fill('wrong-password');
  await page.getByRole('button', { name: '登录' }).click();
  await expect(page.getByText('用户名或密码错误')).toBeVisible({ timeout: 5_000 });

  await page.getByPlaceholder('请输入密码').fill(process.env.AGENT_E2E_ADMIN_PASSWORD || '');
  await page.getByRole('button', { name: '登录' }).click();
  await expect(page.getByRole('menuitem', { name: '大盘' })).toBeVisible({ timeout: 10_000 });

  await page.getByRole('button', { name: '退出' }).click();
  await expect(page.getByPlaceholder('请输入用户名')).toBeVisible({ timeout: 5_000 });

  await context.close();
});
