import { createHash } from 'crypto';
import { expect, test, type Page, type Request, type Route } from '@playwright/test';

const WORKBENCH_ID = 'wb-e2e';
const OWNER_ID = 'owner-e2e';
const HASH = 'a'.repeat(64);
const SECOND_HASH = 'b'.repeat(64);
const NOW = 1_786_000_000_000;
const PHASES = [
  'REQUIREMENT_ANALYSIS',
  'SOLUTION_DESIGN',
  'IMPLEMENT_TEST',
  'REVIEW_REFACTOR',
] as const;
type Phase = typeof PHASES[number];

type FixtureOptions = {
  ownerId?: string;
  archived?: boolean;
  activeRun?: { phase: Phase; runId: string; runMode?: 'DISCUSS_READ_ONLY' | 'MODIFY_WORKSPACE' };
  noRuns?: boolean;
  onApiRequest?: (request: Request) => void;
  onSse?: (route: Route, request: Request) => Promise<void>;
  documentTreeRepositoryKey?: string;
  documentTreeFailure?: boolean;
  documentContent?: string;
  uploadFailures?: number;
};

function phaseLabel(phase: Phase): string {
  return {
    REQUIREMENT_ANALYSIS: '需求分析',
    SOLUTION_DESIGN: '技术方案设计',
    IMPLEMENT_TEST: '开发部署测试',
    REVIEW_REFACTOR: '人工 Review、重构与测试',
  }[phase];
}

function previousPhase(phase: Phase): Phase | null {
  const index = PHASES.indexOf(phase);
  return index > 0 ? PHASES[index - 1] : null;
}

function workbenchDetail(options: FixtureOptions = {}) {
  const activeRun = options.activeRun;
  return {
    id: WORKBENCH_ID,
    title: 'Workbench 浏览器契约',
    originalGoal: '验证四阶段、恢复、Review 与高影响操作安全边界',
    agentType: 'CODEX',
    environment: 'test',
    activeWriteRunId: activeRun?.runMode === 'MODIFY_WORKSPACE' ? activeRun.runId : null,
    status: options.archived ? 'ARCHIVED' : 'ACTIVE',
    createdAt: NOW,
    updatedAt: NOW,
    version: 7,
    repositoryScope: {
      scopeHash: HASH,
      primaryRepositoryKey: 'service-a',
      repositories: [
        { repositoryKey: 'service-a', relativePath: 'service-a', primary: true },
        { repositoryKey: 'service-b', relativePath: 'service-b', primary: false },
      ],
    },
    creationSnapshot: {
      snapshotId: 'snapshot-e2e',
      topologyHash: HASH,
      stateHash: SECOND_HASH,
      repositoryCount: 2,
    },
    phases: PHASES.map((phase, index) => ({
      phase,
      phaseOrder: index,
      status: phase === activeRun?.phase ? 'IN_PROGRESS' : 'NOT_STARTED',
      conversationGeneration: 0,
      currentConversation: phase === activeRun?.phase
        ? { sessionId: `session-${phase}`, generation: 0 }
        : null,
      conversationHistory: [],
      activeRun: phase === activeRun?.phase ? {
        runId: activeRun.runId,
        runMode: activeRun.runMode ?? 'DISCUSS_READ_ONLY',
        preparedAt: NOW,
        reviewConfirmationId: null,
        reviewOpinionVersion: null,
        reviewOpinionHash: null,
      } : null,
      lastActivityAt: null,
      completedAt: null,
    })),
  };
}

function listItem(options: FixtureOptions = {}) {
  const detail = workbenchDetail(options);
  return {
    id: detail.id,
    title: detail.title,
    status: detail.status,
    agentType: detail.agentType,
    environment: detail.environment,
    primaryRepositoryKey: detail.repositoryScope.primaryRepositoryKey,
    repositoryCount: detail.repositoryScope.repositories.length,
    activeWriteRunId: detail.activeWriteRunId,
    createdAt: detail.createdAt,
    updatedAt: detail.updatedAt,
    version: detail.version,
  };
}

function handoff(phase: Phase) {
  return {
    sourcePhase: phase,
    summary: `${phaseLabel(phase)}交接`,
    decisions: [],
    openQuestions: [],
    pinnedFiles: [],
    referencedRuns: [],
    version: 1,
    contentHash: HASH,
    updatedAt: NOW,
    readOnly: false,
  };
}

function handoffSource(targetPhase: Phase) {
  const sourcePhase = previousPhase(targetPhase);
  if (!sourcePhase) {
    return {
      targetPhase,
      latestSource: null,
      reception: null,
      acceptedSource: null,
      stale: false,
      diff: null,
    };
  }
  const source = handoff(sourcePhase);
  return {
    targetPhase,
    latestSource: source,
    reception: {
      sourcePhase,
      sourceVersion: source.version,
      sourceHash: source.contentHash,
      acceptedAt: NOW,
    },
    acceptedSource: source,
    stale: false,
    diff: null,
  };
}

function effectiveCapabilityProfile(phase: Phase) {
  return {
    phase,
    status: 'AVAILABLE',
    profileId: `workbench-${phase.toLowerCase().replaceAll('_', '-')}`,
    profileVersion: '1.0.0',
    profileHash: HASH,
    rules: [{
      id: 'platform/workbench-safety',
      required: true,
      selected: true,
      source: 'PHASE_PROFILE',
      summary: '平台安全边界',
      access: null,
    }],
    skills: [{
      id: phase.toLowerCase().replaceAll('_', '-'),
      required: false,
      selected: true,
      source: 'PHASE_PROFILE',
      summary: `${phaseLabel(phase)}默认能力`,
      access: null,
    }],
    mcpServers: [{
      id: 'repository-query',
      required: false,
      selected: true,
      source: 'MCP_CATALOG',
      summary: '仓库只读查询',
      access: 'READ',
    }],
    optionalSkillIds: [phase.toLowerCase().replaceAll('_', '-')],
    optionalMcpServerIds: ['repository-query'],
    additionalRule: '',
    overrideVersion: 0,
    warnings: [],
    effectiveFrom: 'NEXT_RUN',
    activeRunSnapshotHash: null,
  };
}

function json(route: Route, body: unknown, status = 200): Promise<void> {
  return route.fulfill({
    status,
    contentType: 'application/json',
    body: JSON.stringify(body),
  });
}

function phaseFromUrl(url: URL): Phase | null {
  return PHASES.find(phase => url.pathname.includes(`/phases/${phase}/`)) ?? null;
}

function sseEnvelope(phase: Phase, runId: string, data: Record<string, unknown>) {
  return JSON.stringify({
    schemaVersion: 'workbench-run-event@1',
    runId,
    workbenchId: WORKBENCH_ID,
    phase,
    occurredAt: NOW,
    data,
  });
}

function frame(id: number, event: string, data: string): string {
  return `id: ${id}\nevent: ${event}\ndata: ${data}\n\n`;
}

function multipartFileName(request: Request): string {
  const body = request.postDataBuffer()?.toString('utf8') ?? '';
  const match = /filename="([^"\r\n]+)"/.exec(body);
  return match?.[1]?.trim() || 'attachment.txt';
}

async function installWorkbenchFixture(page: Page, options: FixtureOptions = {}): Promise<void> {
  let opinion: null | {
    phase: 'REVIEW_REFACTOR';
    version: number;
    content: string;
    contentHash: string;
    reviewedAt: number;
    readOnly: boolean;
  } = null;
  let confirmation: null | {
    confirmationId: string;
    phase: 'REVIEW_REFACTOR';
    opinionVersion: number;
    opinionHash: string;
    confirmedAt: number;
    readOnly: boolean;
  } = null;
  let runSequence = 0;
  let attachmentSequence = 0;
  let remainingUploadFailures = options.uploadFailures ?? 0;
  const submittedRunPhases = new Map<string, Phase>();
  const ensuredConversations = new Map<Phase, {
    sessionId: string;
    workbenchVersion: number;
  }>();

  await page.route('**/api/**', async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    if (!url.pathname.startsWith('/api/')) {
      await route.fallback();
      return;
    }
    const method = request.method();
    options.onApiRequest?.(request);

    if (url.pathname === '/api/auth/status') {
      await json(route, {
        authEnabled: true,
        authenticated: true,
        username: 'workbench-owner',
        userId: options.ownerId === undefined ? OWNER_ID : options.ownerId,
        role: 'USER',
      });
      return;
    }
    if (url.pathname === '/api/workbenches' && method === 'GET') {
      await json(route, { items: [listItem(options)], nextCursor: null });
      return;
    }
    if (url.pathname === `/api/workbenches/${WORKBENCH_ID}` && method === 'GET') {
      await json(route, workbenchDetail(options));
      return;
    }
    if (url.pathname.endsWith('/handoff') && method === 'GET') {
      await json(route, { code: 'WORKBENCH_HANDOFF_NOT_FOUND' }, 404);
      return;
    }
    if (url.pathname.endsWith('/handoff-source') && method === 'GET') {
      const phase = phaseFromUrl(url);
      await json(route, handoffSource(phase ?? 'REQUIREMENT_ANALYSIS'));
      return;
    }
    if (url.pathname.endsWith('/capability-profile') && method === 'GET') {
      const phase = phaseFromUrl(url) ?? 'REQUIREMENT_ANALYSIS';
      await json(route, effectiveCapabilityProfile(phase));
      return;
    }
    if (url.pathname.endsWith('/conversation/messages') && method === 'GET') {
      const phase = phaseFromUrl(url) ?? 'REQUIREMENT_ANALYSIS';
      const ensured = ensuredConversations.get(phase);
      const activeSession = options.activeRun?.phase === phase
        ? `session-${phase}` : null;
      await json(route, {
        sessionId: ensured?.sessionId ?? activeSession,
        generation: 0,
        workbenchVersion: ensured?.workbenchVersion ?? 7,
        messages: [],
        nextCursor: null,
      });
      return;
    }
    if (url.pathname.endsWith('/conversation') && method === 'POST') {
      const phase = phaseFromUrl(url) ?? 'REQUIREMENT_ANALYSIS';
      const expectedVersion = Number(request.headers()['if-match']);
      const existing = ensuredConversations.get(phase);
      const ensured = existing ?? {
        sessionId: `session-ensure-${phase}`,
        workbenchVersion: expectedVersion + 1,
      };
      ensuredConversations.set(phase, ensured);
      await json(route, {
        sessionId: ensured.sessionId,
        generation: 0,
        workbenchVersion: ensured.workbenchVersion,
        created: existing == null,
      });
      return;
    }
    if (url.pathname.endsWith('/complete') && method === 'POST') {
      const phase = phaseFromUrl(url) ?? 'REQUIREMENT_ANALYSIS';
      await json(route, {
        workbenchId: WORKBENCH_ID,
        phase,
        phaseStatus: 'HUMAN_COMPLETED',
        conversationId: null,
        conversationGeneration: 0,
        workbenchVersion: 8,
        changed: true,
      });
      return;
    }
    if (url.pathname.endsWith('/review-candidates') && method === 'POST') {
      await json(route, {
        phase: 'REVIEW_REFACTOR',
        baseOpinionVersion: opinion?.version ?? 0,
        conversationGeneration: 0,
        sourceMessageCount: 2,
        strategy: 'DETERMINISTIC_PUBLIC_REVIEW_MESSAGES_V1',
        items: [{
          itemId: HASH,
          finding: 'Application 层代替聚合做业务判断',
          impact: '规则会随入口漂移',
          suggestedChange: '下沉领域策略',
          affectedFiles: [{
            repositoryKey: 'service-a',
            relativePath: 'src/main/java/OrderService.java',
          }],
          suggestedTests: ['运行 Order 聚合单测'],
        }, {
          itemId: SECOND_HASH,
          finding: '扩大了无关重构范围',
          impact: '增加回归风险',
          suggestedChange: '限制在订单子域',
          affectedFiles: [],
          suggestedTests: [],
        }],
      });
      return;
    }
    if (url.pathname.endsWith('/review-opinion') && method === 'GET') {
      await json(route, opinion ?? { code: 'WORKBENCH_REVIEW_OPINION_NOT_FOUND' }, opinion ? 200 : 404);
      return;
    }
    if (url.pathname.endsWith('/review-confirmation') && method === 'GET') {
      await json(
        route,
        confirmation ?? { code: 'WORKBENCH_REVIEW_CONFIRMATION_NOT_FOUND' },
        confirmation ? 200 : 404,
      );
      return;
    }
    if (url.pathname.endsWith('/review-opinion') && method === 'PUT') {
      const body = request.postDataJSON() as { content: string };
      const content = body.content.trim();
      opinion = {
        phase: 'REVIEW_REFACTOR',
        version: 1,
        content,
        contentHash: createHash('sha256').update(content).digest('hex'),
        reviewedAt: NOW,
        readOnly: false,
      };
      confirmation = null;
      await json(route, opinion);
      return;
    }
    if (url.pathname.endsWith('/review-confirmation') && method === 'POST') {
      const body = request.postDataJSON() as { opinionVersion: number; opinionHash: string };
      confirmation = {
        confirmationId: 'confirmation-e2e',
        phase: 'REVIEW_REFACTOR',
        opinionVersion: body.opinionVersion,
        opinionHash: body.opinionHash,
        confirmedAt: NOW,
        readOnly: false,
      };
      await json(route, confirmation, 201);
      return;
    }
    if (url.pathname.endsWith('/documents/tree') && method === 'GET') {
      if (options.documentTreeFailure) {
        await json(route, {
          code: 'WORKBENCH_PATH_FORBIDDEN',
          message: 'token=server-secret /home/private/should-never-render',
          token: 'server-secret',
        }, 403);
        return;
      }
      const repositoryKey = options.documentTreeRepositoryKey ?? url.searchParams.get('repositoryKey') ?? '';
      await json(route, {
        repositoryKey,
        path: url.searchParams.get('path') ?? '',
        entries: [{
          name: 'README.md',
          relativePath: 'README.md',
          kind: 'FILE',
          size: 128,
          lastModified: NOW,
        }],
        truncated: false,
      });
      return;
    }
    if (url.pathname.endsWith('/documents/content') && method === 'GET') {
      const content = options.documentContent ?? '# Safe Workbench';
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        headers: { ETag: `"${HASH}"` },
        body: JSON.stringify({
          reference: {
            repositoryKey: url.searchParams.get('repositoryKey'),
            relativePath: url.searchParams.get('path'),
          },
          kind: 'MARKDOWN',
          mediaType: 'text/markdown',
          encoding: 'UTF-8',
          size: new TextEncoder().encode(content).byteLength,
          lastModified: NOW,
          contentVersion: HASH,
          content,
          truncated: false,
          deleted: false,
        }),
      });
      return;
    }
    if (/\/phases\/[^/]+\/attachments$/.test(url.pathname) && method === 'POST') {
      attachmentSequence++;
      if (remainingUploadFailures > 0) {
        remainingUploadFailures--;
        await json(route, {
          code: 'WORKBENCH_ATTACHMENT_STORAGE_UNAVAILABLE',
          message: 'token=server-secret /home/private/should-never-render',
        }, 503);
        return;
      }
      const displayName = multipartFileName(request);
      await json(route, {
        attachmentId: `uploaded-e2e-${attachmentSequence}`,
        displayName,
        mediaType: displayName.toLowerCase().endsWith('.png') ? 'image/png' : 'text/plain',
        size: Math.max(1, request.postDataBuffer()?.byteLength ?? 1),
        sha256: SECOND_HASH,
        expiresAt: new Date(NOW + 3_600_000).toISOString(),
      }, 201);
      return;
    }
    if (/\/phases\/[^/]+\/attachments\/[^/]+$/.test(url.pathname) && method === 'DELETE') {
      await route.fulfill({ status: 204 });
      return;
    }
    if (url.pathname === `/api/workbenches/${WORKBENCH_ID}/runs` && method === 'GET') {
      const phase = (url.searchParams.get('phase') as Phase | null) ?? 'REQUIREMENT_ANALYSIS';
      await json(route, {
        items: options.noRuns ? [] : [{
          runId: 'run-history-e2e',
          workbenchId: WORKBENCH_ID,
          phase,
          sessionId: `session-history-${phase}`,
          status: 'SUCCEEDED',
          runMode: phase === 'IMPLEMENT_TEST' ? 'MODIFY_WORKSPACE' : 'DISCUSS_READ_ONLY',
          lastEventSeq: 3,
          earliestRetainedSeq: 1,
          createdAt: NOW,
          startedAt: NOW + 10,
          finishedAt: NOW + 210,
          failureCode: null,
        }],
        nextCursor: null,
      });
      return;
    }
    if (url.pathname.endsWith('/runs/run-history-e2e') && method === 'GET') {
      await json(route, {
        runId: 'run-history-e2e',
        workbenchId: WORKBENCH_ID,
        phase: 'REQUIREMENT_ANALYSIS',
        sessionId: 'session-history-REQUIREMENT_ANALYSIS',
        status: 'SUCCEEDED',
        runMode: 'DISCUSS_READ_ONLY',
        lastEventSeq: 3,
        earliestRetainedSeq: 1,
        createdAt: NOW,
        startedAt: NOW + 10,
        finishedAt: NOW + 210,
        failureCode: null,
      });
      return;
    }
    if (url.pathname.endsWith('/runs/run-history-e2e/events-page') && method === 'GET') {
      const phase: Phase = 'REQUIREMENT_ANALYSIS';
      await json(route, {
        runId: 'run-history-e2e',
        after: 0,
        through: 3,
        lastEventSeq: 3,
        earliestRetainedSeq: 1,
        hasMore: false,
        events: [
          {
            sequence: 1,
            eventType: 'run_status',
            payload: sseEnvelope(phase, 'run-history-e2e', {
              status: 'RUNNING', runMode: 'DISCUSS_READ_ONLY',
            }),
          },
          {
            sequence: 2,
            eventType: 'agent_chunk',
            payload: sseEnvelope(phase, 'run-history-e2e', { content: '已恢复的历史 Agent 输出' }),
          },
          {
            sequence: 3,
            eventType: 'terminal',
            payload: sseEnvelope(phase, 'run-history-e2e', {
              status: 'SUCCEEDED', failureCode: null, publicMessage: '历史运行完成',
            }),
          },
        ],
      });
      return;
    }
    if (url.pathname.endsWith('/runs/run-history-e2e/capability') && method === 'GET') {
      await json(route, {
        runId: 'run-history-e2e',
        workbenchId: WORKBENCH_ID,
        phase: 'REQUIREMENT_ANALYSIS',
        runMode: 'DISCUSS_READ_ONLY',
        createdAt: NOW,
        overrideVersion: 0,
        policyVersion: 'workbench-policy@1',
        profileId: 'workbench-requirement-analysis',
        profileVersion: '1.0.0',
        profileHash: HASH,
        bindingHash: SECOND_HASH,
        runtimeCompatibility: 'm0-2026-07-22',
        repositoryScopeHash: HASH,
        primaryRepositoryKey: 'service-a',
        repositories: [
          {
            repositoryKey: 'service-a',
            relativePath: 'service-a',
            primary: true,
            access: 'READ',
          },
          {
            repositoryKey: 'service-b',
            relativePath: 'service-b',
            primary: false,
            access: 'READ',
          },
        ],
        rules: [{
          id: 'platform/workbench-safety', version: '1.0.0', source: 'PLATFORM',
          contentHash: HASH, mandatory: true, safeSummary: '平台安全边界',
        }],
        skills: [{
          id: 'requirement-analysis', version: '1.0.0', source: 'PLATFORM',
          packageHash: SECOND_HASH, trustTier: 'PLATFORM',
        }],
        mcpServers: [],
        rejected: [],
      });
      return;
    }
    if (/\/phases\/[^/]+\/runs$/.test(url.pathname) && method === 'POST') {
      const phase = phaseFromUrl(url) ?? 'REQUIREMENT_ANALYSIS';
      const runId = `run-submit-${++runSequence}`;
      submittedRunPhases.set(runId, phase);
      await json(route, {
        runId,
        sessionId: `session-submit-${runSequence}`,
        status: 'PENDING',
        phaseStatus: 'IN_PROGRESS',
        workbenchVersion: 7 + runSequence,
        capabilitySnapshotHash: HASH,
        repositoryScopeHash: SECOND_HASH,
        replayed: false,
      }, 202);
      return;
    }
    if (url.pathname.endsWith('/events') && options.onSse) {
      await options.onSse(route, request);
      return;
    }
    if (url.pathname.endsWith('/events')) {
      const runId = url.pathname.split('/').at(-2) ?? 'run-unknown';
      const phase = submittedRunPhases.get(runId)
        ?? options.activeRun?.phase
        ?? 'REQUIREMENT_ANALYSIS';
      await route.fulfill({
        status: 200,
        contentType: 'text/event-stream',
        body: frame(1, 'terminal', sseEnvelope(phase, runId, {
          status: 'SUCCEEDED', failureCode: null, publicMessage: 'fixture complete',
        })),
      });
      return;
    }
    if (url.pathname.endsWith('/stop') && method === 'POST') {
      const runId = url.pathname.split('/').at(-2) ?? '';
      await json(route, { runId, status: 'CANCEL_REQUESTED' }, 202);
      return;
    }

    await json(route, {
      code: 'WORKBENCH_REQUEST_FAILED',
      message: 'token=server-secret /home/private/should-never-render',
    }, 500);
  });
}

async function gotoWorkbench(page: Page): Promise<void> {
  await page.goto(`/workbench.html?id=${WORKBENCH_ID}`);
  await expect(page.getByRole('heading', { name: 'Workbench 浏览器契约' })).toBeVisible();
}

test('四阶段对话保持主区域，文档收起占满且拖拽宽度刷新恢复', async ({ page }) => {
  await installWorkbenchFixture(page);
  await gotoWorkbench(page);

  const workarea = page.locator('.workbench-workarea');
  const conversation = page.locator('.workbench-conversation-panel');
  const documentPane = page.locator('.workbench-document-panel');

  for (const phase of PHASES) {
    await page.getByRole('button', { name: new RegExp(phaseLabel(phase)) }).click();
    await expect(page.getByRole('heading', {
      name: phaseLabel(phase),
      exact: true,
      level: 2,
    })).toBeVisible();
    await expect(conversation).toBeVisible();
    await expect(documentPane).toBeVisible();
    const [workareaBox, conversationBox, documentBox] = await Promise.all([
      workarea.boundingBox(),
      conversation.boundingBox(),
      documentPane.boundingBox(),
    ]);
    expect(workareaBox).not.toBeNull();
    expect(conversationBox).not.toBeNull();
    expect(documentBox).not.toBeNull();
    expect(conversationBox!.width).toBeGreaterThan(documentBox!.width);
    expect(conversationBox!.width).toBeGreaterThan(workareaBox!.width / 2);
  }

  const normalConversationWidth = (await conversation.boundingBox())!.width;
  await documentPane.getByRole('button', { name: '收起', exact: true }).click();
  await expect(documentPane).toHaveCount(0);
  await expect(page.getByRole('separator', {
    name: '调整文档区宽度，双击恢复默认宽度',
  })).toHaveCount(0);
  await expect.poll(async () => {
    const [workareaBox, conversationBox] = await Promise.all([
      workarea.boundingBox(),
      conversation.boundingBox(),
    ]);
    if (!workareaBox || !conversationBox) return false;
    return conversationBox.width >= workareaBox.width - 3;
  }).toBe(true);
  expect((await conversation.boundingBox())!.width)
    .toBeGreaterThan(normalConversationWidth);

  await conversation.getByRole('button', { name: '恢复文档区', exact: true }).click();
  const separator = page.getByRole('separator', {
    name: '调整文档区宽度，双击恢复默认宽度',
  });
  await expect(separator).toBeVisible();
  await separator.scrollIntoViewIfNeeded();
  const [workareaBox, separatorBox] = await Promise.all([
    workarea.boundingBox(),
    separator.boundingBox(),
  ]);
  expect(workareaBox).not.toBeNull();
  expect(separatorBox).not.toBeNull();
  const targetX = workareaBox!.x + workareaBox!.width * 0.5;
  const pointerY = separatorBox!.y + separatorBox!.height * 0.5;
  await page.mouse.move(
    separatorBox!.x + separatorBox!.width * 0.5,
    pointerY,
  );
  await page.mouse.down();
  await page.mouse.move(targetX, pointerY, { steps: 10 });
  await page.mouse.up();

  await expect.poll(async () => {
    const [currentWorkarea, currentDocument] = await Promise.all([
      workarea.boundingBox(),
      documentPane.boundingBox(),
    ]);
    if (!currentWorkarea || !currentDocument) return 0;
    return currentDocument.width / currentWorkarea.width;
  }).toBeGreaterThan(0.45);
  const resizedDocumentWidth = (await documentPane.boundingBox())!.width;

  await page.reload();
  await expect(page.getByRole('heading', {
    name: phaseLabel('REVIEW_REFACTOR'),
    exact: true,
    level: 2,
  })).toBeVisible();
  await expect(documentPane).toBeVisible();
  await expect.poll(async () => {
    const restored = await documentPane.boundingBox();
    return restored ? Math.abs(restored.width - resizedDocumentWidth) : Number.POSITIVE_INFINITY;
  }).toBeLessThanOrEqual(3);
});

test('四阶段可任意导航，Owner 缺失时 fail-closed', async ({ page }) => {
  const submitted: Array<{ phase: Phase; body: Record<string, unknown>; headers: Record<string, string> }> = [];
  await installWorkbenchFixture(page, {
    onApiRequest(request) {
      const url = new URL(request.url());
      if (/\/phases\/[^/]+\/runs$/.test(url.pathname) && request.method() === 'POST') {
        submitted.push({
          phase: phaseFromUrl(url)!,
          body: request.postDataJSON() as Record<string, unknown>,
          headers: request.headers(),
        });
      }
    },
  });
  await gotoWorkbench(page);

  for (const phase of PHASES) {
    await page.getByRole('button', { name: new RegExp(phaseLabel(phase)) }).click();
    await expect(page.getByRole('heading', { name: phaseLabel(phase), exact: true, level: 2 })).toBeVisible();
  }

  await page.getByRole('button', { name: /需求分析/ }).click();
  const composer = page.getByTestId('workbench-run-composer');
  await composer.fill('解释当前需求边界');
  await page.getByTestId('workbench-run-submit').click();
  await expect.poll(() => submitted.length).toBe(1);
  expect(submitted[0].phase).toBe('REQUIREMENT_ANALYSIS');
  expect(submitted[0].body).toEqual({ message: '解释当前需求边界', runMode: 'MODIFY_WORKSPACE' });
  expect(submitted[0].headers['if-match']).toBe('8');
  expect(submitted[0].headers['idempotency-key']).toBeTruthy();

  await page.getByRole('button', { name: /技术方案设计/ }).click();
  await page.getByTestId('workbench-run-composer').fill('解释方案取舍');
  await page.getByTestId('workbench-run-submit').click();
  await expect.poll(() => submitted.length).toBe(2);
  expect(submitted[1].body).toEqual({
    message: '解释方案取舍',
    runMode: 'MODIFY_WORKSPACE',
    handoffSourceVersion: 1,
  });

  await page.getByRole('button', { name: /开发部署测试/ }).click();
  await page.getByTestId('workbench-run-composer').fill('修改两个仓库并运行测试');
  await page.getByTestId('workbench-run-submit').click();
  await expect.poll(() => submitted.length).toBe(3);
  expect(submitted[2].body).toEqual({
    message: '修改两个仓库并运行测试',
    runMode: 'MODIFY_WORKSPACE',
    handoffSourceVersion: 1,
  });

  const ownerlessPage = await page.context().newPage();
  let ownerlessSubmissions = 0;
  await installWorkbenchFixture(ownerlessPage, {
    ownerId: '',
    onApiRequest(request) {
      if (/\/runs$/.test(new URL(request.url()).pathname) && request.method() === 'POST') {
        ownerlessSubmissions++;
      }
    },
  });
  await gotoWorkbench(ownerlessPage);
  const ownerlessComposer = ownerlessPage.getByTestId('workbench-run-composer');
  await ownerlessComposer.fill('不应提交');
  await expect(ownerlessPage.getByTestId('workbench-run-submit')).toBeDisabled();
  expect(ownerlessSubmissions).toBe(0);
  await ownerlessPage.close();
});

test('全局写 Run 不阻止其他阶段提交，未开始阶段仍可人工完成且结构化文件引用可打开', async ({ page }) => {
  const submitted: Array<Record<string, unknown>> = [];
  await installWorkbenchFixture(page, {
    activeRun: {
      phase: 'SOLUTION_DESIGN',
      runId: 'write-run-other-phase',
      runMode: 'MODIFY_WORKSPACE',
    },
    onApiRequest(request) {
      const url = new URL(request.url());
      if (url.pathname.endsWith('/phases/IMPLEMENT_TEST/runs')
        && request.method() === 'POST') {
        submitted.push(request.postDataJSON() as Record<string, unknown>);
      }
    },
    async onSse(route, request) {
      const runId = new URL(request.url()).pathname.split('/').at(-2) ?? 'run-unknown';
      await route.fulfill({
        status: 200,
        contentType: 'text/event-stream',
        body: frame(1, 'file_changed', sseEnvelope('IMPLEMENT_TEST', runId, {
          repositoryKey: 'service-b',
          path: 'reports/test.log',
          changeType: 'MODIFIED',
          contentVersion: 'document-v2',
        })) + frame(2, 'terminal', sseEnvelope('IMPLEMENT_TEST', runId, {
          status: 'SUCCEEDED', failureCode: null, publicMessage: 'fixture complete',
        })),
      });
    },
  });
  await gotoWorkbench(page);

  const complete = page.getByRole('button', { name: '人工完成', exact: true });
  await expect(complete).toBeEnabled();
  await complete.click();
  await expect(page.getByRole('button', { name: '重新打开', exact: true })).toBeVisible();

  await page.getByRole('button', { name: /开发部署测试/ }).click();
  await page.getByRole('button', { name: /README\.md/ }).click();
  await page.getByTestId('workbench-run-composer').fill('继续分析并提交');
  await page.getByTestId('workbench-run-submit').click();
  await expect.poll(() => submitted.length).toBe(1);
  expect(submitted[0]).toEqual({
    message: '继续分析并提交',
    runMode: 'MODIFY_WORKSPACE',
    handoffSourceVersion: 1,
  });

  const fileReference = page.getByTestId('workbench-structured-document-reference');
  await expect(fileReference).toContainText('service-b/reports/test.log');
  await fileReference.click();
  await expect(page.locator('.workbench-document-viewer-heading'))
    .toContainText('reports/test.log');
  await expect(page.locator('.workbench-recent-document-group > strong'))
    .toContainText(['service-b', 'service-a']);
});

test('完成态 Run 刷新后可分页恢复，并追溯本轮实际 Rules、Skills、MCP', async ({ page }) => {
  await installWorkbenchFixture(page);
  await gotoWorkbench(page);

  await page.getByTestId('open-run-history').click();

  await expect(page.getByTestId('workbench-run-history-list')).toContainText('run-history-e2e');
  await expect(page.getByTestId('workbench-run-history-timeline')).toContainText('已恢复的历史 Agent 输出');
  await expect(page.getByTestId('workbench-run-history-timeline')).toContainText('历史运行完成');
  const binding = page.getByTestId('workbench-run-history-capability');
  await expect(binding).toContainText('workbench-requirement-analysis');
  await expect(binding).toContainText('platform/workbench-safety');
  await expect(binding).toContainText('requirement-analysis');
  await expect(binding).not.toContainText('server-secret');
  await expect(binding).not.toContainText('/private/never-render');
});

test('刷新按 marker 游标恢复并携带 Last-Event-ID，断流进入重连状态', async ({ page }) => {
  const requests: Array<{ after: string | null; lastEventId: string | undefined }> = [];
  let sseRequest = 0;
  await installWorkbenchFixture(page, {
    activeRun: { phase: 'REQUIREMENT_ANALYSIS', runId: 'run-resume' },
    async onSse(route, request) {
      const url = new URL(request.url());
      requests.push({ after: url.searchParams.get('after'), lastEventId: request.headers()['last-event-id'] });
      sseRequest++;
      const body = sseRequest === 1
        ? frame(1, 'run_status', sseEnvelope('REQUIREMENT_ANALYSIS', 'run-resume', {
            status: 'RUNNING', runMode: 'DISCUSS_READ_ONLY',
          })) + frame(2, 'agent_chunk', sseEnvelope('REQUIREMENT_ANALYSIS', 'run-resume', {
            content: '刷新前输出',
          }))
        : frame(3, 'agent_chunk', sseEnvelope('REQUIREMENT_ANALYSIS', 'run-resume', {
            content: '刷新后续传输出',
          }));
      await route.fulfill({ status: 200, contentType: 'text/event-stream', body });
    },
  });
  await gotoWorkbench(page);
  await expect(page.getByText('刷新前输出')).toBeVisible();
  await expect(page.getByText('正在重连')).toBeVisible();

  await page.reload();
  await expect(page.getByText('刷新后续传输出')).toBeVisible();
  expect(requests.some(item => item.after === '2' && item.lastEventId === '2')).toBe(true);
});

test('Stop 只记录取消意图，直到 SSE terminal 才展示已取消终态', async ({ page }) => {
  let stopRequested = false;
  let sseRequest = 0;
  await installWorkbenchFixture(page, {
    activeRun: { phase: 'IMPLEMENT_TEST', runId: 'run-stop', runMode: 'MODIFY_WORKSPACE' },
    onApiRequest(request) {
      if (new URL(request.url()).pathname.endsWith('/stop') && request.method() === 'POST') {
        stopRequested = true;
      }
    },
    async onSse(route) {
      sseRequest++;
      if (sseRequest > 1) {
        const deadline = Date.now() + 5_000;
        while (!stopRequested && Date.now() < deadline) {
          await new Promise(resolve => setTimeout(resolve, 25));
        }
      }
      const body = sseRequest === 1
        ? frame(1, 'run_status', sseEnvelope('IMPLEMENT_TEST', 'run-stop', {
            status: 'RUNNING', runMode: 'MODIFY_WORKSPACE',
          }))
        : frame(2, 'run_status', sseEnvelope('IMPLEMENT_TEST', 'run-stop', {
            status: 'CANCEL_REQUESTED', runMode: 'MODIFY_WORKSPACE',
          })) + frame(3, 'terminal', sseEnvelope('IMPLEMENT_TEST', 'run-stop', {
            status: 'CANCELLED', failureCode: null, publicMessage: '由 Owner 停止',
          }));
      await route.fulfill({ status: 200, contentType: 'text/event-stream', body });
    },
  });
  await page.addInitScript(() => {
    localStorage.setItem('agent-web:workbench-shell:owner-e2e:wb-e2e', JSON.stringify({
      selectedPhase: 'IMPLEMENT_TEST',
    }));
  });
  await gotoWorkbench(page);
  await expect(page.getByText('运行中')).toBeVisible();
  await page.getByTestId('workbench-run-stop').click();
  expect(stopRequested).toBe(true);
  await expect(page.getByText('停止请求已记录，页面会持续等待并展示明确终态。')).toBeVisible();
  await expect(page.getByText('本轮运行已取消')).toHaveCount(0);
  await expect(page.getByText('本轮运行已取消')).toBeVisible({ timeout: 5_000 });
});

test('Review exact proof 绑定 MODIFY，请求变更立即失效', async ({ page }) => {
  const reviewRequests: Array<{ path: string; method: string; body: unknown; ifMatch?: string }> = [];
  let runBody: Record<string, unknown> | null = null;
  await installWorkbenchFixture(page, {
    onApiRequest(request) {
      const url = new URL(request.url());
      if (url.pathname.includes('/review-')) {
        reviewRequests.push({
          path: url.pathname,
          method: request.method(),
          body: request.postData() ? request.postDataJSON() : null,
          ifMatch: request.headers()['if-match'],
        });
      }
      if (url.pathname.endsWith('/phases/REVIEW_REFACTOR/runs') && request.method() === 'POST') {
        runBody = request.postDataJSON() as Record<string, unknown>;
      }
    },
  });
  await page.addInitScript(() => {
    localStorage.setItem('agent-web:workbench-shell:owner-e2e:wb-e2e', JSON.stringify({
      selectedPhase: 'REVIEW_REFACTOR',
    }));
  });
  await gotoWorkbench(page);
  await expect(page.getByText('未授权修改')).toBeVisible();
  const opinionText = '仅将订单规则下沉到聚合，并执行领域单测';
  const reviewInput = page.getByPlaceholder('写下需要 Agent 解释或执行的 Review 意见、重构目标及回归测试要求');
  await reviewInput.fill(`  ${opinionText}  `);
  await page.getByTestId('review-save-opinion').click();
  await expect(page.getByText(/Review Opinion 已保存为 v1/)).toBeVisible();
  await page.getByTestId('review-confirm-modification').click();
  await expect(page.getByText('已精确确认')).toBeVisible();

  const save = reviewRequests.find(item => item.method === 'PUT')!;
  expect(save.body).toEqual({ content: opinionText });
  expect(save.ifMatch).toBe('0');
  const confirm = reviewRequests.find(item => item.method === 'POST' && item.path.endsWith('/review-confirmation'))!;
  expect(confirm.body).toEqual({
    opinionVersion: 1,
    opinionHash: createHash('sha256').update(opinionText).digest('hex'),
  });

  await page.getByTestId('workbench-run-submit').click();
  await expect.poll(() => runBody).not.toBeNull();
  expect(runBody).toEqual({
    message: opinionText,
    runMode: 'MODIFY_WORKSPACE',
    handoffSourceVersion: 1,
    reviewConfirmationId: 'confirmation-e2e',
  });

  await reviewInput.fill(`${opinionText}。补充范围`);
  await expect(page.getByText('未授权修改')).toBeVisible();
  await expect(page.getByTestId('workbench-run-submit')).toBeDisabled();
});

test('归档后仅可恢复查看历史', async ({ page }) => {
  await installWorkbenchFixture(page, { archived: true });
  await gotoWorkbench(page);
  await expect(page.getByTestId('workbench-run-composer')).toBeDisabled();
  await expect(page.getByText('已归档，仅可恢复查看历史。')).toBeVisible();
  await expect(page.getByTestId('workbench-upload-image-button')).toHaveCount(0);
  await expect(page.getByTestId('workbench-upload-file-button')).toHaveCount(0);
});

test('仓内文档和浏览器上传附件联合提交时只发送逻辑引用', async ({ page }) => {
  const requests: Request[] = [];
  let runBody: Record<string, unknown> | null = null;
  await installWorkbenchFixture(page, {
    onApiRequest(request) {
      const path = new URL(request.url()).pathname;
      if (path.includes('/attachments')) requests.push(request);
      if (path.endsWith('/phases/REQUIREMENT_ANALYSIS/runs') && request.method() === 'POST') {
        runBody = request.postDataJSON() as Record<string, unknown>;
      }
    },
  });
  await gotoWorkbench(page);

  await page.getByRole('button', { name: /README\.md/ }).click();
  await expect(page.getByTestId('workbench-attach-document')).toBeEnabled();
  await page.getByTestId('workbench-attach-document').click();
  await expect(page.getByTestId('workbench-pending-attachments'))
    .toContainText('service-a/README.md');

  await page.getByTestId('workbench-upload-image-input').setInputFiles({
    name: 'architecture.png',
    mimeType: 'image/png',
    buffer: Buffer.from('safe png fixture'),
  });
  const uploaded = page.getByTestId('workbench-upload-item');
  await expect(uploaded).toContainText('architecture.png');
  await expect(uploaded).toContainText('待发送');
  await expect(page.getByTestId('workbench-upload-preview')).toBeVisible();
  await expect(page.getByTestId('workbench-upload-items')).toContainText('浏览器上传');

  await page.getByTestId('workbench-run-composer').fill('结合设计图和 README 分析需求');
  await page.getByTestId('workbench-run-submit').click();
  await expect.poll(() => runBody).not.toBeNull();

  expect(runBody).toEqual({
    message: '结合设计图和 README 分析需求',
    runMode: 'MODIFY_WORKSPACE',
    attachments: [{
      repositoryKey: 'service-a',
      relativePath: 'README.md',
      contentHash: HASH,
    }, {
      type: 'UPLOADED_CONVERSATION',
      attachmentId: 'uploaded-e2e-1',
      contentHash: SECOND_HASH,
    }],
  });
  const upload = requests.find(request => request.method() === 'POST')!;
  expect(new URL(upload.url()).pathname)
    .toBe(`/api/workbenches/${WORKBENCH_ID}/phases/REQUIREMENT_ANALYSIS/attachments`);
  expect(new URL(upload.url()).searchParams.get('conversationGeneration')).toBe('0');
  expect(upload.headers()['content-type']).toMatch(/^multipart\/form-data; boundary=/);
  expect(JSON.stringify(runBody)).not.toMatch(
    /architecture\.png|image\/png|blob:|storageKey|absolutePath|repositoryRoot/,
  );
  expect(requests.some(request => request.method() === 'DELETE')).toBe(false);
  await expect(page.getByTestId('workbench-upload-item')).toHaveCount(0);
});

test('浏览器附件上传失败保留文本并可重试和精确取消', async ({ page }) => {
  const requests: Request[] = [];
  await installWorkbenchFixture(page, {
    uploadFailures: 1,
    onApiRequest(request) {
      if (new URL(request.url()).pathname.includes('/attachments')) requests.push(request);
    },
  });
  await gotoWorkbench(page);
  const composer = page.getByTestId('workbench-run-composer');
  await composer.fill('失败时不能丢失这段文本');

  await page.getByTestId('workbench-upload-file-input').setInputFiles({
    name: 'notes.txt',
    mimeType: 'text/plain',
    buffer: Buffer.from('safe notes'),
  });
  await expect(page.getByTestId('workbench-upload-item')).toContainText('上传失败');
  await expect(page.getByTestId('workbench-upload-notice')).toHaveCount(0);
  await expect(composer).toHaveValue('失败时不能丢失这段文本');

  await page.getByTestId('workbench-upload-retry').click();
  await expect(page.getByTestId('workbench-upload-item')).toContainText('待发送');
  await page.getByTestId('workbench-upload-remove').click();
  await expect(page.getByTestId('workbench-upload-item')).toHaveCount(0);
  await expect(composer).toHaveValue('失败时不能丢失这段文本');

  const uploadRequests = requests.filter(request => request.method() === 'POST');
  const deleteRequest = requests.find(request => request.method() === 'DELETE')!;
  expect(uploadRequests).toHaveLength(2);
  expect(new URL(deleteRequest.url()).pathname).toBe(
    `/api/workbenches/${WORKBENCH_ID}/phases/REQUIREMENT_ANALYSIS/attachments/uploaded-e2e-2`,
  );
  expect(new URL(deleteRequest.url()).searchParams.get('conversationGeneration')).toBe('0');
  expect(requests.every(request => !new URL(request.url()).pathname.startsWith('/api/fs'))).toBe(true);
});

test('Review Candidate 逐条编辑、采用或忽略，只在显式保存和确认后产生证明', async ({ page }) => {
  const reviewRequests: Array<{ path: string; method: string; body: unknown }> = [];
  await installWorkbenchFixture(page, {
    onApiRequest(request) {
      const path = new URL(request.url()).pathname;
      if (path.includes('/review-')) {
        reviewRequests.push({
          path,
          method: request.method(),
          body: request.postData() ? request.postDataJSON() : null,
        });
      }
    },
  });
  await page.addInitScript(() => {
    localStorage.setItem('agent-web:workbench-shell:owner-e2e:wb-e2e', JSON.stringify({
      selectedPhase: 'REVIEW_REFACTOR',
    }));
  });
  await gotoWorkbench(page);

  await page.getByTestId('review-generate-candidate').click();
  const items = page.getByTestId('review-candidate-item');
  await expect(items).toHaveCount(2);
  await items.nth(0).locator('textarea').nth(2).fill('下沉到 Order 聚合根');
  await items.nth(0).getByRole('button', { name: '采用到人工草稿' }).click();
  await items.nth(1).getByRole('button', { name: '忽略' }).click();

  const reviewInput = page.getByPlaceholder('写下需要 Agent 解释或执行的 Review 意见、重构目标及回归测试要求');
  await expect(reviewInput).toHaveValue(/Application 层代替聚合做业务判断/);
  await expect(reviewInput).toHaveValue(/下沉到 Order 聚合根/);
  await expect(reviewInput).toHaveValue(/service-a::src\/main\/java\/OrderService.java/);
  expect(reviewRequests.filter(request => request.method === 'PUT')).toEqual([]);
  expect(reviewRequests.filter(request =>
    request.method === 'POST' && request.path.endsWith('/review-confirmation'))).toEqual([]);
  expect(reviewRequests.find(request => request.path.endsWith('/review-candidates'))?.body).toEqual({});

  await page.getByTestId('review-save-opinion').click();
  await expect(page.getByText(/Review Opinion 已保存为 v1/)).toBeVisible();
  await expect(page.getByTestId('review-candidate-list')).toHaveCount(0);
  await expect(page.getByText('未授权修改')).toBeVisible();
  await page.getByTestId('review-confirm-modification').click();
  await expect(page.getByText('已精确确认')).toBeVisible();
});

test('Markdown 只渲染净化 HTML，scope mismatch 与服务端秘密均使用安全提示', async ({ page }) => {
  const markdown = [
    '# 安全文档',
    '<img src=x onerror="window.__workbenchXss = 1">',
    '<script>window.__workbenchXss = 2</script>',
    '[危险链接](javascript:window.__workbenchXss=3)',
  ].join('\n');
  await installWorkbenchFixture(page, { documentContent: markdown });
  await gotoWorkbench(page);
  await page.getByRole('button', { name: /README\.md/ }).click();
  const preview = page.getByTestId('workbench-markdown-sanitized-preview');
  await expect(preview.getByRole('heading', { name: '安全文档' })).toBeVisible();
  expect(await page.evaluate(() => (window as Window & { __workbenchXss?: number }).__workbenchXss)).toBeUndefined();
  await expect(preview.locator('script')).toHaveCount(0);
  await expect(preview.locator('[onerror]')).toHaveCount(0);
  await expect(preview.locator('a[href^="javascript:"]')).toHaveCount(0);

  const mismatched = await page.context().newPage();
  await mismatched.addInitScript(() => localStorage.clear());
  await installWorkbenchFixture(mismatched, { documentTreeRepositoryKey: 'scope-escape' });
  await gotoWorkbench(mismatched);
  await expect(mismatched.getByTestId('workbench-document-error'))
    .toContainText('文档响应与当前 Workbench 仓库范围不一致');
  await expect(mismatched.locator('body')).not.toContainText('server-secret');
  await expect(mismatched.locator('body')).not.toContainText('/home/private');
  await mismatched.close();

  const secretFailure = await page.context().newPage();
  await secretFailure.addInitScript(() => localStorage.clear());
  await installWorkbenchFixture(secretFailure, { documentTreeFailure: true });
  await gotoWorkbench(secretFailure);
  await expect(secretFailure.getByTestId('workbench-document-error'))
    .toContainText('该路径不在 Workbench 授权范围内');
  await expect(secretFailure.locator('body')).not.toContainText('server-secret');
  await expect(secretFailure.locator('body')).not.toContainText('/home/private');
  await secretFailure.close();
});
