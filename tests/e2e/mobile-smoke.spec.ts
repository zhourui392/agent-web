import { test, expect } from '@playwright/test';
import { ADMIN_PASSWORD } from './_admin';

test('mobile smoke: 首页可发送消息,admin 可登录大盘', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });

  await page.goto('/');
  const input = page.locator('textarea[placeholder*="输入你的问题"]');
  await expect(input).toBeEnabled({ timeout: 10_000 });
  await input.fill('E2E-MOBILE-' + Date.now());
  await page.getByRole('button', { name: '发送' }).click();
  await expect(page.locator('.message-agent .text-segment').last())
    .toContainText('诊断结论', { timeout: 15_000 });

  await page.goto('/admin');
  await page.getByPlaceholder('请输入管理口令').fill(ADMIN_PASSWORD);
  await page.getByRole('button', { name: '登录' }).click();
  await expect(page.getByRole('menuitem', { name: '大盘' })).toBeVisible({ timeout: 10_000 });
});
