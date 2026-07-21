import { test, expect } from '@playwright/test';
import { gotoAdminMenu } from './_admin';
import { gotoChatReady } from './_chat';

test('用户建议闭环: 主控台提交 → admin 回复 → 主控台状态可见', async ({ page }) => {
  const marker = 'E2E-SUG-' + Date.now();
  const title = marker + ' 建议标题';
  const content = marker + ' 建议内容';
  const reply = marker + ' 管理员回复';

  await gotoChatReady(page);
  await page.getByRole('button', { name: '建议/反馈' }).click();
  const userDialog = page.locator('.el-dialog').filter({ hasText: '建议 / 反馈' });
  await expect(userDialog).toBeVisible({ timeout: 5_000 });
  await userDialog.getByPlaceholder('一句话概括你的建议').fill(title);
  await userDialog.getByPlaceholder('请描述遇到的问题、期望的改进或使用建议').fill(content);
  await userDialog.getByRole('button', { name: '提交' }).click();
  await expect(userDialog.locator('[data-test="suggestion-card"]').filter({ hasText: marker }))
    .toBeVisible({ timeout: 10_000 });
  await expect(userDialog.getByText(/待处理|PENDING/)).toBeVisible();
  await page.keyboard.press('Escape');

  await gotoAdminMenu(page, '用户建议');
  await page.getByPlaceholder('搜索标题、内容、用户').fill(marker);
  await page.getByRole('button', { name: '搜索' }).click();
  const row = page.locator('.el-table__row').filter({ hasText: marker }).first();
  await expect(row).toBeVisible({ timeout: 10_000 });
  await row.getByRole('button', { name: '处理' }).click();

  const drawer = page.locator('.el-drawer').filter({ hasText: '处理用户建议' });
  await expect(drawer).toBeVisible({ timeout: 5_000 });
  await drawer.locator('.el-select').click();
  await page.getByRole('option', { name: '已回复' }).click();
  await drawer.getByPlaceholder('填写给用户看的处理回复').fill(reply);
  await drawer.getByRole('button', { name: '保存' }).click();
  await expect(page.locator('.el-message--success').filter({ hasText: '已保存' }))
    .toBeVisible({ timeout: 5_000 });

  await gotoChatReady(page);
  await page.getByRole('button', { name: '建议/反馈' }).click();
  await page.getByRole('tab', { name: '处理状态' }).click();
  await page.getByRole('button', { name: '刷新', exact: true }).click();
  const updated = page.locator('[data-test="suggestion-card"]').filter({ hasText: marker });
  await expect(updated).toBeVisible({ timeout: 10_000 });
  await expect(updated).toContainText(reply);
  await expect(updated).toContainText(/已回复|REPLIED/);
});
