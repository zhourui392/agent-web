import { expect, test, type Page, type Request, type Route } from '@playwright/test';

const WORKBENCH_ID = 'workbench-stage-e2e';
const OWNER_ID = 'owner-e2e';
const HASH_A = 'a'.repeat(64);
const HASH_B = 'b'.repeat(64);
const NOW = 1_786_000_000_000;

const STAGES = [
  {
    stageInstanceIdentifier: 'stage-analysis',
    definitionIdentifier: 'analysis',
    definitionRevision: 2,
    definitionHash: HASH_A,
    snapshotHash: HASH_B,
    sequenceNumber: 10,
    displayName: '需求分析',
    description: '澄清目标和约束',
    allowedRunModes: ['DISCUSS_READ_ONLY'],
  },
  {
    stageInstanceIdentifier: 'stage-implementation',
    definitionIdentifier: 'implementation',
    definitionRevision: 4,
    definitionHash: HASH_B,
    snapshotHash: HASH_A,
    sequenceNumber: 20,
    displayName: '开发测试',
    description: '实现并验证变更',
    allowedRunModes: ['DISCUSS_READ_ONLY', 'MODIFY_WORKSPACE'],
  },
] as const;

interface FixtureOptions {
  onRequest?: (request: Request) => void;
}

function detail() {
  return {
    id: WORKBENCH_ID,
    title: 'Stage-only Workbench',
    originalGoal: '验证 Dynamic Stage 单模型主链',
    agentType: 'CODEX',
    environment: 'test',
    activeWriteRunId: null,
    status: 'ACTIVE',
    createdAt: NOW,
    updatedAt: NOW,
    version: 7,
    repositoryScope: {
      scopeHash: HASH_A,
      primaryRepositoryKey: 'service-a',
      workspaceRoot: '/workspace/mock',
      repositories: [
        { repositoryKey: 'service-a', relativePath: 'service-a', primary: true },
        { repositoryKey: 'service-b', relativePath: 'service-b', primary: false },
      ],
    },
    creationSnapshot: {
      snapshotId: 'snapshot-e2e',
      topologyHash: HASH_A,
      stateHash: HASH_B,
      repositoryCount: 2,
    },
    stages: STAGES.map(stage => ({
      ...stage,
      status: 'IN_PROGRESS',
      conversationGeneration: 0,
      currentConversation: {
        sessionId: `session-${stage.stageInstanceIdentifier}`,
        generation: 0,
        createdAt: NOW,
        retiredAt: null,
      },
      conversationHistory: [],
      activeRun: null,
      lastActivityAt: null,
      completedAt: null,
    })),
  };
}

function listItem() {
  const workbench = detail();
  return {
    id: workbench.id,
    title: workbench.title,
    status: workbench.status,
    agentType: workbench.agentType,
    environment: workbench.environment,
    primaryRepositoryKey: workbench.repositoryScope.primaryRepositoryKey,
    repositoryCount: workbench.repositoryScope.repositories.length,
    activeWriteRunId: null,
    createdAt: workbench.createdAt,
    updatedAt: workbench.updatedAt,
    version: workbench.version,
  };
}

async function json(route: Route, body: unknown, status = 200): Promise<void> {
  await route.fulfill({
    status,
    contentType: 'application/json',
    body: JSON.stringify(body),
  });
}

function stageFromPath(pathname: string): string {
  const matched = pathname.match(/\/stages\/([^/]+)\//);
  return matched ? decodeURIComponent(matched[1]) : 'stage-analysis';
}

function eventEnvelope(
  stageInstanceIdentifier: string,
  runId: string,
  data: Record<string, unknown>,
): string {
  return JSON.stringify({
    schemaVersion: 'workbench-run-event@1',
    runId,
    workbenchId: WORKBENCH_ID,
    stageInstanceIdentifier,
    occurredAt: NOW,
    data,
  });
}

function sseFrame(
  sequence: number,
  type: string,
  payload: string,
): string {
  return `id: ${sequence}\nevent: ${type}\ndata: ${payload}\n\n`;
}

async function installFixture(
  page: Page,
  options: FixtureOptions = {},
): Promise<void> {
  let runSequence = 0;
  await page.route('**/api/**', async route => {
    const request = route.request();
    const url = new URL(request.url());
    const path = url.pathname;
    const method = request.method();
    if (!path.startsWith('/api/')) {
      await route.continue();
      return;
    }
    options.onRequest?.(request);

    if (path === '/api/auth/status') {
      await json(route, {
        authEnabled: true,
        authenticated: true,
        username: 'workbench-owner',
        userId: OWNER_ID,
        role: 'USER',
      });
      return;
    }
    if (path === '/api/workbenches' && method === 'GET') {
      await json(route, { items: [listItem()], nextCursor: null });
      return;
    }
    if (path === `/api/workbenches/${WORKBENCH_ID}` && method === 'GET') {
      await json(route, detail());
      return;
    }
    if (path === '/api/workbench/stage-definitions' && method === 'GET') {
      await json(route, {
        stageCatalogVersion: 9,
        stages: STAGES.map(stage => ({
          definitionIdentifier: stage.definitionIdentifier,
          publishedRevision: stage.definitionRevision,
          displayName: stage.displayName,
          description: stage.description,
          sequenceNumber: stage.sequenceNumber,
          definitionHash: stage.definitionHash,
        })),
      });
      return;
    }
    if (path === '/api/workbench/workspaces/inspect' && method === 'POST') {
      await json(route, {
        workspaceRootDisplay: '/workspace/mock',
        inspectionToken: 'inspection-token',
        source: 'LOCAL',
        repositories: [{
          repositoryKey: 'service-a',
          relativePath: 'service-a',
          branch: 'master',
          headShort: 'abc1234',
          clean: true,
          selectedByDefault: true,
          primarySuggested: true,
          warnings: [],
        }],
        warnings: [],
      });
      return;
    }
    if (path === '/api/workbenches' && method === 'POST') {
      await json(route, {
        workbenchId: WORKBENCH_ID,
        status: 'ACTIVE',
        version: 1,
        replayed: false,
      }, 201);
      return;
    }
    if (/\/stages\/[^/]+\/commands$/.test(path) && method === 'GET') {
      await json(route, []);
      return;
    }
    if (/\/stages\/[^/]+\/conversation\/messages$/.test(path) && method === 'GET') {
      const stageInstanceIdentifier = stageFromPath(path);
      await json(route, {
        sessionId: `session-${stageInstanceIdentifier}`,
        generation: 0,
        workbenchVersion: 7,
        messages: [],
        nextCursor: null,
      });
      return;
    }
    if (/\/stages\/[^/]+\/(complete|reopen)$/.test(path) && method === 'POST') {
      const stageInstanceIdentifier = stageFromPath(path);
      await json(route, {
        workbenchId: WORKBENCH_ID,
        stageInstanceIdentifier,
        definitionIdentifier: STAGES.find(
          stage => stage.stageInstanceIdentifier === stageInstanceIdentifier,
        )?.definitionIdentifier ?? 'analysis',
        stageStatus: path.endsWith('/complete') ? 'HUMAN_COMPLETED' : 'IN_PROGRESS',
        conversationId: `session-${stageInstanceIdentifier}`,
        conversationGeneration: 0,
        workbenchVersion: 8,
        changed: true,
      });
      return;
    }
    if (/\/stages\/[^/]+\/runs$/.test(path) && method === 'POST') {
      const stageInstanceIdentifier = stageFromPath(path);
      const runId = `run-submit-${++runSequence}`;
      await json(route, {
        runId,
        sessionId: `session-${stageInstanceIdentifier}`,
        status: 'PENDING',
        stageStatus: 'IN_PROGRESS',
        workbenchVersion: 7 + runSequence,
        capabilitySnapshotHash: HASH_A,
        repositoryScopeHash: HASH_B,
        replayed: false,
      }, 202);
      return;
    }
    if (/\/stages\/[^/]+\/attachments$/.test(path) && method === 'POST') {
      await json(route, {
        attachmentId: 'uploaded-e2e-1',
        displayName: 'architecture.png',
        mediaType: 'image/png',
        size: Math.max(1, request.postDataBuffer()?.byteLength ?? 1),
        sha256: HASH_B,
        expiresAt: new Date(NOW + 3_600_000).toISOString(),
      }, 201);
      return;
    }
    if (/\/stages\/[^/]+\/attachments\/[^/]+$/.test(path) && method === 'DELETE') {
      await route.fulfill({ status: 204 });
      return;
    }
    if (path.endsWith('/documents/tree') && method === 'GET') {
      await json(route, {
        repositoryKey: url.searchParams.get('repositoryKey'),
        path: url.searchParams.get('path') ?? '',
        entries: [{
          name: 'README.md', relativePath: 'README.md', kind: 'FILE',
          size: 128, lastModified: NOW,
        }],
        truncated: false,
      });
      return;
    }
    if (path.endsWith('/documents/content') && method === 'GET') {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        headers: { ETag: `"${HASH_A}"` },
        body: JSON.stringify({
          reference: {
            repositoryKey: url.searchParams.get('repositoryKey'),
            relativePath: url.searchParams.get('path'),
          },
          kind: 'MARKDOWN',
          mediaType: 'text/markdown',
          encoding: 'UTF-8',
          size: 16,
          lastModified: NOW,
          contentVersion: HASH_A,
          content: '# Safe Workbench',
          truncated: false,
          deleted: false,
        }),
      });
      return;
    }
    if (path === `/api/workbenches/${WORKBENCH_ID}/runs` && method === 'GET') {
      const stageInstanceIdentifier = url.searchParams.get('stageInstanceIdentifier')
        ?? 'stage-analysis';
      await json(route, {
        items: [{
          runId: 'run-history-1',
          workbenchId: WORKBENCH_ID,
          stageInstanceIdentifier,
          sessionId: `session-${stageInstanceIdentifier}`,
          status: 'SUCCEEDED',
          runMode: 'DISCUSS_READ_ONLY',
          lastEventSeq: 3,
          earliestRetainedSeq: 1,
          createdAt: NOW,
          startedAt: NOW + 10,
          finishedAt: NOW + 100,
          failureCode: null,
        }],
        nextCursor: null,
      });
      return;
    }
    if (path.endsWith('/runs/run-history-1') && method === 'GET') {
      await json(route, {
        runId: 'run-history-1',
        workbenchId: WORKBENCH_ID,
        stageInstanceIdentifier: 'stage-analysis',
        sessionId: 'session-stage-analysis',
        status: 'SUCCEEDED',
        runMode: 'DISCUSS_READ_ONLY',
        lastEventSeq: 3,
        earliestRetainedSeq: 1,
        createdAt: NOW,
        startedAt: NOW + 10,
        finishedAt: NOW + 100,
        failureCode: null,
      });
      return;
    }
    if (path.endsWith('/runs/run-history-1/events-page') && method === 'GET') {
      await json(route, {
        runId: 'run-history-1',
        after: 0,
        through: 3,
        lastEventSeq: 3,
        earliestRetainedSeq: 1,
        hasMore: false,
        events: [
          {
            sequence: 1,
            eventType: 'run_status',
            payload: eventEnvelope('stage-analysis', 'run-history-1', {
              status: 'RUNNING', runMode: 'DISCUSS_READ_ONLY',
            }),
          },
          {
            sequence: 2,
            eventType: 'agent_chunk',
            payload: eventEnvelope('stage-analysis', 'run-history-1', {
              content: 'Stage 历史输出',
            }),
          },
          {
            sequence: 3,
            eventType: 'terminal',
            payload: eventEnvelope('stage-analysis', 'run-history-1', {
              status: 'SUCCEEDED', failureCode: null, publicMessage: '历史完成',
            }),
          },
        ],
      });
      return;
    }
    if (path.endsWith('/runs/run-history-1/capability') && method === 'GET') {
      await json(route, {
        runId: 'run-history-1',
        workbenchId: WORKBENCH_ID,
        stageInstanceIdentifier: 'stage-analysis',
        runMode: 'DISCUSS_READ_ONLY',
        createdAt: NOW,
        policyVersion: 'workbench-policy@1',
        profileId: 'stage-analysis',
        profileVersion: '2',
        profileHash: HASH_A,
        bindingHash: HASH_B,
        runtimeCompatibility: 'codex-e2e',
        repositoryScopeHash: HASH_A,
        primaryRepositoryKey: 'service-a',
        repositories: [{
          repositoryKey: 'service-a', relativePath: 'service-a',
          primary: true, access: 'READ',
        }],
        rules: [], skills: [], mcpServers: [], rejected: [],
      });
      return;
    }
    if (/\/runs\/[^/]+\/events$/.test(path) && method === 'GET') {
      const runId = path.split('/').at(-2) ?? 'run-unknown';
      const stageInstanceIdentifier = runId.endsWith('2')
        ? 'stage-implementation' : 'stage-analysis';
      await route.fulfill({
        status: 200,
        contentType: 'text/event-stream',
        body: sseFrame(1, 'terminal', eventEnvelope(
          stageInstanceIdentifier,
          runId,
          { status: 'SUCCEEDED', failureCode: null, publicMessage: 'fixture complete' },
        )),
      });
      return;
    }
    if (/\/runs\/[^/]+\/stop$/.test(path) && method === 'POST') {
      await json(route, {
        runId: path.split('/').at(-2), status: 'CANCEL_REQUESTED',
      }, 202);
      return;
    }

    await json(route, { code: 'WORKBENCH_REQUEST_FAILED', message: 'safe failure' }, 404);
  });
}

async function openWorkbench(page: Page): Promise<void> {
  await page.goto(`/workbench.html?id=${WORKBENCH_ID}`);
  await expect(page.locator('.workbench-list-item.active'))
    .toContainText('Stage-only Workbench');
  await expect(page.locator('.workbench-stage.active')).toContainText('需求分析');
}

test('Stage 导航和本地恢复只使用冻结实例身份', async ({ page }) => {
  const paths: string[] = [];
  await installFixture(page, {
    onRequest: request => paths.push(new URL(request.url()).pathname),
  });
  await openWorkbench(page);

  await page.getByRole('button', { name: /开发测试/ }).click();
  await expect(page.locator('.workbench-stage.active')).toContainText('开发测试');
  await page.reload();
  await expect(page.locator('.workbench-stage.active')).toContainText('开发测试');

  const storedKeys = await page.evaluate(() => Object.keys(localStorage));
  expect(storedKeys.some(key => key.startsWith('agent-web:workbench-stage-shell:')))
    .toBe(true);
  expect(paths.some(path => path.includes('/phases/'))).toBe(false);
});

test('Stage 冻结 Run Mode 决定提交模式，双模式要求显式选择', async ({ page }) => {
  const submissions: Array<{ path: string; body: unknown }> = [];
  await installFixture(page, {
    onRequest(request) {
      const path = new URL(request.url()).pathname;
      if (request.method() === 'POST' && path.endsWith('/runs')) {
        submissions.push({ path, body: request.postDataJSON() });
      }
    },
  });
  await openWorkbench(page);

  const composer = page.getByTestId('workbench-run-composer');
  await composer.fill('先分析需求');
  await expect(page.getByTestId('workbench-run-mode-selector')).toContainText('只读讨论');
  await page.getByTestId('workbench-run-submit').click();
  await expect.poll(() => submissions.length).toBe(1);
  expect(submissions[0]).toEqual({
    path: `/api/workbenches/${WORKBENCH_ID}/stages/stage-analysis/runs`,
    body: { message: '先分析需求', runMode: 'DISCUSS_READ_ONLY' },
  });

  await page.getByRole('button', { name: /开发测试/ }).click();
  await composer.fill('实施并测试');
  await expect(page.getByTestId('workbench-run-submit')).toBeDisabled();
  await page.getByTestId('workbench-run-mode-selector')
    .getByText('请选择运行模式', { exact: true }).click();
  await page.getByRole('option', { name: '修改工作区' }).click();
  await expect(page.getByTestId('workbench-run-submit')).toBeEnabled();
  await page.getByTestId('workbench-run-submit').click();
  await expect.poll(() => submissions.length).toBe(2);
  expect(submissions[1]).toEqual({
    path: `/api/workbenches/${WORKBENCH_ID}/stages/stage-implementation/runs`,
    body: { message: '实施并测试', runMode: 'MODIFY_WORKSPACE' },
  });
});

test('Stage 附件与历史 Run 都保持精确实例绑定', async ({ page }) => {
  const requests: Request[] = [];
  let detailLoads = 0;
  await installFixture(page, {
    onRequest(request) {
      const path = new URL(request.url()).pathname;
      if (request.method() === 'GET' && path === `/api/workbenches/${WORKBENCH_ID}`) {
        detailLoads++;
      }
      if (path.includes('/attachments') || path.endsWith('/runs')) {
        requests.push(request);
      }
    },
  });
  await openWorkbench(page);

  await page.getByRole('button', { name: /README\.md/ }).click();
  const documentDialog = page.getByRole('dialog', { name: 'service-a/README.md' });
  await expect(page.getByTestId('workbench-attach-document')).toBeEnabled();
  await page.getByTestId('workbench-attach-document').click();
  await documentDialog.getByRole('button', { name: 'Close' }).click();
  await expect(documentDialog).toBeHidden();
  await page.getByTestId('workbench-upload-image-input').setInputFiles({
    name: 'architecture.png',
    mimeType: 'image/png',
    buffer: Buffer.from('safe image fixture'),
  });
  await expect(page.getByTestId('workbench-upload-item')).toContainText('待发送');
  await page.getByTestId('workbench-run-composer').fill('结合附件分析');
  await page.getByTestId('workbench-run-submit').click();

  await expect.poll(() => requests.filter(request => request.method() === 'POST').length)
    .toBeGreaterThanOrEqual(2);
  const upload = requests.find(request => new URL(request.url()).pathname.endsWith('/attachments'));
  expect(new URL(upload!.url()).pathname).toBe(
    `/api/workbenches/${WORKBENCH_ID}/stages/stage-analysis/attachments`,
  );
  const submitted = requests.find(request => new URL(request.url()).pathname.endsWith('/runs'));
  expect(submitted!.postDataJSON()).toEqual({
    message: '结合附件分析',
    runMode: 'DISCUSS_READ_ONLY',
    attachments: [
      {
        repositoryKey: 'service-a', relativePath: 'README.md', contentHash: HASH_A,
      },
      {
        type: 'UPLOADED_CONVERSATION', attachmentId: 'uploaded-e2e-1', contentHash: HASH_B,
      },
    ],
  });

  await expect.poll(() => detailLoads).toBeGreaterThanOrEqual(2);
  await documentDialog.getByRole('button', { name: 'Close' }).click();
  await expect(documentDialog).toBeHidden();
  await page.getByTestId('open-run-history').click();
  await expect(page.getByTestId('workbench-run-history-list')).toContainText('run-history-1');
  await expect(page.getByTestId('workbench-run-history-timeline')).toContainText('Stage 历史输出');
  await expect(page.getByTestId('workbench-run-history-capability')).toContainText('stage-analysis');
  const historyRequest = requests.find(request => {
    const url = new URL(request.url());
    return request.method() === 'GET' && url.pathname.endsWith('/runs')
      && url.searchParams.has('stageInstanceIdentifier');
  });
  expect(new URL(historyRequest!.url()).searchParams.get('stageInstanceIdentifier'))
    .toBe('stage-analysis');
});

test('创建 Workbench 只提交已发布 Stage Definition 身份', async ({ page }) => {
  let creationBody: Record<string, unknown> | null = null;
  await installFixture(page, {
    onRequest(request) {
      const path = new URL(request.url()).pathname;
      if (path === '/api/workbenches' && request.method() === 'POST') {
        creationBody = request.postDataJSON() as Record<string, unknown>;
      }
    },
  });
  await openWorkbench(page);

  await page.getByRole('button', { name: '新建', exact: true }).click();
  const dialog = page.getByRole('dialog', { name: '创建 Workbench' });
  await expect(dialog.getByText('需求分析')).toBeVisible();
  await expect(dialog.getByText('开发测试')).toBeVisible();
  await dialog.locator('.workbench-field').filter({ hasText: 'Workspace Root' })
    .locator('input').fill('/workspace/mock');
  await dialog.getByRole('button', { name: '检查' }).click();
  await expect(dialog.getByRole('checkbox', { name: /service-a/ })).toBeChecked();
  await dialog.locator('.workbench-field').filter({ hasText: '标题' })
    .locator('input').fill('New Stage Workbench');
  await dialog.locator('.workbench-field').filter({ hasText: '原始目标' })
    .locator('textarea').fill('Use selected dynamic stages');
  await dialog.getByRole('button', { name: '创建 Workbench' }).click();

  await expect.poll(() => creationBody).not.toBeNull();
  expect(creationBody).toMatchObject({
    stageDefinitionIdentifiers: ['analysis', 'implementation'],
    expectedStageCatalogVersion: 9,
  });
  expect(JSON.stringify(creationBody)).not.toMatch(/phase|handoff|review/i);
});
