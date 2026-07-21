import { test, expect, Page } from '@playwright/test';
import { gotoAdminMenu } from './_admin';

/**
 * 对话记录菜单 admin UI 围栏(MPA 拆分前置基线)。
 *
 * 链路:主控台 / 发一条带唯一 marker 的消息 seed 一个会话(Codex stub 立即回诊断结论)→
 * /admin 登录 → 对话记录菜单 → 按 marker 搜索 → 点「查看」→ 详情抽屉渲染该消息。
 * 覆盖列表加载 / 搜索 / 详情抽屉三处接线,MPA 拆分后 conversations.html 必须维持。
 *
 * 依赖 application-e2e.yml: agent.cli.codex.args 指向 codex JSON stub。
 */

const CHAT_INPUT = 'textarea[placeholder*="输入你的问题"]';

/** 主控台发一条消息,等 assistant 气泡回稳定诊断结论,作为一条可被 admin 看到的会话。 */
async function seedConversation(page: Page, marker: string): Promise<void> {
  await page.goto('/');
  const input = page.locator(CHAT_INPUT);
  await expect(input).toBeEnabled({ timeout: 10_000 });
  await input.fill(marker + ' 你好');
  await page.getByRole('button', { name: '发送' }).click();
  await expect(page.locator('.message-agent .text-segment').last())
    .toContainText('诊断结论', { timeout: 15_000 });
  await expect(page.getByRole('button', { name: '停止' })).toHaveCount(0, { timeout: 10_000 });
}

test('admin 对话记录: seed 会话 → 搜索 → 详情抽屉渲染消息', async ({ page }) => {
  const marker = 'E2E-CONV-' + Date.now();
  await seedConversation(page, marker);

  await gotoAdminMenu(page, '对话记录');

  // 按 marker 搜索标题。echo stub 不写 s.title,标题回退到首条消息;后端 keyword 已对
  // COALESCE 兜底标题做匹配(SqliteConversationQueryService 修复),故按 marker 能搜到本次会话。
  await page.getByPlaceholder('搜索标题 / 用户名 / 工号').fill(marker);
  await page.getByRole('button', { name: '搜索' }).click();

  // marker 唯一 → 命中且仅命中本次 seed 行
  const row = page.locator('.el-table__row').filter({ hasText: marker });
  await expect(row.first()).toBeVisible({ timeout: 10_000 });

  // 搜索收敛到唯一结果 → 只有一个「查看」(固定列可能重复,取 first)
  await page.getByRole('button', { name: '查看' }).first().click();

  // 详情抽屉:drawer-meta 含「会话 ID」+ 用户消息正文含 marker
  const drawer = page.locator('.el-drawer').filter({ hasText: '会话 ID' });
  await expect(drawer).toBeVisible({ timeout: 5_000 });
  await expect(drawer.getByText(marker).first()).toBeVisible({ timeout: 5_000 });
});
