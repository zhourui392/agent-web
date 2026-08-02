import { expect, test, type Page, type Request, type Route } from '@playwright/test';

const WORKBENCH_ID = 'workbench-admin-e2e';
const RUN_ID = 'run-admin-e2e';
const NOW = 1_786_000_000_000;
const HASH_A = 'a'.repeat(64);
const HASH_B = 'b'.repeat(64);
const HASH_C = 'c'.repeat(64);

type AuthState = 'ADMIN' | 'USER' | 'ANONYMOUS';

function json(route: Route, body: unknown, status = 200): Promise<void> {
  return route.fulfill({
    status,
    contentType: 'application/json',
    body: JSON.stringify(body),
  });
}

function listItem() {
  return {
    workbenchId: WORKBENCH_ID,
    ownerId: 'owner-admin-e2e',
    ownerName: 'Owner Safe Name',
    title: 'Admin Workbench Safe View',
    status: 'ACTIVE',
    agentType: 'CODEX',
    environment: 'local',
    primaryRepositoryKey: 'agent-web',
    repositoryCount: 2,
    activeWriteRunId: RUN_ID,
    createdAt: NOW,
    updatedAt: NOW + 1,
    version: 4,
  };
}

function detail() {
  return {
    ...listItem(),
    repositoryScopeHash: HASH_A,
    repositories: [
      { repositoryKey: 'agent-web', relativePath: 'agent-web', primary: true },
      { repositoryKey: 'service-b', relativePath: 'service-b', primary: false },
    ],
    phases: [
      {
        phase: 'REQUIREMENT_ANALYSIS', phaseOrder: 0, status: 'HUMAN_COMPLETED',
        activeRunId: null, activeRunMode: null, lastActivityAt: NOW, completedAt: NOW,
      },
      {
        phase: 'SOLUTION_DESIGN', phaseOrder: 1, status: 'HUMAN_COMPLETED',
        activeRunId: null, activeRunMode: null, lastActivityAt: NOW, completedAt: NOW,
      },
      {
        phase: 'IMPLEMENT_TEST', phaseOrder: 2, status: 'IN_PROGRESS',
        activeRunId: RUN_ID, activeRunMode: 'MODIFY_WORKSPACE', lastActivityAt: NOW, completedAt: null,
      },
      {
        phase: 'REVIEW_REFACTOR', phaseOrder: 3, status: 'NOT_STARTED',
        activeRunId: null, activeRunMode: null, lastActivityAt: null, completedAt: null,
      },
    ],
    workspaceRoot: '/home/private/workspace',
    repositoryRoot: '/home/private/workspace/agent-web',
    originalGoal: 'PRIVATE_PROMPT_BODY_SHOULD_NOT_RENDER',
    rootFingerprint: 'PRIVATE_ROOT_FINGERPRINT_SHOULD_NOT_RENDER',
  };
}

function runListItem() {
  return {
    runId: RUN_ID,
    workbenchId: WORKBENCH_ID,
    phase: 'IMPLEMENT_TEST',
    status: 'RUNNING',
    runMode: 'MODIFY_WORKSPACE',
    lastEventSeq: 7,
    createdAt: NOW,
    startedAt: NOW + 10,
    cancelRequestedAt: null,
    finishedAt: null,
    failureCode: null,
  };
}

function runDetail() {
  return {
    ...runListItem(),
    exitCode: null,
    repositoryScopeHash: HASH_A,
    capabilitySnapshotHash: HASH_B,
    promptHash: HASH_C,
    runtimeHandlePresent: true,
    sessionId: 'PRIVATE_SESSION_SHOULD_NOT_RENDER',
    prompt: 'PRIVATE_PROMPT_SHOULD_NOT_RENDER',
    errorMessage: '/home/private PRIVATE_STDERR_SHOULD_NOT_RENDER',
    toolOutput: 'PRIVATE_TOOL_OUTPUT_SHOULD_NOT_RENDER',
    secret: 'PRIVATE_SECRET_SHOULD_NOT_RENDER',
  };
}

async function installFixture(
  page: Page,
  auth: AuthState,
  requests: Request[] = [],
): Promise<void> {
  await page.route('**/api/**', async route => {
    const request = route.request();
    const url = new URL(request.url());
    if (!url.pathname.startsWith('/api/')) {
      await route.fallback();
      return;
    }
    requests.push(request);

    if (url.pathname === '/api/auth/status') {
      await json(route, auth === 'ANONYMOUS'
        ? { authEnabled: true, authenticated: false, username: null, userId: null, role: null }
        : {
            authEnabled: true,
            authenticated: true,
            username: auth === 'ADMIN' ? 'admin-e2e' : 'user-e2e',
            userId: auth === 'ADMIN' ? 'admin-id' : 'user-id',
            role: auth,
          });
      return;
    }
    if (auth !== 'ADMIN') {
      await json(route, {
        code: auth === 'ANONYMOUS' ? 'AUTHENTICATION_REQUIRED' : 'ACCESS_DENIED',
      }, auth === 'ANONYMOUS' ? 401 : 403);
      return;
    }
    if (url.pathname.startsWith('/api/refinery/') || url.pathname.startsWith('/api/harness/')) {
      await json(route, { code: 'NOT_FOUND' }, 404);
      return;
    }
    if (url.pathname === '/api/admin/workbenches' && request.method() === 'GET') {
      await json(route, { items: [listItem()], nextCursor: null });
      return;
    }
    if (url.pathname === `/api/admin/workbenches/${WORKBENCH_ID}` && request.method() === 'GET') {
      await json(route, detail());
      return;
    }
    if (url.pathname === `/api/admin/workbenches/${WORKBENCH_ID}/runs`
      && request.method() === 'GET') {
      await json(route, { items: [runListItem()], nextCursor: null });
      return;
    }
    if (url.pathname === `/api/admin/workbenches/${WORKBENCH_ID}/runs/${RUN_ID}`
      && request.method() === 'GET') {
      await json(route, runDetail());
      return;
    }
    if (url.pathname === `/api/admin/workbenches/${WORKBENCH_ID}/runs/${RUN_ID}/stop`
      && request.method() === 'POST') {
      await json(route, {
        workbenchId: WORKBENCH_ID,
        runId: RUN_ID,
        action: 'STOP',
        outcome: 'REQUESTED',
        runStatus: 'CANCEL_REQUESTED',
        acceptedAt: NOW + 20,
      }, 202);
      return;
    }
    if (url.pathname === `/api/admin/workbenches/${WORKBENCH_ID}/runs/${RUN_ID}/reconcile`
      && request.method() === 'POST') {
      await json(route, {
        workbenchId: WORKBENCH_ID,
        runId: RUN_ID,
        action: 'RECONCILE',
        outcome: 'UNCHANGED',
        runStatus: 'RUNNING',
        acceptedAt: NOW + 30,
      });
      return;
    }
    await json(route, { code: 'NOT_FOUND' }, 404);
  });
}

test('未登录和普通用户都不会请求 Admin Workbench 数据', async ({ browser }) => {
  for (const auth of ['ANONYMOUS', 'USER'] as const) {
    const context = await browser.newContext();
    const page = await context.newPage();
    const requests: Request[] = [];
    await installFixture(page, auth, requests);

    await page.goto('/admin/workbenches.html');
    await expect(page.getByText('管理后台 · 管理员登录')).toBeVisible();
    if (auth === 'USER') {
      await expect(page.getByText('当前账户无管理员权限')).toBeVisible();
    }
    expect(requests.some(request =>
      new URL(request.url()).pathname.startsWith('/api/admin/workbenches'))).toBe(false);
    await context.close();
  }
});

test('ADMIN 仅查看脱敏投影并在显式确认后执行 Stop 与单 Run Reconcile', async ({ page }) => {
  const requests: Request[] = [];
  await installFixture(page, 'ADMIN', requests);
  await page.goto('/admin/workbenches.html');

  await expect(page.getByText('Workbench 运维', { exact: true })).toBeVisible();
  await expect(page.getByText('独立管理员运维边界')).toBeVisible();
  await expect(page.getByTestId('admin-workbench-list')).toContainText('Owner Safe Name');
  await expect(page.getByTestId('admin-workbench-detail')).toContainText('agent-web');
  await expect(page.getByTestId('admin-workbench-detail')).toContainText('service-b');
  await expect(page.getByTestId('admin-workbench-run-list')).toContainText(RUN_ID);

  const rendered = await page.locator('body').innerText();
  expect(rendered).not.toMatch(
    /PRIVATE_|\/home\/private|SESSION_SHOULD_NOT_RENDER|STDERR_SHOULD_NOT_RENDER|TOOL_OUTPUT_SHOULD_NOT_RENDER/,
  );
  await expect(page.getByRole('button', {
    name: /发送消息|修改 Handoff|能力覆盖|Review Confirmation|批准高影响操作/,
  })).toHaveCount(0);

  await page.getByRole('button', { name: '查看', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Run 安全详情与运维' })).toBeVisible();
  await expect(page.getByText('不会重放 Provider')).toBeVisible();
  await expect(page.getByText('存在持久化引用')).toBeVisible();

  const stopRequests = () => requests.filter(request =>
    new URL(request.url()).pathname.endsWith('/stop') && request.method() === 'POST');
  await page.getByTestId('admin-workbench-stop').click();
  await expect(page.getByText('确认停止异常 Run')).toBeVisible();
  expect(stopRequests()).toHaveLength(0);
  await page.getByRole('button', { name: '确认停止', exact: true }).click();
  await expect.poll(() => stopRequests().length).toBe(1);
  await expect(page.getByText('停止请求已受理')).toBeVisible();

  const reconcileRequests = () => requests.filter(request =>
    new URL(request.url()).pathname.endsWith('/reconcile') && request.method() === 'POST');
  await page.getByTestId('admin-workbench-reconcile').click();
  await expect(page.getByText('确认单 Run 对账')).toBeVisible();
  expect(reconcileRequests()).toHaveLength(0);
  await page.getByRole('button', { name: '确认对账', exact: true }).click();
  await expect.poll(() => reconcileRequests().length).toBe(1);
  await expect(page.getByText('单 Run 对账已完成')).toBeVisible();

  for (const request of [...stopRequests(), ...reconcileRequests()]) {
    expect(request.postData()).toBe('{}');
    expect(request.postData()).not.toMatch(/owner|actor|administrator/i);
  }
});
