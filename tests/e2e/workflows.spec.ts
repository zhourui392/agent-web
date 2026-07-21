import { test, expect } from '@playwright/test';
import { gotoAdminMenu } from './_admin';
import { cleanupDir, makeTempDir } from './_tmp';

test.describe('工作流 admin UI', () => {
  let tmpDir: string;

  test.beforeAll(async () => {
    tmpDir = await makeTempDir('e2e-workflow');
  });

  test.afterAll(async () => {
    await cleanupDir(tmpDir);
  });

  test('新建 → 编辑 → 运行 → 执行详情 → 删除', async ({ page }) => {
    const marker = 'E2E-WF-' + Date.now();
    const name = marker + ' workflow';
    const editedName = marker + ' workflow edited';

    await gotoAdminMenu(page, '工作流');
    await page.getByRole('button', { name: '新建' }).click();
    const editDialog = page.locator('.el-dialog').filter({ hasText: '新建工作流' });
    await expect(editDialog).toBeVisible({ timeout: 5_000 });
    await editDialog.locator('.el-form-item').filter({ hasText: '名称' }).locator('input').fill(name);
    await editDialog.locator('.el-form-item').filter({ hasText: '描述' }).locator('textarea').fill(marker + ' description');
    await editDialog.locator('.el-form-item').filter({ hasText: '工作目录' }).locator('input').fill(tmpDir);
    await editDialog.locator('.el-form-item').filter({ hasText: '步骤名' }).locator('input').fill('review');
    await editDialog.locator('.el-form-item').filter({ hasText: 'Prompt 模板' }).locator('textarea').fill(marker + ' {{branch}}');
    await editDialog.getByRole('button', { name: '保存' }).click();
    await expect(page.locator('.el-message--success').filter({ hasText: '已保存' }).last())
      .toBeVisible({ timeout: 5_000 });

    let row = page.locator('.el-table__row').filter({ hasText: name }).first();
    await expect(row).toBeVisible({ timeout: 10_000 });

    await row.getByRole('button', { name: '编辑' }).click();
    const updateDialog = page.locator('.el-dialog').filter({ hasText: '编辑工作流' });
    await expect(updateDialog).toBeVisible({ timeout: 5_000 });
    await updateDialog.locator('.el-form-item').filter({ hasText: '名称' }).locator('input').fill(editedName);
    await updateDialog.locator('.el-form-item').filter({ hasText: 'Prompt 模板' }).locator('textarea').fill(marker + ' edited {{branch}}');
    await updateDialog.getByRole('button', { name: '保存' }).click();
    await expect(page.locator('.el-message--success').filter({ hasText: '已保存' }).last())
      .toBeVisible({ timeout: 5_000 });

    row = page.locator('.el-table__row').filter({ hasText: editedName }).first();
    await expect(row).toBeVisible({ timeout: 10_000 });
    await row.getByRole('button', { name: '运行' }).click();
    const runDialog = page.locator('.el-dialog').filter({ hasText: '运行工作流' });
    await expect(runDialog).toBeVisible({ timeout: 5_000 });
    await runDialog.locator('textarea').fill('{"branch":"main"}');
    await runDialog.getByRole('button', { name: '运行' }).click();

    const detailDrawer = page.locator('.el-drawer').filter({ hasText: '执行详情' });
    await expect(detailDrawer).toBeVisible({ timeout: 15_000 });
    await expect(detailDrawer.getByText(/状态：(RUNNING|SUCCEEDED|FAILED)/)).toBeVisible({ timeout: 10_000 });
    await expect(async () => {
      const executions = await page.request.get('/api/admin-workflow-executions?page=1&size=20');
      expect(executions.ok()).toBeTruthy();
      const body = await executions.json();
      const found = body.find((e: { id: string; status: string }) => e.status === 'SUCCEEDED');
      expect(found).toBeTruthy();
    }).toPass({ timeout: 20_000, intervals: [500, 1000] });

    await detailDrawer.getByRole('button', { name: 'Close' }).click().catch(async () => {
      await page.keyboard.press('Escape');
    });
    await row.getByRole('button', { name: '删除' }).click();
    await page.locator('.el-message-box').getByRole('button', { name: 'OK' }).click();
    await expect(page.locator('.el-message--success').filter({ hasText: '已删除' }).last())
      .toBeVisible({ timeout: 5_000 });
    await expect(page.locator('.el-table__row').filter({ hasText: editedName })).toHaveCount(0, { timeout: 10_000 });
  });
});
