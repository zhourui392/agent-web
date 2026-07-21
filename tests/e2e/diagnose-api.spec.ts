import { test, expect, APIRequestContext } from '@playwright/test';
import * as fs from 'fs/promises';
import * as os from 'os';
import * as path from 'path';

/**
 * §6.1(b) 诊断 + issue-log 纯 API 链路围栏(无 UI 依赖)。
 *
 * 组件化/admin 迁移是纯前端重构,后端 API 契约不变。本 spec 用 APIRequestContext
 * 直接打 HTTP,锁住「诊断提交 → 历史列表/详情 → continue-as-chat → issue-log draft/save 落盘」
 * 全链路,迁移全程必须持续绿。
 *
 * admin 鉴权前向兼容:Phase 0 时 /api/diagnose-history 尚未纳入 admin 保护,
 * 但 spec 预先以 admin 身份登录拿 cookie;Phase 2 把该前缀纳入 protected-prefixes 后无需改本 spec。
 *
 * 关键依赖:
 * - application-e2e.yml: agent.cli.codex 指向 codex-json-stub.cmd, agent.admin.password=e2e-admin-pass
 * - agent.issue-log.refine.enabled=false → draft 走 DraftBuilder 启发式 fallback
 */

const API_KEY = 'ak-ops-team-change-me';
const ADMIN_PASSWORD = 'e2e-admin-pass';
const STUB_KEYWORDS = ['ServiceA', '限流过紧', 'Sentinel'];
const RUN_MARKER = 'E2E-DIAG-API-' + Date.now();

let tmpDir: string;

test.beforeAll(async () => {
  tmpDir = path.join(os.tmpdir(), 'e2e-diagnose-api-' + Date.now());
  await fs.mkdir(tmpDir, { recursive: true });
});

test.afterAll(async () => {
  if (tmpDir) {
    await fs.rm(tmpDir, { recursive: true, force: true }).catch(() => {
      // 临时目录清理失败不影响测试结论
    });
  }
});

/** 以 admin 身份登录,cookie 由 APIRequestContext 自动随后续请求携带。 */
async function loginAsAdmin(request: APIRequestContext): Promise<void> {
  const res = await request.post('/api/admin/login', {
    data: { password: ADMIN_PASSWORD },
  });
  expect(res.ok(), 'admin login should succeed with e2e password').toBeTruthy();
  expect((await res.json()).authenticated).toBe(true);

  // 确认 cookie 已被 context 记住并随请求回带
  const status = await request.get('/api/admin/status');
  expect((await status.json()).authenticated, 'admin cookie should stick across requests').toBe(true);
}

async function submitDiagnose(request: APIRequestContext): Promise<string> {
  const res = await request.post('/api/diagnose', {
    headers: { 'X-API-Key': API_KEY, 'Content-Type': 'application/json' },
    data: {
      agentType: 'CODEX',
      workingDir: tmpDir,
      query: RUN_MARKER + ' 测试: 调用 ServiceA 频繁超时',
      env: 'test',
    },
  });
  expect(res.status(), 'diagnose submit should return 201').toBe(201);
  const body = await res.json();
  expect(body.taskId).toBeTruthy();
  expect(body.streamUrl).toBe(`/api/diagnose/${body.taskId}/stream`);
  return body.taskId;
}

async function waitTaskSucceeded(request: APIRequestContext, taskId: string): Promise<void> {
  const deadline = Date.now() + 20_000;
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
  throw new Error('diagnose did not reach SUCCESS within 20s, last=' + last);
}

test('diagnose API 链路: 提交 → 历史列表/详情 → continue-as-chat', async ({ request }) => {
  await loginAsAdmin(request);

  const taskId = await submitDiagnose(request);
  await waitTaskSucceeded(request, taskId);

  // 历史列表能查到本次任务
  const listRes = await request.get('/api/diagnose-history?page=1&size=50');
  expect(listRes.ok()).toBeTruthy();
  const list: Array<{ taskId: string; status: string }> = await listRes.json();
  const found = list.find((t) => t.taskId === taskId);
  expect(found, 'submitted task should appear in history list').toBeTruthy();
  expect(found!.status).toBe('SUCCESS');

  // 详情含提炼后的 result + stub 关键词
  const detailRes = await request.get(`/api/diagnose-history/${encodeURIComponent(taskId)}`);
  expect(detailRes.ok()).toBeTruthy();
  const detail = await detailRes.json();
  expect(detail.taskId).toBe(taskId);
  for (const kw of STUB_KEYWORDS) {
    expect(detail.result).toContain(kw);
  }

  // continue-as-chat 返回新会话元数据
  const contRes = await request.post(`/api/diagnose-history/${encodeURIComponent(taskId)}/continue-as-chat`);
  expect(contRes.ok()).toBeTruthy();
  const cont = await contRes.json();
  expect(cont.sessionId, 'continue-as-chat sessionId').toBeTruthy();
  expect(cont.workingDir).toBe(tmpDir);
  expect(cont.agentType).toBe('CODEX');
  expect(cont).toHaveProperty('resumeId');
});

test('issue-log API 链路: draft → save → 落盘 I-*.md + INDEX.md', async ({ request }) => {
  await loginAsAdmin(request);

  const taskId = await submitDiagnose(request);
  await waitTaskSucceeded(request, taskId);

  // draft: refine 关闭 → DraftBuilder 启发式, categories/services 非空
  const draftRes = await request.get(`/api/diagnose-history/${encodeURIComponent(taskId)}/issue-log/draft`);
  expect(draftRes.ok()).toBeTruthy();
  const draft = await draftRes.json();
  expect(draft.refined, 'refine disabled in e2e → heuristic draft').toBe(false);
  expect(draft.title.trim().length).toBeGreaterThan(0);
  expect(Array.isArray(draft.categories) && draft.categories.length).toBeTruthy();
  expect(Array.isArray(draft.services) && draft.services.length).toBeTruthy();

  // save: 用 draft 字段 + 自定义 title 回存
  const title = RUN_MARKER + ' ServiceA 限流超时';
  const saveRes = await request.post(`/api/diagnose-history/${encodeURIComponent(taskId)}/issue-log`, {
    data: {
      title,
      categories: draft.categories,
      services: draft.services,
      // 落盘校验要求触发词非空;stub 文本无可提取 token 时启发式 draft 为空,补一个稳定词
      triggerSignals: draft.triggerSignals && draft.triggerSignals.length
        ? draft.triggerSignals : ['ServiceA 频繁超时'],
      phenomenon: draft.phenomenon || '',
      rootCause: draft.rootCause || '',
      solution: draft.solution || '',
      notes: draft.notes || '',
    },
  });
  expect(saveRes.ok(), 'issue-log save should succeed').toBeTruthy();
  const saved = await saveRes.json();
  expect(saved.id).toMatch(/^I-\d+/);
  expect(saved.filePath).toBeTruthy();

  // 落盘断言
  const issuesDir = path.join(tmpDir, 'docs', 'issue-log', 'issue');
  const files = await fs.readdir(issuesDir);
  const issueFiles = files.filter((f) => /^I-\d+.*\.md$/.test(f));
  expect(issueFiles.length).toBeGreaterThanOrEqual(1);
  const matched = (
    await Promise.all(issueFiles.map((f) => fs.readFile(path.join(issuesDir, f), 'utf-8')))
  ).find((c) => c.includes(title));
  expect(matched, 'saved issue file should contain our title').toBeTruthy();
  expect(matched!).toMatch(/^# I-\d+ - /);

  const indexContent = await fs.readFile(path.join(tmpDir, 'docs', 'issue-log', 'INDEX.md'), 'utf-8');
  expect(indexContent).toContain(title);
});
