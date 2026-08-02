/**
 * High-impact Operation list and decision orchestration.
 *
 * @author alex
 * @since 2026-08-01
 */
// @ts-expect-error Frontend Vue ESM entry has no declaration for this relative test import.
import * as frontendVueRuntime from '../../frontend/node_modules/vue/index.mjs';
import { createHash } from 'node:crypto';
import { describe, expect, it, vi } from 'vitest';
import {
  WorkbenchOperationApiError,
  type WorkbenchHighImpactOperation,
  type WorkbenchOperationApiClient,
  type WorkbenchOperationProposalInput,
} from '../../frontend/js/api/workbench-operation.js';
import type { WorkbenchRunApiClient } from '../../frontend/js/api/workbench-run.js';
import { useWorkbenchOperations } from '../../frontend/js/composables/useWorkbenchOperations.js';

const { ref } = frontendVueRuntime as typeof import('vue');

function operation(overrides: Partial<WorkbenchHighImpactOperation> = {}): WorkbenchHighImpactOperation {
  return {
    operationId: 'operation-1',
    sourceRunId: 'run-1',
    phase: 'IMPLEMENT_TEST',
    type: 'GIT_PUSH',
    target: {
      type: 'GIT_PUSH',
      repositoryKeys: ['agent-web'],
      details: {
        remoteName: 'origin',
        localBranch: 'master',
        remoteRef: 'refs/heads/master',
        expectedLocalHead: 'a'.repeat(40),
        forceAllowed: false,
      },
    },
    requestedPayloadHash: 'b'.repeat(64),
    safeSummary: 'Push master to origin',
    status: 'PROPOSED',
    proposedAt: 100,
    decisionReason: null,
    decidedAt: null,
    authorizationExpiresAt: null,
    preflightHash: null,
    executionReference: null,
    failureCode: null,
    updatedAt: 100,
    version: 0,
    executionAvailable: false,
    executionMode: 'MANUAL_OR_DEFERRED',
    ...overrides,
  };
}

function api(overrides: Partial<WorkbenchOperationApiClient> = {}): WorkbenchOperationApiClient {
  return {
    list: vi.fn().mockResolvedValue([operation()]),
    get: vi.fn().mockResolvedValue(operation()),
    propose: vi.fn().mockResolvedValue(operation({
      operationId: 'operation-proposed',
      type: 'GIT_COMMIT',
      target: {
        type: 'GIT_COMMIT',
        repositoryKeys: ['agent-web'],
        details: {},
      },
    })),
    decide: vi.fn().mockResolvedValue(operation({ status: 'AUTHORIZED', version: 1 })),
    ...overrides,
  };
}

function runApi(overrides: Partial<WorkbenchRunApiClient> = {}): WorkbenchRunApiClient {
  return {
    getConversationMessages: vi.fn(),
    ensureConversation: vi.fn(),
    restartConversation: vi.fn(),
    submitRun: vi.fn(),
    getRun: vi.fn(),
    stopRun: vi.fn(),
    listRuns: vi.fn().mockResolvedValue({
      items: [{
        runId: 'run-1', workbenchId: 'wb-1', phase: 'IMPLEMENT_TEST', sessionId: 'session-1',
        status: 'SUCCEEDED', runMode: 'MODIFY_WORKSPACE', lastEventSeq: 3, createdAt: 100,
        startedAt: 101, finishedAt: 102, failureCode: null,
      }],
      nextCursor: null,
    }),
    getRunEvents: vi.fn(),
    getRunCapability: vi.fn(),
    eventsUrl: vi.fn(),
    ...overrides,
  };
}

function commitDraft() {
  return {
    sourceRunId: 'run-1',
    safeSummary: '人工核对 Commit 目标',
    target: {
      type: 'GIT_COMMIT' as const,
      repositoryKey: 'agent-web',
      branch: 'master',
      expectedHead: 'a'.repeat(40),
      expectedStateHash: 'b'.repeat(64),
      includedPaths: ['README.md'],
      safeMessagePreview: '  feat: caf\u0065\u0301\r\n\r\n  add proposal  ',
    },
  };
}

describe('useWorkbenchOperations', () => {
  it('filters cards by phase and records approval as authorization without claiming execution', async () => {
    const client = api();
    const operations = useWorkbenchOperations({
      workbenchId: ref('wb-1'),
      phase: ref('IMPLEMENT_TEST'),
      apiClient: client,
    });
    await vi.waitFor(() => expect(operations.operationLoading.value).toBe(false));

    await operations.decideOperation(operation(), 'APPROVE', '已核对仓库、分支和 HEAD');

    expect(client.decide).toHaveBeenCalledWith('wb-1', 'operation-1', 0, {
      decision: 'APPROVE',
      reason: '已核对仓库、分支和 HEAD',
    });
    expect(operations.phaseOperations.value[0]).toMatchObject({
      status: 'AUTHORIZED',
      executionAvailable: false,
      executionMode: 'MANUAL_OR_DEFERRED',
    });
    expect(operations.operationNotice.value).toContain('不会自动执行');
  });

  it('adopts a safe current projection on version conflict and never retries silently', async () => {
    const current = operation({ version: 2, updatedAt: 200 });
    const decide = vi.fn().mockRejectedValue(new WorkbenchOperationApiError(
      409,
      'WORKBENCH_OPERATION_VERSION_CONFLICT',
      current,
    ));
    const client = api({ decide });
    const operations = useWorkbenchOperations({
      workbenchId: ref('wb-1'),
      phase: ref('IMPLEMENT_TEST'),
      apiClient: client,
    });
    await vi.waitFor(() => expect(operations.operationLoading.value).toBe(false));

    await operations.decideOperation(operation(), 'REJECT', '目标已变化');

    expect(decide).toHaveBeenCalledTimes(1);
    expect(operations.phaseOperations.value[0]?.version).toBe(2);
    expect(operations.operationError.value).toContain('已变化');
  });

  it('allows archived operation history to load but disables every decision', async () => {
    const client = api();
    const operations = useWorkbenchOperations({
      workbenchId: ref('wb-1'),
      phase: ref('IMPLEMENT_TEST'),
      archived: ref(true),
      apiClient: client,
    });
    await vi.waitFor(() => expect(operations.operationLoading.value).toBe(false));

    await operations.decideOperation(operation(), 'APPROVE', '不应写入');

    expect(client.list).toHaveBeenCalledWith('wb-1');
    expect(client.decide).not.toHaveBeenCalled();
    expect(operations.operationReadOnly.value).toBe(true);
  });

  it('loads only real Runs from the current Workbench and Phase as proposal sources', async () => {
    const runs = runApi({
      listRuns: vi.fn().mockResolvedValue({
        items: [
          { runId: 'run-1', workbenchId: 'wb-1', phase: 'IMPLEMENT_TEST', sessionId: 's1', status: 'SUCCEEDED', runMode: 'MODIFY_WORKSPACE', lastEventSeq: 1, createdAt: 4, startedAt: 5, finishedAt: 6, failureCode: null },
          { runId: 'wrong-workbench', workbenchId: 'wb-2', phase: 'IMPLEMENT_TEST', sessionId: 's2', status: 'SUCCEEDED', runMode: 'MODIFY_WORKSPACE', lastEventSeq: 1, createdAt: 3, startedAt: 4, finishedAt: 5, failureCode: null },
          { runId: 'wrong-phase', workbenchId: 'wb-1', phase: 'SOLUTION_DESIGN', sessionId: 's3', status: 'SUCCEEDED', runMode: 'DISCUSS_READ_ONLY', lastEventSeq: 1, createdAt: 2, startedAt: 3, finishedAt: 4, failureCode: null },
        ],
        nextCursor: null,
      }),
    });
    const operations = useWorkbenchOperations({
      workbenchId: ref('wb-1'),
      phase: ref('IMPLEMENT_TEST'),
      repositories: ref([{ repositoryKey: 'agent-web', relativePath: '.', primary: true }]),
      apiClient: api(),
      runApiClient: runs,
    });

    await operations.prepareOperationProposal();

    expect(runs.listRuns).toHaveBeenCalledWith('wb-1', { phase: 'IMPLEMENT_TEST', limit: 100 });
    expect(operations.operationSourceRuns.value.map(run => run.runId)).toEqual(['run-1']);
    expect(operations.operationProposalDisabledReason.value).toBeNull();
  });

  it('hashes the normalized Commit preview and reuses the idempotency key until the specification changes', async () => {
    const propose = vi.fn()
      .mockRejectedValueOnce(new WorkbenchOperationApiError(0, 'WORKBENCH_OPERATION_NETWORK_ERROR'))
      .mockResolvedValueOnce(operation({ operationId: 'operation-proposed', type: 'GIT_COMMIT' }))
      .mockResolvedValueOnce(operation({ operationId: 'operation-changed', type: 'GIT_COMMIT' }));
    const keys = ['key-1', 'key-2'];
    const client = api({ propose });
    const operations = useWorkbenchOperations({
      workbenchId: ref('wb-1'),
      phase: ref('IMPLEMENT_TEST'),
      repositories: ref([{ repositoryKey: 'agent-web', relativePath: '.', primary: true }]),
      apiClient: client,
      runApiClient: runApi(),
      idempotencyKeyFactory: () => keys.shift() ?? 'unexpected-key',
    });
    await operations.prepareOperationProposal();
    const draft = commitDraft();

    await operations.proposeOperation(draft);
    await operations.proposeOperation(draft);
    await operations.proposeOperation({ ...draft, safeSummary: '改变后的风险摘要' });

    const normalizedPreview = 'feat: caf\u00e9\n\n  add proposal';
    const expectedHash = createHash('sha256').update(normalizedPreview, 'utf8').digest('hex');
    expect(propose.mock.calls.map(call => call[1])).toEqual(['key-1', 'key-1', 'key-2']);
    const submitted = propose.mock.calls[0]?.[2] as WorkbenchOperationProposalInput;
    expect(submitted.target).toMatchObject({
      type: 'GIT_COMMIT',
      safeMessagePreview: normalizedPreview,
      messageHash: expectedHash,
    });
    expect(operations.phaseOperations.value[0]).toMatchObject({
      operationId: 'operation-changed',
      status: 'PROPOSED',
      executionAvailable: false,
    });
    expect(operations.operationNotice.value).toContain('尚未授权');
    expect(operations.operationNotice.value).toContain('不会自动执行');
    expect(operations.operationProposalCreatedToken.value).toBe(2);
  });

  it('disables proposal preparation when archived or when no current-Phase Run fact exists', async () => {
    const archivedRuns = runApi();
    const archived = useWorkbenchOperations({
      workbenchId: ref('wb-1'), phase: ref('IMPLEMENT_TEST'), archived: ref(true),
      repositories: ref([{ repositoryKey: 'agent-web', relativePath: '.', primary: true }]),
      apiClient: api(), runApiClient: archivedRuns,
    });
    await archived.prepareOperationProposal();
    await archived.proposeOperation(commitDraft());
    expect(archivedRuns.listRuns).not.toHaveBeenCalled();
    expect(archived.operationProposalDisabledReason.value).toContain('已归档');
    expect(archived.operationProposing.value).toBe(false);

    const emptyRuns = runApi({ listRuns: vi.fn().mockResolvedValue({ items: [], nextCursor: null }) });
    const empty = useWorkbenchOperations({
      workbenchId: ref('wb-1'), phase: ref('IMPLEMENT_TEST'),
      repositories: ref([{ repositoryKey: 'agent-web', relativePath: '.', primary: true }]),
      apiClient: api(), runApiClient: emptyRuns,
    });
    await empty.prepareOperationProposal();
    expect(empty.operationSourceRuns.value).toEqual([]);
    expect(empty.operationProposalDisabledReason.value).toContain('真实 Run');
  });

  it('isolates late source-Run responses across Workbench and Phase changes', async () => {
    let resolveFirst!: (value: Awaited<ReturnType<WorkbenchRunApiClient['listRuns']>>) => void;
    const first = new Promise<Awaited<ReturnType<WorkbenchRunApiClient['listRuns']>>>(resolve => {
      resolveFirst = resolve;
    });
    const listRuns = vi.fn()
      .mockReturnValueOnce(first)
      .mockResolvedValueOnce({ items: [], nextCursor: null });
    const workbenchId = ref<string | null>('wb-1');
    const phase = ref<'IMPLEMENT_TEST' | 'SOLUTION_DESIGN'>('IMPLEMENT_TEST');
    const operations = useWorkbenchOperations({
      workbenchId, phase,
      repositories: ref([{ repositoryKey: 'agent-web', relativePath: '.', primary: true }]),
      apiClient: api(), runApiClient: runApi({ listRuns }),
    });

    const stale = operations.prepareOperationProposal();
    workbenchId.value = 'wb-2';
    phase.value = 'SOLUTION_DESIGN';
    await operations.prepareOperationProposal();
    resolveFirst({
      items: [{ runId: 'stale-run', workbenchId: 'wb-1', phase: 'IMPLEMENT_TEST', sessionId: 's1', status: 'SUCCEEDED', runMode: 'MODIFY_WORKSPACE', lastEventSeq: 1, createdAt: 1, startedAt: 2, finishedAt: 3, failureCode: null }],
      nextCursor: null,
    });
    await stale;

    expect(operations.operationSourceRuns.value).toEqual([]);
    expect(operations.operationProposalDisabledReason.value).toContain('真实 Run');
  });

  it('drops a late proposal response after the Phase scope changes', async () => {
    let resolveProposal!: (value: WorkbenchHighImpactOperation) => void;
    const proposalResponse = new Promise<WorkbenchHighImpactOperation>(resolve => {
      resolveProposal = resolve;
    });
    const propose = vi.fn().mockReturnValue(proposalResponse);
    const phase = ref<'IMPLEMENT_TEST' | 'SOLUTION_DESIGN'>('IMPLEMENT_TEST');
    const operations = useWorkbenchOperations({
      workbenchId: ref('wb-1'), phase,
      repositories: ref([{ repositoryKey: 'agent-web', relativePath: '.', primary: true }]),
      apiClient: api({ propose }), runApiClient: runApi(),
      idempotencyKeyFactory: () => 'key-1',
    });
    await operations.prepareOperationProposal();

    const pending = operations.proposeOperation(commitDraft());
    await vi.waitFor(() => expect(propose).toHaveBeenCalledTimes(1));
    phase.value = 'SOLUTION_DESIGN';
    resolveProposal(operation({ operationId: 'late-proposal', type: 'GIT_COMMIT' }));
    await pending;

    expect(operations.operations.value.some(item => item.operationId === 'late-proposal')).toBe(false);
    expect(operations.operationProposalCreatedToken.value).toBe(0);
    expect(operations.operationNotice.value).toBeNull();
    expect(operations.operationProposing.value).toBe(false);
  });

  it('preserves the draft contract but rotates a server-conflicted idempotency key', async () => {
    const propose = vi.fn()
      .mockRejectedValueOnce(new WorkbenchOperationApiError(409, 'IDEMPOTENCY_CONFLICT'))
      .mockResolvedValueOnce(operation({ operationId: 'operation-after-conflict', type: 'GIT_COMMIT' }));
    const keys = ['conflicted-key', 'replacement-key'];
    const operations = useWorkbenchOperations({
      workbenchId: ref('wb-1'), phase: ref('IMPLEMENT_TEST'),
      repositories: ref([{ repositoryKey: 'agent-web', relativePath: '.', primary: true }]),
      apiClient: api({ propose }), runApiClient: runApi(),
      idempotencyKeyFactory: () => keys.shift() ?? 'unexpected-key',
    });
    await operations.prepareOperationProposal();

    await operations.proposeOperation(commitDraft());
    expect(operations.operationError.value).toContain('草稿已保留');
    expect(operations.operationError.value).toContain('重新提交');
    await operations.proposeOperation(commitDraft());

    expect(propose.mock.calls.map(call => call[1])).toEqual(['conflicted-key', 'replacement-key']);
    expect(operations.operations.value[0]?.operationId).toBe('operation-after-conflict');
  });

  it('maps Push, Local Deploy and Production drafts to exact typed scoped payloads', async () => {
    const propose = vi.fn().mockImplementation((
      _workbenchId: string,
      _key: string,
      input: WorkbenchOperationProposalInput,
    ) => Promise.resolve(operation({
      operationId: `operation-${input.target.type}`,
      sourceRunId: input.sourceRunId,
      phase: input.phase,
      type: input.target.type,
    })));
    const operations = useWorkbenchOperations({
      workbenchId: ref('wb-1'), phase: ref('IMPLEMENT_TEST'),
      repositories: ref([
        { repositoryKey: 'agent-web', relativePath: '.', primary: true },
        { repositoryKey: 'service-b', relativePath: 'service-b', primary: false },
      ]),
      apiClient: api({ propose }), runApiClient: runApi(),
      idempotencyKeyFactory: vi.fn()
        .mockReturnValueOnce('push-key')
        .mockReturnValueOnce('deploy-key')
        .mockReturnValueOnce('production-key'),
    });
    await operations.prepareOperationProposal();

    await operations.proposeOperation({
      sourceRunId: 'run-1', safeSummary: '核对非 force Push',
      target: {
        type: 'GIT_PUSH', repositoryKey: 'agent-web', remoteName: 'origin',
        localBranch: 'feature/workbench', remoteRef: 'refs/heads/feature/workbench',
        expectedLocalHead: 'a'.repeat(40),
      },
    });
    await operations.proposeOperation({
      sourceRunId: 'run-1', safeSummary: '核对本地部署模板与回滚',
      target: {
        type: 'LOCAL_DEPLOY', templateId: 'service', templateVersion: '1',
        templateHash: 'b'.repeat(64), repositoryTargets: ['agent-web', 'service-b'],
        environment: 'LOCAL', expectedWorkspaceStateHash: 'c'.repeat(64),
        rollbackSummary: '恢复旧版本进程',
      },
    });
    await operations.proposeOperation({
      sourceRunId: 'run-1', safeSummary: '核对生产资源当前状态',
      target: {
        type: 'PRODUCTION_WRITE', environment: 'production',
        resourceReference: 'database/orders', expectedProductionStateHash: 'd'.repeat(64),
      },
    });
    await operations.proposeOperation({
      sourceRunId: 'run-1', safeSummary: '非法本地生产写',
      target: {
        type: 'PRODUCTION_WRITE', environment: 'LOCAL',
        resourceReference: 'database/orders', expectedProductionStateHash: 'd'.repeat(64),
      },
    });

    expect(propose).toHaveBeenCalledTimes(3);
    expect(propose.mock.calls.map(call => call[2].target)).toEqual([{
      type: 'GIT_PUSH', repositoryKey: 'agent-web', remoteName: 'origin',
      localBranch: 'feature/workbench', remoteRef: 'refs/heads/feature/workbench',
      expectedLocalHead: 'a'.repeat(40),
    }, {
      type: 'LOCAL_DEPLOY', templateId: 'service', templateVersion: '1',
      templateHash: 'b'.repeat(64), repositoryTargets: ['agent-web', 'service-b'],
      environment: 'LOCAL', expectedWorkspaceStateHash: 'c'.repeat(64),
      rollbackSummary: '恢复旧版本进程',
    }, {
      type: 'PRODUCTION_WRITE', environment: 'production',
      resourceReference: 'database/orders', expectedProductionStateHash: 'd'.repeat(64),
    }]);
    expect(operations.operationError.value).toContain('不符合类型约束');
  });
});
