import { test, expect } from '@playwright/test';
import { gotoAdminMenu } from './_admin';
import { cleanupDir, makeTempDir } from './_tmp';

test.describe('admin 对话页', () => {
  let tmpDir: string;

  test.beforeAll(async () => {
    tmpDir = await makeTempDir('e2e-admin-chat');
  });

  test.afterAll(async () => {
    await cleanupDir(tmpDir);
  });

  test('handoff 会话后 admin/chat 内嵌 chat-panel 可发送消息并渲染 Codex stub 结果', async ({ page }) => {
    const marker = 'E2E-ADMIN-CHAT-' + Date.now();
    const create = await page.request.post('/api/chat/session', {
      data: { workingDir: tmpDir, agentType: 'CODEX', env: null },
    });
    expect(create.ok()).toBeTruthy();
    const session = await create.json();

    await gotoAdminMenu(page, '对话');
    await page.evaluate((handoff) => {
      sessionStorage.setItem('admin.chat.handoff', JSON.stringify(handoff));
    }, {
      sessionId: session.sessionId,
      resumeId: '',
      workingDir: tmpDir,
      agentType: 'CODEX',
    });
    await page.goto('/admin/chat.html');

    const input = page.locator('textarea[placeholder*="输入你的问题"]');
    await expect(input).toBeEnabled({ timeout: 10_000 });
    await input.fill(marker + ' hello');
    await page.getByRole('button', { name: '发送' }).click();
    await expect(page.locator('.message-agent .text-segment').last())
      .toContainText('诊断结论', { timeout: 15_000 });
    await expect(page.getByRole('button', { name: '停止' })).toHaveCount(0, { timeout: 10_000 });
  });
});
