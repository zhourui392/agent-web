import { test, expect } from '@playwright/test';
import * as path from 'path';
import { cleanupDir, makeTempDir, makeTempDirIn, writeTempFile } from './_tmp';

test.describe('工作空间文件系统', () => {
  test('工作空间切换: 浏览候选目录不清空对话，确认后才切换', async ({ page }) => {
    const marker = 'E2E-WORKSPACE-CONFIRM-' + Date.now();
    let targetDir: string | undefined;

    try {
      targetDir = await makeTempDirIn(path.resolve(process.cwd(), '..'), 'agent-web-e2e-workspace-confirm');
      const targetName = path.basename(targetDir);

      await page.goto('/');
      const input = page.locator('textarea[placeholder*="输入你的问题"]');
      await expect(input).toBeEnabled({ timeout: 10_000 });
      await input.fill(marker);
      await page.getByRole('button', { name: '发送' }).click();
      await expect(page.locator('.message-agent .text-segment').last())
        .toContainText('诊断结论', { timeout: 15_000 });

      const committedWorkspace = (await page.locator('.workspace-selector .path').textContent())!.trim();
      await page.locator('.workspace-selector').click();
      const dialog = page.locator('[data-test="workspace-dialog"]');
      const targetDirRow = dialog.locator('[data-test="fs-row"]').filter({ hasText: targetName });
      await expect(targetDirRow).toHaveCount(1);
      await targetDirRow.click();

      const candidatePath = dialog.locator('[data-test="workspace-candidate-path"]');
      await expect(candidatePath).toHaveValue(targetDir);
      await expect(page.locator('.workspace-selector .path')).toHaveText(committedWorkspace);
      await expect(page.locator('.message-user-text').filter({ hasText: marker })).toBeVisible();

      await dialog.getByRole('button', { name: '确认' }).click();

      await expect(dialog).toBeHidden();
      await expect(page.locator('.workspace-selector .path')).toHaveText(targetDir);
      await expect(page.locator('.message-user-text').filter({ hasText: marker })).toHaveCount(0);
    } finally {
      await cleanupDir(targetDir);
    }
  });

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
      const candidatePath = dialog.locator('[data-test="workspace-candidate-path"]');
      await expect(candidatePath).toHaveValue(targetDir);

      await dialog.locator('[data-test="fs-upload"] input[type="file"]').setInputFiles(sourceFile);
      await expect(page.locator('.el-message--success').filter({ hasText: '上传成功' }))
        .toBeVisible({ timeout: 10_000 });

      const row = dialog.locator('[data-test="fs-row"]').filter({ hasText: fileName });
      await expect(row).toBeVisible({ timeout: 10_000 });

      const selectedPath = await candidatePath.inputValue();
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

  test('md 预览: 上传 .md → 点 dropdown preview → previewVisible 渲染', async ({ page }) => {
    const marker = 'E2E-FS-PREVIEW-' + Date.now();
    const fileName = marker + '.md';
    const content = '# ' + marker + '\n\n**bold** content';
    let targetDir: string | undefined;
    let sourceDir: string | undefined;

    try {
      targetDir = await makeTempDirIn(path.resolve(process.cwd(), '..'), 'agent-web-e2e-fs-preview-target');
      sourceDir = await makeTempDir('agent-web-e2e-fs-preview-source');
      const sourceFile = await writeTempFile(sourceDir, fileName, content);
      const targetName = path.basename(targetDir);

      await page.goto('/');
      await expect(page.locator('textarea[placeholder*="输入你的问题"]')).toBeEnabled({ timeout: 10_000 });

      await page.locator('.workspace-selector').click();
      const dialog = page.locator('[data-test="workspace-dialog"]');
      await expect(dialog).toBeVisible({ timeout: 5_000 });

      const targetDirRow = dialog.locator('[data-test="fs-row"]').filter({ hasText: targetName });
      await targetDirRow.scrollIntoViewIfNeeded();
      await targetDirRow.click();

      await dialog.locator('[data-test="fs-upload"] input[type="file"]').setInputFiles(sourceFile);
      await expect(page.locator('.el-message--success').filter({ hasText: '上传成功' }))
        .toBeVisible({ timeout: 10_000 });

      const row = dialog.locator('[data-test="fs-row"]').filter({ hasText: fileName });
      await expect(row).toBeVisible({ timeout: 10_000 });

      // 点 dropdown preview(handleFileCommand('preview') 分支)
      await row.locator('.fs-actions .el-icon').click();
      await page.getByRole('menuitem', { name: '预览' }).click();

      // previewVisible 渲染 + previewHtml 含 markdown 内容
      const preview = dialog.locator('[data-test="md-preview"]');
      await expect(preview).toBeVisible({ timeout: 10_000 });
      await expect(preview.locator('.md-preview-body')).toContainText(marker, { timeout: 10_000 });
    } finally {
      await cleanupDir(targetDir);
      await cleanupDir(sourceDir);
    }
  });

  test('download UI: 点 dropdown download → 触发 window.open', async ({ page }) => {
    const marker = 'E2E-FS-DL-' + Date.now();
    const fileName = marker + '.txt';
    const content = marker + ' download content';
    let targetDir: string | undefined;
    let sourceDir: string | undefined;

    try {
      targetDir = await makeTempDirIn(path.resolve(process.cwd(), '..'), 'agent-web-e2e-fs-dl-target');
      sourceDir = await makeTempDir('agent-web-e2e-fs-dl-source');
      const sourceFile = await writeTempFile(sourceDir, fileName, content);
      const targetName = path.basename(targetDir);

      await page.goto('/');
      await expect(page.locator('textarea[placeholder*="输入你的问题"]')).toBeEnabled({ timeout: 10_000 });

      await page.locator('.workspace-selector').click();
      const dialog = page.locator('[data-test="workspace-dialog"]');
      await expect(dialog).toBeVisible({ timeout: 5_000 });

      const targetDirRow = dialog.locator('[data-test="fs-row"]').filter({ hasText: targetName });
      await targetDirRow.scrollIntoViewIfNeeded();
      await targetDirRow.click();

      await dialog.locator('[data-test="fs-upload"] input[type="file"]').setInputFiles(sourceFile);
      await expect(page.locator('.el-message--success').filter({ hasText: '上传成功' }))
        .toBeVisible({ timeout: 10_000 });

      const row = dialog.locator('[data-test="fs-row"]').filter({ hasText: fileName });
      await expect(row).toBeVisible({ timeout: 10_000 });

      // 点 dropdown download(handleFileCommand('download') 分支,window.open)
      const popupPromise = page.waitForEvent('popup');
      await row.locator('.fs-actions .el-icon').click();
      await page.getByRole('menuitem', { name: '下载' }).click();
      const popup = await popupPromise;
      // popup 打开即验证 window.open 触发(不验 URL:/api/fs/download 在 popup 加载/浏览器处理中,
      // URL 可能是 about:blank 或加载中,toHaveURL 会 timeout)
      expect(popup).toBeTruthy();
    } finally {
      await cleanupDir(targetDir);
      await cleanupDir(sourceDir);
    }
  });
});
