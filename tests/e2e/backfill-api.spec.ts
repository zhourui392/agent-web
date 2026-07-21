import { test, expect, APIRequestContext } from '@playwright/test';

/**
 * §6.1(b) issue-log 经验回填纯 API 契约围栏(无 UI 依赖)。
 *
 * 锁住 candidates 列表响应结构、approve/reject 缺失候选的 404、手动 run 的异步触发契约。
 * admin 迁移后这些端点路径/契约不变,本 spec 全程必须绿。
 *
 * 注:e2e profile agent.issue-log.backfill.enabled=false(关定时回填),
 * 但手动 /run 端点仍可调,异步立即返回,这里只断言同步响应契约。
 */

const ADMIN_PASSWORD = 'e2e-admin-pass';

async function loginAsAdmin(request: APIRequestContext): Promise<void> {
  const res = await request.post('/api/admin/login', { data: { password: ADMIN_PASSWORD } });
  expect(res.ok(), 'admin login should succeed').toBeTruthy();
  expect((await res.json()).authenticated).toBe(true);
}

test('backfill API: candidates 列表响应结构', async ({ request }) => {
  await loginAsAdmin(request);

  const res = await request.get('/api/issue-log-backfill/candidates');
  expect(res.ok()).toBeTruthy();
  const body = await res.json();
  expect(Array.isArray(body.candidates), 'candidates should be an array').toBeTruthy();
  expect(body).toHaveProperty('knownCategories');
  expect(body).toHaveProperty('knownServices');
});

test('backfill API: approve/reject 缺失候选返回 404', async ({ request }) => {
  await loginAsAdmin(request);

  const missing = 'NO-SUCH-TASK-' + Date.now();

  const rejectRes = await request.post(`/api/issue-log-backfill/candidates/${missing}/reject`);
  expect(rejectRes.status()).toBe(404);
  expect((await rejectRes.json()).error).toBe('candidate_not_found');

  const approveRes = await request.post(`/api/issue-log-backfill/candidates/${missing}/approve`, {
    data: { title: 'x', categories: ['logic-pitfall'], services: ['svc'] },
  });
  expect(approveRes.status()).toBe(404);
  expect((await approveRes.json()).error).toBe('candidate_not_found');
});

test('backfill API: 手动 run 异步触发契约', async ({ request }) => {
  await loginAsAdmin(request);

  const res = await request.post('/api/issue-log-backfill/run');
  // 无在跑任务 → 202 triggered;若恰有一轮在跑 → 200 alreadyRunning
  expect([200, 202]).toContain(res.status());
  const body = await res.json();
  if (res.status() === 202) {
    expect(body.triggered).toBe(true);
  } else {
    expect(body.alreadyRunning).toBe(true);
  }
});
