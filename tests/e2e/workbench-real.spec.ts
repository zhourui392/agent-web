import { expect, test, type APIRequestContext, type Page } from '@playwright/test';
import { execFileSync } from 'child_process';
import { mkdtempSync, mkdirSync, rmSync, writeFileSync } from 'fs';
import { tmpdir } from 'os';
import path from 'path';

type RepositoryCandidate = {
  repositoryKey: string;
  relativePath: string;
};

type Inspection = {
  repositories: RepositoryCandidate[];
};

type CreatedWorkbench = {
  workbenchId: string;
};

type HandoffProjection = {
  sourcePhase: string;
  version: number;
  contentHash: string;
};

type RunSubmission = {
  runId: string;
};

type RunEventPage = {
  events: Array<{
    eventType: string;
    payload: string;
  }>;
};

const temporaryRoots: string[] = [];

function git(repository: string, ...args: string[]): void {
  execFileSync('git', ['-C', repository, ...args], { stdio: 'ignore' });
}

function createWorkspace(repositoryNames: string[]): string {
  const root = mkdtempSync(path.join(tmpdir(), 'agent-web-workbench-real-e2e-'));
  temporaryRoots.push(root);
  for (const name of repositoryNames) {
    const repository = path.join(root, name);
    mkdirSync(repository);
    execFileSync('git', ['init', '-q', repository], { stdio: 'ignore' });
    git(repository, 'config', 'user.name', 'Workbench E2E');
    git(repository, 'config', 'user.email', 'workbench-e2e@example.invalid');
    writeFileSync(path.join(repository, 'README.md'), `# ${name}\n`, 'utf8');
    git(repository, 'add', 'README.md');
    git(repository, 'commit', '-q', '-m', 'initial fixture');
  }
  return root;
}

async function inspectWorkspace(
  request: APIRequestContext,
  workspaceRoot: string,
): Promise<Inspection> {
  const response = await request.post('/api/workbench/workspaces/inspect', {
    data: { workspaceRoot },
  });
  expect(response.ok(), await response.text()).toBe(true);
  return response.json() as Promise<Inspection>;
}

async function createWorkbench(
  request: APIRequestContext,
  workspaceRoot: string,
  inspection: Inspection,
  selected: string[],
  title: string,
): Promise<CreatedWorkbench> {
  const byRelativePath = new Map(
    inspection.repositories.map(repository => [repository.relativePath, repository.repositoryKey]),
  );
  const selectedKeys = selected.map(name => {
    const key = byRelativePath.get(name);
    if (!key) throw new Error(`repository fixture ${name} was not inspected`);
    return key;
  });
  const response = await request.post('/api/workbenches', {
    headers: { 'Idempotency-Key': `workbench-real-${Date.now()}-${Math.random()}` },
    data: {
      title,
      originalGoal: '通过真实 Controller、SQLite、Runtime Stub 和 SSE 验证本地开发 Workbench',
      agentType: 'CODEX',
      environment: 'test',
      workspaceRoot,
      primaryRepository: selectedKeys[0],
      repositories: selectedKeys,
    },
  });
  expect(response.status(), await response.text()).toBe(201);
  return response.json() as Promise<CreatedWorkbench>;
}

async function openWorkbench(page: Page, workbench: CreatedWorkbench, title: string): Promise<void> {
  await page.goto(`/workbench.html?id=${encodeURIComponent(workbench.workbenchId)}`);
  await expect(page.getByRole('heading', { name: title })).toBeVisible();
}

async function acceptReviewHandoff(
  request: APIRequestContext,
  workbench: CreatedWorkbench,
): Promise<void> {
  const handoffResponse = await request.put(
    `/api/workbenches/${encodeURIComponent(workbench.workbenchId)}`
      + '/phases/IMPLEMENT_TEST/handoff',
    {
      headers: { 'If-Match': '0' },
      data: {
        summary: '开发阶段已完成，进入人工 Review 与受影响测试。',
        decisions: [],
        openQuestions: [],
        pinnedFiles: [],
        referencedRuns: [],
      },
    },
  );
  expect(handoffResponse.ok(), await handoffResponse.text()).toBe(true);
  const handoff = await handoffResponse.json() as HandoffProjection;
  expect(handoff.sourcePhase).toBe('IMPLEMENT_TEST');

  const receptionResponse = await request.post(
    `/api/workbenches/${encodeURIComponent(workbench.workbenchId)}`
      + '/phases/REVIEW_REFACTOR/handoff-receptions',
    {
      data: {
        sourcePhase: handoff.sourcePhase,
        sourceVersion: handoff.version,
        sourceHash: handoff.contentHash,
      },
    },
  );
  expect(receptionResponse.ok(), await receptionResponse.text()).toBe(true);
}

function eventData(payload: string): Record<string, unknown> {
  const parsed = JSON.parse(payload) as { data?: unknown };
  expect(parsed.data).toBeTruthy();
  return parsed.data as Record<string, unknown>;
}

test.afterAll(() => {
  for (const root of temporaryRoots) rmSync(root, { recursive: true, force: true });
});

test('真实单仓 Run 经 SQLite/SSE 完成刷新恢复和 Stop 明确终态', async ({ page, request }) => {
  const root = createWorkspace(['single-service']);
  const inspection = await inspectWorkspace(request, root);
  const title = '真实单仓 Runtime 试点';
  const workbench = await createWorkbench(
    request,
    root,
    inspection,
    ['single-service'],
    title,
  );
  await openWorkbench(page, workbench, title);

  const composer = page.getByTestId('workbench-run-composer');
  await composer.fill('[E2E_RELOAD] 验证刷新后从持久化游标恢复');
  await page.getByTestId('workbench-run-submit').click();
  await expect(page.getByText('真实 Runtime 已读取本轮冻结的 Workbench 执行计划。')).toBeVisible();

  await page.reload();
  await expect(page.getByText('Workbench 真实后端运行完成。')).toBeVisible({ timeout: 15_000 });
  await expect(page.getByText('本轮运行成功')).toBeVisible();

  await page.getByTestId('open-run-history').click();
  await expect(page.getByTestId('workbench-run-history-list')).toContainText('成功');
  await expect(page.getByTestId('workbench-run-history-timeline'))
    .toContainText('Workbench 真实后端运行完成。');
  await expect(page.getByTestId('workbench-run-history-capability'))
    .toContainText('workbench-requirement-analysis');
  await page.keyboard.press('Escape');

  await expect(composer).toBeEnabled();
  await composer.fill('[E2E_WAIT_FOR_STOP] 验证真实进程停止与取消终态');
  await page.getByTestId('workbench-run-submit').click();
  await expect(page.getByTestId('workbench-run-stop')).toBeVisible();
  await page.getByTestId('workbench-run-stop').click();
  await expect(page.getByText('停止请求已记录，页面会持续等待并展示明确终态。')).toBeVisible();
  await expect(page.getByText('本轮运行已取消')).toBeVisible({ timeout: 15_000 });
});

test('真实多仓 Scope 保留主仓并排除未选择 sibling', async ({ page, request }) => {
  const root = createWorkspace(['service-a', 'service-b', 'service-c']);
  const inspection = await inspectWorkspace(request, root);

  const multiTitle = '真实多仓 Scope 试点';
  const multi = await createWorkbench(
    request,
    root,
    inspection,
    ['service-a', 'service-b'],
    multiTitle,
  );
  await openWorkbench(page, multi, multiTitle);
  await expect(page.locator('.workbench-detail-eyebrow')).toContainText('2 个仓库');
  await expect(page.locator('.workbench-scope-row')).toHaveCount(2);
  await expect(page.locator('body')).not.toContainText('service-c/README.md');

  const scopedTitle = '未选择 sibling 排除试点';
  const scoped = await createWorkbench(
    request,
    root,
    inspection,
    ['service-a'],
    scopedTitle,
  );
  await openWorkbench(page, scoped, scopedTitle);
  await expect(page.locator('.workbench-detail-eyebrow')).toContainText('1 个仓库');
  await expect(page.locator('.workbench-scope-row')).toHaveCount(1);
  await expect(page.locator('body')).not.toContainText('service-b/README.md');
  await expect(page.locator('body')).not.toContainText('service-c/README.md');
});

test('真实 Review 确认后 MODIFY 经 Runtime/SSE 持久化文件与受影响测试并可刷新恢复', async ({
  page,
  request,
}) => {
  const root = createWorkspace(['review-service']);
  const inspection = await inspectWorkspace(request, root);
  const title = '真实 Review 重构测试链路';
  const workbench = await createWorkbench(
    request,
    root,
    inspection,
    ['review-service'],
    title,
  );
  await acceptReviewHandoff(request, workbench);
  await openWorkbench(page, workbench, title);

  await page.getByRole('button', { name: /人工 Review、重构与测试/ }).click();
  await expect(page.getByRole('heading', {
    name: '人工 Review、重构与测试',
    exact: true,
    level: 2,
  })).toBeVisible();
  const reviewOpinion = [
    '[E2E_REVIEW_MODIFY_TEST]',
    '仅按人工确认修改 review-e2e.txt，并执行受影响测试。',
  ].join(' ');
  const reviewInput = page.getByPlaceholder(
    '写下需要 Agent 解释或执行的 Review 意见、重构目标及回归测试要求',
  );
  await reviewInput.fill(reviewOpinion);
  await page.getByTestId('review-save-opinion').click();
  await expect(page.getByText(/Review Opinion 已保存为 v1/)).toBeVisible();
  await page.getByTestId('review-confirm-modification').click();
  await expect(page.getByText('已精确确认')).toBeVisible();

  await page.locator('.workbench-composer-mode')
    .getByText('修改工作区', { exact: true }).click();
  const submittedResponse = page.waitForResponse(response =>
    response.request().method() === 'POST'
      && response.url().endsWith('/phases/REVIEW_REFACTOR/runs'));
  await page.getByTestId('workbench-run-submit').click();
  const submissionHttp = await submittedResponse;
  expect(submissionHttp.status()).toBe(202);
  const submission = await submissionHttp.json() as RunSubmission;

  const liveTestResult = page.locator('.workbench-test-event');
  await expect(liveTestResult).toContainText('runtime-test-command', {
    timeout: 15_000,
  });
  await expect(liveTestResult).toContainText('PASSED');
  await expect(liveTestResult).toContainText('命令已完成');
  const changedFile = page.getByRole('button', {
    name: /review-service\/review-e2e\.txt/,
  });
  await expect(changedFile).toBeVisible();
  await changedFile.click();
  await expect(page.locator('.workbench-document-panel'))
    .toContainText('Review refactor applied after human confirmation.');
  await expect(page.getByText('已按人工确认意见完成重构和受影响测试。'))
    .toBeVisible();
  await expect(page.getByText('本轮运行成功')).toBeVisible();

  const eventResponse = await request.get(
    `/api/workbenches/${encodeURIComponent(workbench.workbenchId)}`
      + `/runs/${encodeURIComponent(submission.runId)}/events-page?after=0&limit=200`,
  );
  expect(eventResponse.ok(), await eventResponse.text()).toBe(true);
  const persisted = await eventResponse.json() as RunEventPage;
  const fileEvents = persisted.events
    .filter(event => event.eventType === 'file_changed')
    .map(event => eventData(event.payload));
  expect(fileEvents).toContainEqual(expect.objectContaining({
    repositoryKey: 'review-service',
    path: 'review-e2e.txt',
    changeType: 'ADDED',
  }));
  const testStatuses = persisted.events
    .filter(event => event.eventType === 'test_progress')
    .map(event => eventData(event.payload).status);
  expect(testStatuses).toEqual(['RUNNING', 'PASSED']);
  expect(persisted.events.map(event => event.eventType)).toContain('terminal');

  await page.reload();
  await expect(page.getByRole('heading', { name: title })).toBeVisible();
  await page.getByTestId('open-run-history').click();
  const recoveredTimeline = page.getByTestId('workbench-run-history-timeline');
  await expect(recoveredTimeline).toContainText('review-service/review-e2e.txt');
  await expect(recoveredTimeline).toContainText('runtime-test-command');
  await expect(recoveredTimeline).toContainText('PASSED');
  await expect(recoveredTimeline).toContainText('命令已完成');
  await expect(recoveredTimeline).toContainText('已按人工确认意见完成重构和受影响测试。');
  await expect(recoveredTimeline).toContainText('成功');
});
