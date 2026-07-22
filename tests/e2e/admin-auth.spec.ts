import { test, expect } from '@playwright/test';

test('admin 登录壳: 数据库账户登录 → ADMIN 进大盘 → 退出回登录态', async ({ browser, baseURL }) => {
  const context = await browser.newContext({ baseURL });
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
