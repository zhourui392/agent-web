import {
  expect,
  test,
  type APIRequestContext,
  type APIResponse,
  type Page,
} from '@playwright/test';
import { execFileSync } from 'child_process';
import { mkdtempSync, mkdirSync, rmSync, writeFileSync } from 'fs';
import { tmpdir } from 'os';
import path from 'path';

type WorkbenchRunMode = 'DISCUSS_READ_ONLY' | 'MODIFY_WORKSPACE';

type RepositoryCandidate = {
  repositoryKey: string;
  relativePath: string;
};

type Inspection = {
  repositories: RepositoryCandidate[];
};

type AdminStageCatalog = {
  stageCatalogVersion: number;
};

type AdminStageDefinition = {
  definitionIdentifier: string;
  definitionVersion: number;
};

type SelectableStageDefinition = {
  definitionIdentifier: string;
  publishedRevision: number;
  displayName: string;
};

type SelectableStageCatalog = {
  stageCatalogVersion: number;
  stages: SelectableStageDefinition[];
};

type WorkbenchStageView = {
  stageInstanceIdentifier: string;
  definitionIdentifier: string;
  displayName: string;
  allowedRunModes: WorkbenchRunMode[];
};

type WorkbenchDetail = {
  id: string;
  stages: WorkbenchStageView[];
};

type CreatedWorkbench = {
  workbenchId: string;
  stage: WorkbenchStageView;
};

type RunSubmission = {
  runId: string;
};

type RunDetail = {
  runId: string;
  stageInstanceIdentifier: string;
  status: string;
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

async function expectStatus(response: APIResponse, status: number): Promise<void> {
  expect(response.status(), await response.text()).toBe(status);
}

async function inspectWorkspace(
  request: APIRequestContext,
  workspaceRoot: string,
): Promise<Inspection> {
  const response = await request.post('/api/workbench/workspaces/inspect', {
    data: { workspaceRoot },
  });
  await expectStatus(response, 200);
  return response.json() as Promise<Inspection>;
}

async function readAdminStageCatalog(
  request: APIRequestContext,
): Promise<AdminStageCatalog> {
  const response = await request.get('/api/admin-settings/workbench/stage-definitions');
  await expectStatus(response, 200);
  return response.json() as Promise<AdminStageCatalog>;
}

async function publishStageDefinition(
  request: APIRequestContext,
  definitionIdentifier: string,
  sequenceNumber: number,
  displayName: string,
  allowedRunModes: WorkbenchRunMode[],
): Promise<SelectableStageCatalog> {
  const catalogBeforeCreate = await readAdminStageCatalog(request);
  const createdResponse = await request.post(
    '/api/admin-settings/workbench/stage-definitions',
    {
      headers: { 'If-Match': String(catalogBeforeCreate.stageCatalogVersion) },
      data: {
        definitionIdentifier,
        sequenceNumber,
        displayName,
        description: `${displayName}真实 E2E 阶段`,
        stageRules: '仅在冻结 Repository Scope 内按当前运行模式完成任务。',
        allowedRunModes,
        commandReferences: [],
        skillReferences: [],
        mcpServerReferences: [],
      },
    },
  );
  await expectStatus(createdResponse, 200);
  const created = await createdResponse.json() as AdminStageDefinition;
  expect(created.definitionIdentifier).toBe(definitionIdentifier);

  const catalogBeforePublish = await readAdminStageCatalog(request);
  const publishedResponse = await request.post(
    `/api/admin-settings/workbench/stage-definitions/${encodeURIComponent(definitionIdentifier)}/publish`,
    {
      headers: { 'If-Match': String(created.definitionVersion) },
      data: { expectedStageCatalogVersion: catalogBeforePublish.stageCatalogVersion },
    },
  );
  await expectStatus(publishedResponse, 200);

  const selectableResponse = await request.get('/api/workbench/stage-definitions');
  await expectStatus(selectableResponse, 200);
  const selectable = await selectableResponse.json() as SelectableStageCatalog;
  expect(selectable.stages).toContainEqual(expect.objectContaining({
    definitionIdentifier,
    displayName,
  }));
  return selectable;
}

async function createWorkbench(
  request: APIRequestContext,
  workspaceRoot: string,
  inspection: Inspection,
  selectedRepositories: string[],
  stageCatalog: SelectableStageCatalog,
  stageDefinitionIdentifier: string,
  title: string,
): Promise<CreatedWorkbench> {
  const byRelativePath = new Map(
    inspection.repositories.map(repository => [repository.relativePath, repository.repositoryKey]),
  );
  const selectedKeys = selectedRepositories.map(name => {
    const key = byRelativePath.get(name);
    if (!key) throw new Error(`repository fixture ${name} was not inspected`);
    return key;
  });
  const response = await request.post('/api/workbenches', {
    headers: { 'Idempotency-Key': `workbench-real-${Date.now()}-${Math.random()}` },
    data: {
      title,
      originalGoal: '通过真实 Controller、SQLite、Runtime Stub 和 SSE 验证 Dynamic Stage Workbench',
      agentType: 'CODEX',
      environment: 'test',
      workspaceRoot,
      primaryRepository: selectedKeys[0],
      repositories: selectedKeys,
      stageDefinitionIdentifiers: [stageDefinitionIdentifier],
      expectedStageCatalogVersion: stageCatalog.stageCatalogVersion,
    },
  });
  await expectStatus(response, 201);
  const created = await response.json() as { workbenchId: string };

  const detailResponse = await request.get(
    `/api/workbenches/${encodeURIComponent(created.workbenchId)}`,
  );
  await expectStatus(detailResponse, 200);
  const detail = await detailResponse.json() as WorkbenchDetail;
  const stage = detail.stages.find(
    item => item.definitionIdentifier === stageDefinitionIdentifier,
  );
  if (!stage) {
    throw new Error(`created Workbench did not freeze Stage ${stageDefinitionIdentifier}`);
  }
  return { workbenchId: created.workbenchId, stage };
}

async function openWorkbench(page: Page, workbench: CreatedWorkbench, title: string): Promise<void> {
  await page.goto(`/workbench.html?id=${encodeURIComponent(workbench.workbenchId)}`);
  await expect(page.locator('.workbench-list-item.active')).toContainText(title);
  await expect(page.locator('.workbench-stage.active')).toContainText(workbench.stage.displayName);
}

function stageRunsUrl(workbench: CreatedWorkbench): string {
  return `/api/workbenches/${encodeURIComponent(workbench.workbenchId)}`
    + `/stages/${encodeURIComponent(workbench.stage.stageInstanceIdentifier)}/runs`;
}

function eventData(payload: string): Record<string, unknown> {
  const parsed = JSON.parse(payload) as { data?: unknown };
  expect(parsed.data).toBeTruthy();
  return parsed.data as Record<string, unknown>;
}

test.afterAll(() => {
  for (const root of temporaryRoots) rmSync(root, { recursive: true, force: true });
});

test('真实 Stage Run 经 SQLite/SSE 完成刷新恢复和 Stop 明确终态', async ({
  page,
  request,
}) => {
  const stageCatalog = await publishStageDefinition(
    request,
    'e2e-recovery',
    101,
    '刷新恢复',
    ['DISCUSS_READ_ONLY'],
  );
  const root = createWorkspace(['single-service']);
  const inspection = await inspectWorkspace(request, root);
  const title = '真实单仓 Dynamic Stage 试点';
  const workbench = await createWorkbench(
    request,
    root,
    inspection,
    ['single-service'],
    stageCatalog,
    'e2e-recovery',
    title,
  );
  await openWorkbench(page, workbench, title);
  await expect(page.getByTestId('workbench-run-mode-selector')).toContainText('只读讨论');

  const composer = page.getByTestId('workbench-run-composer');
  await composer.fill('[E2E_RELOAD] 验证刷新后从持久化游标恢复');
  await page.getByTestId('workbench-run-submit').click();
  await expect(page.getByText('真实 Runtime 已读取本轮冻结的 Workbench 执行计划。'))
    .toBeVisible();

  await page.reload();
  await expect(page.getByText('Workbench 真实后端运行完成。'))
    .toBeVisible({ timeout: 15_000 });
  await expect(page.getByText('本轮运行成功')).toBeVisible();

  await page.getByTestId('open-run-history').click();
  await expect(page.getByTestId('workbench-run-history-list')).toContainText('成功');
  await expect(page.getByTestId('workbench-run-history-timeline'))
    .toContainText('Workbench 真实后端运行完成。');
  await expect(page.getByTestId('workbench-run-history-capability'))
    .toContainText('workbench-stage/e2e-recovery');
  await page.keyboard.press('Escape');

  await expect(composer).toBeEnabled();
  await composer.fill('[E2E_WAIT_FOR_STOP] 验证真实进程停止与取消终态');
  await page.getByTestId('workbench-run-submit').click();
  await expect(page.getByTestId('workbench-run-stop')).toBeVisible();
  await page.getByTestId('workbench-run-stop').click();
  await expect(page.getByText('停止请求已记录，页面会持续等待并展示明确终态。'))
    .toBeVisible();
  await expect(page.getByText('本轮运行已取消')).toBeVisible({ timeout: 15_000 });
});

test('关闭浏览器页面不取消后台 Stage Run，重新打开后恢复同一终态与历史', async ({
  page,
  context,
  request,
}) => {
  const stageCatalog = await publishStageDefinition(
    request,
    'e2e-detached-run',
    201,
    '后台续跑',
    ['DISCUSS_READ_ONLY'],
  );
  const root = createWorkspace(['detached-run-service']);
  const inspection = await inspectWorkspace(request, root);
  const title = '真实浏览器关闭恢复试点';
  const workbench = await createWorkbench(
    request,
    root,
    inspection,
    ['detached-run-service'],
    stageCatalog,
    'e2e-detached-run',
    title,
  );
  await openWorkbench(page, workbench, title);

  const submittedResponse = page.waitForResponse(response =>
    response.request().method() === 'POST'
      && new URL(response.url()).pathname === stageRunsUrl(workbench));
  await page.getByTestId('workbench-run-composer')
    .fill('[E2E_RELOAD] 验证浏览器页面生命周期与后台 Stage Run 解耦');
  await page.getByTestId('workbench-run-submit').click();
  const submissionHttp = await submittedResponse;
  expect(submissionHttp.status()).toBe(202);
  const submission = await submissionHttp.json() as RunSubmission;
  await expect(page.getByText('真实 Runtime 已读取本轮冻结的 Workbench 执行计划。'))
    .toBeVisible();

  await page.close();

  const runUrl = `/api/workbenches/${encodeURIComponent(workbench.workbenchId)}`
    + `/runs/${encodeURIComponent(submission.runId)}`;
  await expect.poll(async () => {
    const response = await request.get(runUrl);
    if (!response.ok()) return `HTTP_${response.status()}`;
    const detail = await response.json() as RunDetail;
    if (detail.runId !== submission.runId
      || detail.stageInstanceIdentifier !== workbench.stage.stageInstanceIdentifier) {
      return 'RUN_IDENTITY_MISMATCH';
    }
    return detail.status;
  }, {
    message: '浏览器页面关闭后，后台 Stage Run 应继续执行到成功终态',
    timeout: 15_000,
  }).toBe('SUCCEEDED');

  const reopened = await context.newPage();
  await openWorkbench(reopened, workbench, title);
  await expect(reopened.getByText('Workbench 真实后端运行完成。')).toBeVisible();
  await reopened.getByTestId('open-run-history').click();
  const history = reopened.getByTestId('workbench-run-history-list');
  await expect(history).toContainText(submission.runId);
  await expect(history).toContainText('成功');
  const timeline = reopened.getByTestId('workbench-run-history-timeline');
  await expect(timeline).toContainText('Workbench 真实后端运行完成。');
  await expect(timeline).toContainText('成功');
});

test('真实多仓 Scope 保留主仓并排除未选择 sibling', async ({ page, request }) => {
  const stageCatalog = await publishStageDefinition(
    request,
    'e2e-repository-scope',
    301,
    '仓库范围',
    ['DISCUSS_READ_ONLY'],
  );
  const root = createWorkspace(['service-a', 'service-b', 'service-c']);
  const inspection = await inspectWorkspace(request, root);

  const multiTitle = '真实多仓 Scope 试点';
  const multi = await createWorkbench(
    request,
    root,
    inspection,
    ['service-a', 'service-b'],
    stageCatalog,
    'e2e-repository-scope',
    multiTitle,
  );
  await openWorkbench(page, multi, multiTitle);
  await expect(page.getByTestId('repository-scope-popover')).toContainText('2 个仓库');
  await expect(page.locator('.workbench-scope-row')).toHaveCount(2);
  await expect(page.locator('body')).not.toContainText('service-c/README.md');

  const scopedTitle = '未选择 sibling 排除试点';
  const scoped = await createWorkbench(
    request,
    root,
    inspection,
    ['service-a'],
    stageCatalog,
    'e2e-repository-scope',
    scopedTitle,
  );
  await openWorkbench(page, scoped, scopedTitle);
  await expect(page.getByTestId('repository-scope-popover')).toContainText('1 个仓库');
  await expect(page.locator('.workbench-scope-row')).toHaveCount(1);
  await expect(page.locator('body')).not.toContainText('service-b/README.md');
  await expect(page.locator('body')).not.toContainText('service-c/README.md');
});

test('真实可写 Stage 经 Runtime/SSE 持久化文件与受影响测试并可刷新恢复', async ({
  page,
  request,
}) => {
  const stageCatalog = await publishStageDefinition(
    request,
    'e2e-stage-modification',
    401,
    '实现与验证',
    ['MODIFY_WORKSPACE'],
  );
  const root = createWorkspace(['stage-service']);
  const inspection = await inspectWorkspace(request, root);
  const title = '真实 Dynamic Stage 写入链路';
  const workbench = await createWorkbench(
    request,
    root,
    inspection,
    ['stage-service'],
    stageCatalog,
    'e2e-stage-modification',
    title,
  );
  await openWorkbench(page, workbench, title);
  await expect(page.getByTestId('workbench-run-mode-selector')).toContainText('修改工作区');

  const submittedResponse = page.waitForResponse(response =>
    response.request().method() === 'POST'
      && new URL(response.url()).pathname === stageRunsUrl(workbench));
  await page.getByTestId('workbench-run-composer')
    .fill('[E2E_STAGE_MODIFY_TEST] 按冻结 Stage 规则修改文件并执行受影响测试');
  await page.getByTestId('workbench-run-submit').click();
  const submissionHttp = await submittedResponse;
  expect(submissionHttp.status()).toBe(202);
  const submission = await submissionHttp.json() as RunSubmission;

  const liveTestResult = page.getByTestId('workbench-live-test-event')
    .filter({ hasText: 'runtime-test-command' });
  await expect(liveTestResult).toContainText('runtime-test-command', {
    timeout: 15_000,
  });
  await expect(liveTestResult).toContainText('PASSED');
  await expect(liveTestResult).toContainText('命令已完成');
  await expect(page.getByText('已按冻结 Stage 规则完成工作区修改和受影响测试。'))
    .toBeVisible();
  await expect(page.getByText('本轮运行成功')).toBeVisible();

  const changedFile = page.getByTestId('workbench-structured-document-reference')
    .filter({ hasText: 'stage-e2e.txt' });
  await expect(changedFile).toBeVisible();
  await changedFile.click();
  const documentDialog = page.getByRole('dialog', { name: 'stage-service/stage-e2e.txt' });
  await expect(documentDialog)
    .toContainText('Stage modification applied from the frozen Dynamic Stage.');

  const eventResponse = await request.get(
    `/api/workbenches/${encodeURIComponent(workbench.workbenchId)}`
      + `/runs/${encodeURIComponent(submission.runId)}/events-page?after=0&limit=200`,
  );
  await expectStatus(eventResponse, 200);
  const persisted = await eventResponse.json() as RunEventPage;
  const fileEvents = persisted.events
    .filter(event => event.eventType === 'file_changed')
    .map(event => eventData(event.payload));
  expect(fileEvents).toContainEqual(expect.objectContaining({
    repositoryKey: 'stage-service',
    path: 'stage-e2e.txt',
    changeType: 'ADDED',
  }));
  const testStatuses = persisted.events
    .filter(event => event.eventType === 'test_progress')
    .map(event => eventData(event.payload).status);
  expect(testStatuses).toEqual(['RUNNING', 'PASSED']);
  expect(persisted.events.map(event => event.eventType)).toContain('terminal');

  await page.reload();
  await expect(page.locator('.workbench-list-item.active')).toContainText(title);
  const restoredDocumentDialog = page.getByRole('dialog', {
    name: 'stage-service/stage-e2e.txt',
  });
  if (await restoredDocumentDialog.isVisible()) {
    await restoredDocumentDialog.getByRole('button', { name: 'Close' }).click();
    await expect(restoredDocumentDialog).toBeHidden();
  }
  await page.getByTestId('open-run-history').click();
  const recoveredTimeline = page.getByTestId('workbench-run-history-timeline');
  await expect(recoveredTimeline).toContainText('stage-service/stage-e2e.txt');
  await expect(recoveredTimeline).toContainText('runtime-test-command');
  await expect(recoveredTimeline).toContainText('PASSED');
  await expect(recoveredTimeline).toContainText('命令已完成');
  await expect(recoveredTimeline)
    .toContainText('已按冻结 Stage 规则完成工作区修改和受影响测试。');
  await expect(recoveredTimeline).toContainText('成功');
});
