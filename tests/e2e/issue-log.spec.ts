import { test, expect, APIRequestContext, Page } from '@playwright/test';
import * as fs from 'fs/promises';
import * as os from 'os';
import * as path from 'path';
import { gotoAdminMenu } from './_admin';

/**
 * Issue-log 沉淀 admin UI 主链路 E2E(ChatPanel 组件化方案 Phase 3 改写)。
 *
 * 沉淀入口已随诊断历史迁到 /admin。链路:
 * 1. POST /api/diagnose (X-API-Key) 起诊断 → 轮询 /api/diagnose/{id} 到 SUCCESS
 * 2. 复用数据库 ADMIN 用户会话 → 诊断历史菜单 → 详情抽屉 → 点 "沉淀为 issue-log"
 * 3. 弹窗启发式 draft (refine 关闭, 走 DraftBuilder fallback)
 * 4. 改 title 后保存
 * 5. 落盘断言: <workingDir>/docs/issue-log/issue/I-*.md + INDEX.md 含新 ID
 *
 * 纯 API draft/save 契约由 diagnose-api.spec.ts 覆盖,这里只验 admin UI。
 *
 * 关键依赖:
 * - application-e2e.yml: agent.issue-log.refine.enabled=false → DraftBuilder
 * - codex-json-stub: 输出 "ServiceA 限流过紧 / Sentinel" 结论
 */

const API_KEY = 'ak-ops-team-change-me';
const RUN_MARKER = 'E2E-ISSUELOG-' + Date.now();

let tmpDir: string;

test.beforeAll(async () => {
  tmpDir = path.join(os.tmpdir(), 'e2e-issuelog-' + Date.now());
  await fs.mkdir(tmpDir, { recursive: true });
});

test.afterAll(async () => {
  if (tmpDir) {
    await fs.rm(tmpDir, { recursive: true, force: true }).catch(() => {
      // 临时目录被进程占用时清理失败属可接受
    });
  }
});

async function submitDiagnose(request: APIRequestContext): Promise<string> {
  const res = await request.post('/api/diagnose', {
    headers: { 'X-API-Key': API_KEY },
    data: {
      workingDir: tmpDir,
      agentType: 'CODEX',
      query: RUN_MARKER + ' 测试: 调用 ServiceA 频繁超时, 是不是限流问题',
      env: 'test',
    },
  });
  expect(res.status(), 'diagnose submit should return 201').toBe(201);
  const body = await res.json();
  expect(body.taskId).toBeTruthy();
  return body.taskId;
}

/** 轮询 /api/diagnose/{id}(X-API-Key,非 admin 保护)到 SUCCESS。 */
async function waitForDiagnoseSucceeded(request: APIRequestContext, taskId: string): Promise<void> {
  const deadline = Date.now() + 20_000;
  let lastStatus = '';
  while (Date.now() < deadline) {
    const res = await request.get('/api/diagnose/' + encodeURIComponent(taskId), {
      headers: { 'X-API-Key': API_KEY },
    });
    if (res.ok()) {
      lastStatus = (await res.json()).status;
      if (lastStatus === 'SUCCESS') return;
      if (lastStatus === 'FAILED' || lastStatus === 'CANCELLED' || lastStatus === 'TIMEOUT') {
        throw new Error('diagnose terminated abnormally: ' + lastStatus);
      }
    }
    await new Promise((r) => setTimeout(r, 500));
  }
  throw new Error('diagnose did not reach SUCCESS within 20s, lastStatus=' + lastStatus);
}

async function gotoAdminDiagnose(page: Page): Promise<void> {
  await gotoAdminMenu(page, '诊断历史');
}

test('admin issue-log: 诊断 → 详情 → 沉淀 → 落盘 I-*.md + INDEX.md', async ({ page, request }) => {
  // ===== 1. 提交诊断 + 等完成 =====
  const taskId = await submitDiagnose(request);
  await waitForDiagnoseSucceeded(request, taskId);

  // ===== 2. /admin 登录 → 诊断历史 → 点本次任务详情 =====
  await gotoAdminDiagnose(page);
  const taskRow = page.locator(`[data-test="diag-row"][data-task-id="${taskId}"]`).first();
  await expect(taskRow).toBeVisible({ timeout: 10_000 });
  await taskRow.click();

  const draftBtn = page.getByRole('button', { name: /沉淀.*issue-log/ });
  await expect(draftBtn).toBeVisible({ timeout: 5_000 });
  await draftBtn.click();

  // ===== 3. 弹窗 loading → 启发式 draft 显示 =====
  const titleInput = page.getByLabel('标题');
  await expect(titleInput).toBeVisible({ timeout: 10_000 });
  const initialTitle = await titleInput.inputValue();
  expect(initialTitle.trim().length, 'fallback title should be non-empty').toBeGreaterThan(0);

  // ===== 4. 改稳定 title 用于断言;categories/services 由 DraftBuilder 兜底满足必填 =====
  await titleInput.fill('E2E ServiceA 限流超时');

  // ===== 4.5 触发词必填(落盘校验拦空触发词),allow-create select 手动补一个 =====
  const triggerSelect = page.locator('[data-test="issue-log-trigger-select"]');
  await triggerSelect.click();
  await triggerSelect.locator('input').fill('ServiceA 频繁超时');
  await page.keyboard.press('Enter');
  await page.keyboard.press('Escape');

  // ===== 5. 保存 =====
  const saveBtn = page.getByRole('button', { name: /^保存$/ });
  await expect(saveBtn).toBeEnabled();
  await saveBtn.click();

  await expect(page.locator('.el-message--success').first())
    .toContainText(/已生成\s+I-\d+/, { timeout: 10_000 });

  // ===== 6. 文件落盘断言 =====
  const issuesDir = path.join(tmpDir, 'docs', 'issue-log', 'issue');
  const indexFile = path.join(tmpDir, 'docs', 'issue-log', 'INDEX.md');

  const files = await fs.readdir(issuesDir);
  const issueFiles = files.filter((f) => /^I-\d+.*\.md$/.test(f));
  expect(issueFiles.length, 'should have at least one I-*.md file').toBeGreaterThanOrEqual(1);

  const issueContent = await fs.readFile(path.join(issuesDir, issueFiles[0]), 'utf-8');
  expect(issueContent).toMatch(/^# I-\d+ - /);
  expect(issueContent).toContain('E2E ServiceA 限流超时');
  expect(issueContent).toMatch(/- \*\*类型\*\*: \S+/);
  expect(issueContent).toMatch(/- \*\*服务\*\*: \S+/);

  const indexContent = await fs.readFile(indexFile, 'utf-8');
  expect(indexContent).toMatch(/\|\s*I-\d+\s*\|/);
  expect(indexContent).toContain('E2E ServiceA 限流超时');
});
