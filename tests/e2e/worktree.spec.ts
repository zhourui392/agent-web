import { test, expect } from '@playwright/test';

test('worktree UI 接线: list/switch/update/remove 的成功与错误提示', async ({ page }) => {
  const branch = 'feature/e2e-' + Date.now();
  const switchedPath = '/tmp/e2e-worktree/' + branch.replace('/', '-');

  await page.route('**/api/worktree/list?**', async (route) => {
    await route.fulfill({
      contentType: 'application/json',
      body: JSON.stringify([
        { branch: 'main', worktreePath: '/tmp/e2e-main' },
        { branch, worktreePath: switchedPath },
      ]),
    });
  });
  await page.route('**/api/worktree/switch', async (route) => {
    await route.fulfill({
      contentType: 'application/json',
      body: JSON.stringify({
        worktreePath: switchedPath,
        repos: [{ name: 'svc-a', created: true, actualBranch: branch }],
      }),
    });
  });
  await page.route('**/api/worktree/update', async (route) => {
    await route.fulfill({ status: 500, contentType: 'text/plain', body: 'mock update failed' });
  });
  await page.route('**/api/worktree/remove?**', async (route) => {
    await route.fulfill({ contentType: 'application/json', body: JSON.stringify({ success: true }) });
  });
  await page.route('**/api/fs/list?**', async (route) => {
    await route.fulfill({ contentType: 'application/json', body: JSON.stringify([]) });
  });

  await page.goto('/');
  await expect(page.locator('textarea[placeholder*="输入你的问题"]')).toBeEnabled({ timeout: 10_000 });
  await page.getByText('测试环境', { exact: true }).click();
  await page.locator('.el-tag').filter({ hasText: '请选择分支' }).click();

  await page.locator('.el-select').filter({ hasText: '选择或输入分支名' }).click();
  const branchOption = page.getByRole('option', { name: branch });
  await expect(branchOption).toBeVisible({ timeout: 5_000 });
  await branchOption.click();
  await page.getByRole('button', { name: '切换' }).click();
  await expect(page.locator('.el-message--success').filter({ hasText: branch }))
    .toBeVisible({ timeout: 5_000 });
  await expect(page.locator('.workspace-selector')).toContainText(switchedPath);

  await page.getByRole('button', { name: '更新' }).click();
  await expect(page.locator('.el-message--error').filter({ hasText: 'mock update failed' }))
    .toBeVisible({ timeout: 5_000 });

  await page.locator('.el-tag').filter({ hasText: branch }).locator('.el-tag__close').click();
  await expect(page.locator('.el-message--success').filter({ hasText: '已清理分支' }))
    .toBeVisible({ timeout: 5_000 });
});
