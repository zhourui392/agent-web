import { test, expect, APIRequestContext, Page } from '@playwright/test';
import path from 'path';
import os from 'os';
import { promises as fs } from 'fs';
import { gotoAdminMenu, loginAdminUI } from './_admin';

/**
 * 诊断历史 admin UI 主链路 E2E(ChatPanel 组件化方案 Phase 3 改写)。
 *
 * 诊断历史已从主控台迁到管理后台 /admin(管理口令鉴权 + protected-prefixes 把关),
 * 故 UI 断言走 /admin:管理口令登录 → 诊断历史菜单 → 列表 → 详情抽屉渲染结论;
 * 「继续对话」走内嵌 <chat-panel> 续聊(无跳转),断言 resume 后看到原提问气泡。
 *
 * 纯 API 链路(提交/列表/详情/continue-as-chat 契约)由 diagnose-api.spec.ts 覆盖,这里只验 UI。
 *
 * 关键依赖:
 * - application-e2e.yml: agent.cli.codex 指向 codex-json-stub.cmd, agent.admin.password=e2e-admin-pass
 * - /api/diagnose 走 X-API-Key(非 admin 保护);/api/diagnose/{id} 轮询同样走 X-API-Key
 */

const API_KEY = 'ak-ops-team-change-me';
const STUB_KEYWORDS = ['ServiceA', '限流过紧', 'Sentinel'];

async function makeTempWorkingDir(): Promise<string> {
  const tmpDir = path.join(os.tmpdir(), 'e2e-diagnose-' + Date.now());
  await fs.mkdir(tmpDir, { recursive: true });
  return tmpDir;
}

/** 提交一条诊断,返回 taskId 和 marker。列表用 taskId 定位,marker 用于详情和续聊断言。 */
async function submitDiagnose(request: APIRequestContext): Promise<{ taskId: string; marker: string }> {
  const marker = 'E2E-DIAG-UI-' + Date.now();
  const workingDir = await makeTempWorkingDir();
  const res = await request.post('/api/diagnose', {
    headers: { 'X-API-Key': API_KEY, 'Content-Type': 'application/json' },
    data: {
      agentType: 'CODEX',
      workingDir,
      query: marker + ' 测试 traceId=stub-001 接口超时',
      env: 'test',
    },
  });
  expect(res.status()).toBe(201);
  const body = await res.json();
  expect(body.taskId).toBeTruthy();
  return { taskId: body.taskId, marker };
}

/** 轮询 /api/diagnose/{id}(X-API-Key,非 admin 保护)直到终态。 */
async function waitTaskFinished(request: APIRequestContext, taskId: string, timeoutMs = 20_000): Promise<void> {
  const deadline = Date.now() + timeoutMs;
  let last = '';
  while (Date.now() < deadline) {
    const res = await request.get(`/api/diagnose/${taskId}`, { headers: { 'X-API-Key': API_KEY } });
    expect(res.ok()).toBeTruthy();
    last = (await res.json()).status;
    if (last === 'SUCCESS') return;
    if (last === 'FAILED' || last === 'CANCELLED' || last === 'TIMEOUT') {
      throw new Error('diagnose terminated abnormally: ' + last);
    }
    await new Promise((r) => setTimeout(r, 300));
  }
  throw new Error(`diagnose did not finish within ${timeoutMs}ms, last=${last}`);
}

/** /admin 管理口令登录 → 整页跳「诊断历史」页(MPA)。 */
async function gotoAdminDiagnose(page: Page): Promise<void> {
  await gotoAdminMenu(page, '诊断历史');
}

/** 在诊断历史列表中点开指定 taskId 的任务详情抽屉。 */
async function openDetailByTaskId(page: Page, taskId: string) {
  const row = page.locator(`[data-test="diag-row"][data-task-id="${taskId}"]`).first();
  await expect(row).toBeVisible({ timeout: 10_000 });
  await row.click();
  const drawer = page.locator('.el-drawer', { hasText: '诊断详情' });
  await expect(drawer).toBeVisible({ timeout: 5_000 });
  return drawer;
}

test('admin 诊断历史: 登录 → 列表 → 详情抽屉渲染结论', async ({ page, request }) => {
  const { taskId, marker } = await submitDiagnose(request);
  await waitTaskFinished(request, taskId);

  await gotoAdminDiagnose(page);
  const drawer = await openDetailByTaskId(page, taskId);

  // 抽屉里能看到 taskId(确保正中目标任务)
  await expect(drawer.getByText(taskId, { exact: false })).toBeVisible({ timeout: 5_000 });
  await expect(drawer.getByText(marker, { exact: false })).toBeVisible({ timeout: 5_000 });
  // stub 输出的诊断结论关键词(markdown 渲染后作为纯文本存在 DOM)
  for (const kw of STUB_KEYWORDS) {
    await expect(drawer.getByText(kw, { exact: false }).first()).toBeVisible({ timeout: 10_000 });
  }
});

test('admin 续聊: 详情「继续对话」→ 内嵌 chat-panel resume 原提问', async ({ page, request }) => {
  const { taskId, marker } = await submitDiagnose(request);
  await waitTaskFinished(request, taskId);

  await gotoAdminDiagnose(page);
  const drawer = await openDetailByTaskId(page, taskId);

  await drawer.getByRole('button', { name: '继续对话' }).click();

  // 切到「对话」菜单 + 内嵌 chat-panel resume:原诊断提问以 user 气泡回填(continueAsChat 把 query 落成首条 user 消息)
  const userBubble = page.locator('.message-user-text').filter({ hasText: marker }).first();
  await expect(userBubble).toBeVisible({ timeout: 10_000 });
  // resume 完成的系统提示
  await expect(page.getByText('已恢复历史会话').first()).toBeVisible({ timeout: 10_000 });
});

test('admin deep-link: ?taskId 进页自动弹出对应诊断详情抽屉', async ({ page, request }) => {
  const { taskId, marker } = await submitDiagnose(request);
  await waitTaskFinished(request, taskId);

  // 先登录拿到 admin_session cookie,再直达 deep-link;同页 emit('ready') → onReady 读 taskId 自动弹抽屉
  await loginAdminUI(page);
  await page.goto('/admin/diagnose.html?taskId=' + encodeURIComponent(taskId));

  const drawer = page.locator('.el-drawer', { hasText: '诊断详情' });
  await expect(drawer).toBeVisible({ timeout: 10_000 });
  await expect(drawer.getByText(taskId, { exact: false })).toBeVisible({ timeout: 5_000 });
  await expect(drawer.getByText(marker, { exact: false })).toBeVisible({ timeout: 5_000 });
});

test('admin 搜索: 关键词过滤命中对应诊断行', async ({ page, request }) => {
  const { taskId, marker } = await submitDiagnose(request);
  await waitTaskFinished(request, taskId);

  await gotoAdminDiagnose(page);
  await page.getByPlaceholder('搜索提问 / taskId').fill(marker);
  await page.getByRole('button', { name: '搜索' }).click();

  // 命中行可见,且可点开详情确认正中目标
  const row = page.locator(`[data-test="diag-row"][data-task-id="${taskId}"]`).first();
  await expect(row).toBeVisible({ timeout: 10_000 });
  await row.click();
  const drawer = page.locator('.el-drawer', { hasText: '诊断详情' });
  await expect(drawer.getByText(marker, { exact: false })).toBeVisible({ timeout: 5_000 });
});
