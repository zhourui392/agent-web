import { test, expect, Page, Locator } from '@playwright/test';
import * as fs from 'fs/promises';
import * as os from 'os';
import * as path from 'path';

/**
 * 聊天主链路 + 组件化(ChatPanel)行为对等围栏。
 *
 * 这套 spec 是 ChatPanel 组件化的唯一前端防线(CDN-only 无组件级单测):覆盖发消息/SSE、
 * 停止中断、斜杠命令补全、历史加载+恢复、历史删除、消息回退、清空上下文、附件上传/删除,
 * 以及 RAG 召回默认进入 run 提交 body。组件化前后这套必须持续绿(行为对等证明)。
 *
 * 关键依赖(application-e2e.yml):
 * - agent.cli.codex.args 指向 codex JSON stub → assistant 气泡渲染稳定诊断结论
 * - agent.slash-command.command-dirs=tests/e2e/fixtures/commands → 3 个占位命令(e2e-alpha/beta/gamma)
 *
 * 已知不走 e2e 的边角(见设计 §6.3,代码审查 + 手动覆盖):
 * - 心跳超时:依赖 35 秒定时器,强测反成不稳定围栏；刷新恢复由确定性 route 单独覆盖
 * - 工具块折叠(toolStates):echo / codex stub 都不产 tool_use 段,无法在 chat 链路构造工具块
 * - RAG 召回开关 UI:refinery 关闭时 el-switch 隐藏;此处改为断言 SSE URL 默认带 recall=true 间接守住接线
 */

const INPUT = 'textarea[placeholder*="输入你的问题"]';
const ASSISTANT_STUB_TEXT = '诊断结论';

async function gotoReady(page: Page): Promise<void> {
  await page.goto('/');
  await expect(page.locator(INPUT)).toBeEnabled({ timeout: 10_000 });
}

async function sendAndWait(page: Page, text: string): Promise<void> {
  const input = page.locator(INPUT);
  await expect(input).toBeEnabled({ timeout: 10_000 });
  await input.fill(text);
  await page.getByRole('button', { name: '发送' }).click();
  await expect(page.locator('.message-agent .text-segment').last())
    .toContainText(ASSISTANT_STUB_TEXT, { timeout: 15_000 });
  await expect(page.getByRole('button', { name: '停止' })).toHaveCount(0, { timeout: 10_000 });
}

/** 历史会话标题对普通会话 = 首条 user 消息内容,可用唯一 marker 定位列表项。 */
function historyItem(page: Page, marker: string): Locator {
  return page.locator('.history-item').filter({ hasText: marker });
}

test('chat 主链路: 发送 hello 后 assistant 气泡渲染 Codex stub 结论', async ({ page }) => {
  await gotoReady(page);
  const input = page.locator(INPUT);
  await input.fill('hello');
  const sendBtn = page.getByRole('button', { name: '发送' });
  await expect(sendBtn).toBeEnabled();
  await sendBtn.click();

  await expect(page.locator('.message-user-text').last()).toContainText('hello');
  await expect(page.locator('.message-agent .text-segment').last())
    .toContainText(ASSISTANT_STUB_TEXT, { timeout: 15_000 });
  await expect(page.getByRole('button', { name: '停止' })).toHaveCount(0, { timeout: 10_000 });
});

test('停止: resumable run 发送中点停止 → 请求 run stop;提交默认带 recall=true 和幂等键', async ({ page }) => {
  await gotoReady(page);

  await page.route('**/api/chat/session/*/runs', async route => {
    await route.fulfill({
      status: 202,
      contentType: 'application/json',
      body: JSON.stringify({ runId: 'run-stop-e2e', sessionId: 'session-stop', status: 'PENDING', lastEventSeq: 1, duplicated: false }),
    });
  });
  let release: (() => void) | undefined;
  await page.route('**/api/chat/runs/run-stop-e2e/events*', async (route) => {
    await new Promise<void>((resolve) => { release = resolve; });
    await route.abort().catch(() => { /* 页面关闭后请求可能已取消 */ });
  });
  await page.route('**/api/chat/runs/run-stop-e2e/stop', async route => {
    await route.fulfill({
      status: 202,
      contentType: 'application/json',
      body: JSON.stringify({ runId: 'run-stop-e2e', sessionId: 'session-stop', status: 'CANCEL_REQUESTED' }),
    });
  });

  const submitReqP = page.waitForRequest(/\/api\/chat\/session\/[^/]+\/runs$/);
  await page.locator(INPUT).fill('STOP-' + Date.now());
  await page.getByRole('button', { name: '发送' }).click();

  const req = await submitReqP;
  expect(req.method()).toBe('POST');
  expect(req.postDataJSON(), 'RAG 默认开 → POST body 带 recall=true').toMatchObject({ recall: true });
  expect(req.headers()['idempotency-key']).toBeTruthy();

  const stopBtn = page.getByRole('button', { name: '停止' });
  await expect(stopBtn).toBeVisible({ timeout: 5_000 });
  await stopBtn.click();

  await expect(page.locator('.message-system').filter({ hasText: '已请求停止' }))
    .toBeVisible({ timeout: 5_000 });

  if (release) release();
  await page.unroute('**/api/chat/runs/run-stop-e2e/events*');
});

test('刷新恢复: 回放同一 run 的完整输出且不会重复提交', async ({ page }) => {
  let submitted = false;
  let terminal = false;
  let sessionId = '';
  let submitCount = 0;
  let eventConnections = 0;

  await page.route('**/api/chat/runs/active', async route => {
    const active = submitted && !terminal
      ? [{ runId: 'run-refresh-e2e', sessionId, status: 'RUNNING', agentType: 'CODEX', workingDir: '/workspace', lastEventSeq: 2, startedAt: Date.now(), createdAt: Date.now() }]
      : [];
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(active) });
  });
  await page.route('**/api/chat/session/*/runs', async route => {
    submitCount++;
    sessionId = decodeURIComponent(route.request().url().match(/\/session\/([^/]+)\/runs/)![1]);
    submitted = true;
    await route.fulfill({
      status: 202,
      contentType: 'application/json',
      body: JSON.stringify({ runId: 'run-refresh-e2e', sessionId, status: 'PENDING', lastEventSeq: 1, duplicated: false }),
    });
  });
  await page.route('**/api/chat/session/*/messages', async route => {
    const messages = terminal
      ? [{ id: 1, role: 'user', content: 'REFRESH-RUN' }, { id: 2, role: 'assistant', content: 'first-\nsecond' }]
      : [{ id: 1, role: 'user', content: 'REFRESH-RUN' }];
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(messages) });
  });
  await page.route('**/api/chat/runs/run-refresh-e2e/events*', async route => {
    eventConnections++;
    if (eventConnections === 1) {
      await route.fulfill({
        status: 200,
        contentType: 'text/event-stream',
        body: 'id: 1\nevent: run_status\ndata: {"status":"RUNNING"}\n\nid: 2\nevent: chunk\ndata: first-\n\n',
      });
      return;
    }
    terminal = true;
    await route.fulfill({
      status: 200,
      contentType: 'text/event-stream',
      body: 'id: 1\nevent: run_status\ndata: {"status":"RUNNING"}\n\n'
        + 'id: 2\nevent: chunk\ndata: first-\n\n'
        + 'id: 3\nevent: chunk\ndata: second\n\n'
        + 'id: 4\nevent: terminal\ndata: {"status":"SUCCEEDED","assistantMessageId":2}\n\n',
    });
  });

  await gotoReady(page);
  await page.locator(INPUT).fill('REFRESH-RUN');
  await page.getByRole('button', { name: '发送' }).click();
  await expect(page.locator('.message-agent .text-segment').last()).toContainText('first-', { timeout: 5_000 });

  await page.reload();
  await expect(page.locator(INPUT)).toBeEnabled({ timeout: 10_000 });
  await expect(page.locator('.message-agent .text-segment').last()).toContainText('second', { timeout: 10_000 });
  await expect(page.getByRole('button', { name: '停止' })).toHaveCount(0, { timeout: 5_000 });
  expect(submitCount).toBe(1);
  expect(eventConnections).toBeGreaterThanOrEqual(2);
});

test('斜杠命令: / 弹补全, ↑↓ 改选中, Tab/Enter 填充', async ({ page }) => {
  await gotoReady(page);
  const input = page.locator(INPUT);
  const popup = page.locator('.command-popup');
  const items = popup.locator('.command-item');

  // 命令异步加载,重试输入 / 直到弹窗出现
  await expect(async () => {
    await input.fill('');
    await input.fill('/');
    await expect(popup).toBeVisible({ timeout: 1_000 });
  }).toPass({ timeout: 10_000 });

  await expect(items).toHaveCount(3); // 3 个占位命令
  await expect(items.nth(0)).toHaveClass(/active/);

  // ↓↓ 选中下移(按索引,不依赖命令顺序),↑ 回退
  await page.keyboard.press('ArrowDown');
  await expect(items.nth(1)).toHaveClass(/active/);
  await page.keyboard.press('ArrowDown');
  await expect(items.nth(2)).toHaveClass(/active/);
  await page.keyboard.press('ArrowUp');
  await expect(items.nth(1)).toHaveClass(/active/);

  // Tab 填充当前选中项(读其实际命令名,顺序无关)
  const name1 = (await items.nth(1).locator('.command-name').textContent())!.trim(); // 形如 /e2e-beta
  await page.keyboard.press('Tab');
  await expect(popup).toBeHidden();
  await expect(input).toHaveValue(name1 + ' ');

  // Enter 选中:过滤到唯一命令后回车
  await input.fill('/e2e-gamma');
  await expect(popup).toBeVisible();
  await page.keyboard.press('Enter');
  await expect(input).toHaveValue('/e2e-gamma ');
});

test('历史: 发消息落库 → 重载 → 恢复会话渲染原消息', async ({ page }) => {
  const marker = 'HIST-' + Date.now();
  await gotoReady(page);
  await sendAndWait(page, marker + ' hello');

  // 重载页面,onMounted 重拉历史列表(含刚落库的会话)
  await gotoReady(page);
  const item = historyItem(page, marker);
  await expect(item).toBeVisible({ timeout: 10_000 });

  // 点继续对话(▶)恢复会话
  await item.locator('[title="继续对话"]').click();

  await expect(page.locator('.message-user-text').filter({ hasText: marker }))
    .toBeVisible({ timeout: 10_000 });
  await expect(page.locator('.message-system').filter({ hasText: '已恢复历史会话' }))
    .toBeVisible({ timeout: 5_000 });
});

test('历史: 删除会话 → 列表项消失', async ({ page }) => {
  const marker = 'DEL-' + Date.now();
  await gotoReady(page);
  await sendAndWait(page, marker + ' hello');

  await gotoReady(page);
  const item = historyItem(page, marker);
  await expect(item).toBeVisible({ timeout: 10_000 });

  // 历史项内两个按钮:[继续对话, 删除],删除是最后一个
  await item.locator('button').last().click();
  // ElMessageBox 确认弹窗,唯一带文字"删除"的按钮即确认键
  await page.getByRole('button', { name: '删除' }).click();

  await expect(page.locator('.el-message--success').filter({ hasText: '已删除' }))
    .toBeVisible({ timeout: 5_000 });
  await expect(historyItem(page, marker)).toHaveCount(0, { timeout: 5_000 });
});

test('回退: 从 user 消息重开 → 删除该条 + 回填输入框', async ({ page }) => {
  const marker = 'REWIND-' + Date.now();
  await gotoReady(page);
  await sendAndWait(page, marker + ' hello');

  // 流结束后 reloadMessages 把消息升级为持久化 id,↩ 回退按钮才出现
  const rewindBtn = page.locator('.rewind-btn');
  await expect(rewindBtn).toBeVisible({ timeout: 10_000 });
  await rewindBtn.first().click();

  await page.getByRole('button', { name: '确认回退' }).click();

  // 该 user 消息被删,输入框回填原文
  await expect(page.locator('.message-user-text').filter({ hasText: marker }))
    .toHaveCount(0, { timeout: 5_000 });
  await expect(page.locator(INPUT)).toHaveValue(new RegExp(marker));
});

test('清空上下文: 系统提示已清除', async ({ page }) => {
  await gotoReady(page);
  await sendAndWait(page, 'CTX-' + Date.now() + ' hello');

  await page.getByRole('button', { name: '清除上下文' }).click();

  await expect(page.locator('.message-system').filter({ hasText: '上下文已清除' }))
    .toBeVisible({ timeout: 5_000 });
});

test('附件: 上传 .txt → 卡片渲染 → 点 × 删除', async ({ page }) => {
  // 准备临时文本附件
  const dir = path.join(os.tmpdir(), 'e2e-chat-file-' + Date.now());
  await fs.mkdir(dir, { recursive: true });
  const txtPath = path.join(dir, 'note.txt');
  await fs.writeFile(txtPath, 'hello attachment for e2e fence');

  await gotoReady(page);

  // 附件 input 的 accept 含 .log/.txt 等,避开 image/* 那个
  const fileInput = page.locator('input[type=file][accept*=".log"]');
  await fileInput.setInputFiles(txtPath);

  await expect(page.locator('.el-message--success').filter({ hasText: '附件已上传' }))
    .toBeVisible({ timeout: 10_000 });

  const card = page.locator('.pending-file-card');
  await expect(card).toBeVisible({ timeout: 5_000 });
  await expect(card.locator('.pending-file-name')).toContainText('note.txt');

  await card.locator('.pending-file-remove').click();
  await expect(card).toHaveCount(0, { timeout: 3_000 });

  await fs.rm(dir, { recursive: true, force: true }).catch(() => { /* 清理失败不影响结论 */ });
});
