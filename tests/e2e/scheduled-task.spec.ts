import { test, expect } from '@playwright/test';
import * as path from 'path';

// 仓库根 (tests/e2e/ 的祖父目录), 对应 e2e profile 里 agent.fs.roots 的 ${user.dir}
const REPO_ROOT = path.resolve(__dirname, '../..');

/**
 * ScheduledTask UI 主链路 E2E:
 * 1. 打开 "定时任务" 管理对话框
 * 2. 点 "新建" → 表单填名称/cron/prompt/workingDir → 保存
 * 3. 列表里出现该任务 (启用 tag)
 * 4. 点 toggle (停用) → tag 改成"停用"
 * 5. 点 立即执行 → 成功 toast "任务已触发"
 * 6. 点 删除 → 模态确认 "删除" → 任务消失
 *
 * 用时间戳唯一命名任务, 避免 SQLite 残留干扰; 跑完会删除以保留 db 干净。
 */

const RUN_MARKER = 'E2E-TASK-' + Date.now();

test('ScheduledTask: 新建 → toggle → 立即执行 → 删除', async ({ page }) => {
  await page.goto('/');
  await expect(page.locator('textarea[placeholder*="输入你的问题"]'))
    .toBeEnabled({ timeout: 10_000 });

  // ===== 1. 打开 定时任务管理 弹窗 =====
  await page.getByRole('button', { name: /定时任务/ }).click();
  const manager = page.locator('.el-dialog', { hasText: '定时任务管理' });
  await expect(manager).toBeVisible({ timeout: 5_000 });

  // ===== 2. 新建任务 =====
  await manager.getByRole('button', { name: /新建/ }).click();
  const editor = page.locator('.el-dialog', { hasText: '新建定时任务' });
  await expect(editor).toBeVisible({ timeout: 5_000 });

  // 表单 4 字段, 按 label 找输入框
  await editor.locator('input').nth(0).fill(RUN_MARKER);          // 任务名称
  await editor.locator('input').nth(1).fill('0 0 9 * * ?');       // Cron (每天9点预设)
  await editor.locator('textarea').fill('hello scheduled');       // Prompt
  await editor.locator('input').nth(2).fill(REPO_ROOT);  // workingDir (e2e profile fs.roots 含 user.dir, 跨平台)

  await editor.getByRole('button', { name: /^保存$/ }).click();
  // 保存后编辑弹窗关闭, 列表刷新
  await expect(editor).not.toBeVisible({ timeout: 5_000 });

  // ===== 3. 列表里能找到新建的任务, tag = "启用" =====
  // 行 v-for 容器的内联 style 含 border-bottom, 用这个锁定行级 div,
  // 否则 manager.locator('div').filter(hasText: ...) 会优先命中最外层对话框 div
  const row = manager.locator('div[style*="border-bottom"]').filter({ hasText: RUN_MARKER });
  await expect(row).toBeVisible({ timeout: 5_000 });
  await expect(row.locator('.el-tag').filter({ hasText: '启用' })).toBeVisible();

  // 行内按钮顺序: 立即执行(0) / toggle(1) / 编辑(2) / 删除(3)
  const rowButtons = row.locator('button');

  // ===== 4. 点 toggle 切到停用 =====
  await rowButtons.nth(1).click();
  await expect(row.locator('.el-tag').filter({ hasText: '停用' })).toBeVisible({ timeout: 5_000 });

  // ===== 5. 立即执行 =====
  await rowButtons.nth(0).click();
  await expect(page.locator('.el-message--success').filter({ hasText: /任务已触发/ }))
    .toBeVisible({ timeout: 5_000 });

  // ===== 6. 删除 =====
  await rowButtons.nth(3).click();
  // 模态确认: 点 "删除"
  const confirm = page.locator('.el-message-box', { hasText: '确定删除' });
  await expect(confirm).toBeVisible({ timeout: 3_000 });
  await confirm.getByRole('button', { name: /^删除$/ }).click();

  // 行消失 (用 border-bottom 行级 div 反查)
  await expect(manager.locator('div[style*="border-bottom"]').filter({ hasText: RUN_MARKER }))
    .toHaveCount(0, { timeout: 5_000 });
});
