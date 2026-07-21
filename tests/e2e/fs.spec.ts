import { test, expect } from '@playwright/test';
import * as path from 'path';
import { cleanupDir, makeTempDir, makeTempDirIn, writeTempFile } from './_tmp';

test.describe('工作空间文件系统', () => {
  test('工作空间弹窗: 进入测试目录 → 上传 → 下载 → 删除普通文本文件', async ({ page, request }) => {
    const marker = 'E2E-FS-' + Date.now();
    const fileName = marker + '.txt';
    const content = marker + ' workspace file content';
    let targetDir: string | undefined;
    let sourceDir: string | undefined;

    try {
      targetDir = await makeTempDirIn(path.resolve(process.cwd(), '..'), 'agent-web-e2e-fs-target');
      sourceDir = await makeTempDir('agent-web-e2e-fs-source');
      const sourceFile = await writeTempFile(sourceDir, fileName, content);
      const targetName = path.basename(targetDir);

      await page.goto('/');
      await expect(page.locator('textarea[placeholder*="输入你的问题"]')).toBeEnabled({ timeout: 10_000 });

      await page.locator('.workspace-selector').click();
      const dialog = page.locator('[data-test="workspace-dialog"]');
      await expect(dialog).toBeVisible({ timeout: 5_000 });

      const targetDirRow = dialog.locator('[data-test="fs-row"]').filter({ hasText: targetName });
      await expect(targetDirRow, 'workspace root should list the test-owned target directory').toHaveCount(1);
      await targetDirRow.scrollIntoViewIfNeeded();
      await targetDirRow.click();
      await expect(dialog.locator('input').nth(1)).toHaveValue(targetDir);

      await dialog.locator('[data-test="fs-upload"] input[type="file"]').setInputFiles(sourceFile);
      await expect(page.locator('.el-message--success').filter({ hasText: '上传成功' }))
        .toBeVisible({ timeout: 10_000 });

      const row = dialog.locator('[data-test="fs-row"]').filter({ hasText: fileName });
      await expect(row).toBeVisible({ timeout: 10_000 });

      const selectedPath = await dialog.locator('input').nth(1).inputValue();
      const download = await request.get('/api/fs/download?path=' + encodeURIComponent(selectedPath + '/' + fileName));
      expect(download.ok(), 'download API should return uploaded file').toBeTruthy();
      expect(await download.text()).toBe(content);

      await row.locator('.fs-actions .el-icon').click();
      await page.getByRole('menuitem', { name: '删除' }).click();
      await page.getByRole('button', { name: '删除' }).click();
      await expect(page.locator('.el-message--success').filter({ hasText: '已删除' }))
        .toBeVisible({ timeout: 5_000 });
      await expect(row).toHaveCount(0, { timeout: 5_000 });
    } finally {
      await cleanupDir(targetDir);
      await cleanupDir(sourceDir);
    }
  });
});
