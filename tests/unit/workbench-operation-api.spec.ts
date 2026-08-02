/**
 * TD-08 Workbench 高影响操作 Owner API 契约。
 *
 * @author alex
 * @since 2026-08-01
 */
import { describe, expect, it, vi } from 'vitest';
import {
  WorkbenchOperationApiError,
  createWorkbenchOperationApiClient,
  type WorkbenchOperationFetch,
  type WorkbenchOperationProposalInput,
} from '../../frontend/js/api/workbench-operation.js';

function jsonResponse(status: number, body: unknown): Response {
  return new Response(body == null ? null : JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

function authorizedOperation(): Record<string, unknown> {
  return {
    operationId: 'operation-1',
    sourceRunId: 'run-1',
    phase: 'IMPLEMENT_TEST',
    type: 'GIT_COMMIT',
    target: {
      type: 'GIT_COMMIT',
      repositoryKeys: ['agent-web'],
      details: {
        branch: 'master',
        expectedHead: 'a'.repeat(40),
        expectedStateHash: 'b'.repeat(64),
        includedPaths: ['README.md', 'src/main/App.java'],
        messageHash: 'c'.repeat(64),
        safeMessagePreview: 'feat: workbench',
      },
    },
    requestedPayloadHash: 'd'.repeat(64),
    safeSummary: 'Commit selected files',
    status: 'AUTHORIZED',
    proposedAt: 1_722_528_000_000,
    decisionReason: '已核对目标',
    decidedAt: 1_722_528_010_000,
    authorizationExpiresAt: 1_722_528_910_000,
    preflightHash: null,
    executionReference: null,
    failureCode: null,
    updatedAt: 1_722_528_010_000,
    version: 3,
    executionAvailable: false,
    executionMode: 'MANUAL_OR_DEFERRED',
  };
}

describe('workbench high-impact operation API client', () => {
  it('proposes each strict typed target with an exact idempotent POST and accepts only 201 PROPOSED', async () => {
    const fetcher = vi.fn<WorkbenchOperationFetch>().mockImplementation(async (_input, init) => {
      const request = JSON.parse(String(init?.body)) as WorkbenchOperationProposalInput;
      const { type, ...details } = request.target;
      const repositoryKeys = type === 'PRODUCTION_WRITE'
        ? []
        : type === 'LOCAL_DEPLOY'
          ? request.target.repositoryTargets
          : [request.target.repositoryKey];
      const responseDetails = type === 'GIT_PUSH'
        ? { ...details, repositoryKey: undefined, forceAllowed: false }
        : type === 'LOCAL_DEPLOY'
          ? { ...details, repositoryTargets: undefined }
          : type === 'GIT_COMMIT'
            ? { ...details, repositoryKey: undefined }
            : details;
      const proposed = {
        ...authorizedOperation(),
        sourceRunId: request.sourceRunId,
        phase: request.phase,
        type,
        target: { type, repositoryKeys, details: responseDetails },
        status: 'PROPOSED',
        version: 0,
      };
      return jsonResponse(201, proposed);
    });
    const client = createWorkbenchOperationApiClient(fetcher);
    const targets: WorkbenchOperationProposalInput['target'][] = [{
      type: 'GIT_COMMIT', repositoryKey: 'agent-web', branch: 'master',
      expectedHead: 'a'.repeat(40), expectedStateHash: 'b'.repeat(64),
      includedPaths: ['README.md'], messageHash: 'c'.repeat(64), safeMessagePreview: 'feat: proposal',
    }, {
      type: 'GIT_PUSH', repositoryKey: 'agent-web', remoteName: 'origin', localBranch: 'master',
      remoteRef: 'refs/heads/master', expectedLocalHead: 'a'.repeat(40),
    }, {
      type: 'LOCAL_DEPLOY', templateId: 'service', templateVersion: '1', templateHash: 'b'.repeat(64),
      repositoryTargets: ['agent-web'], environment: 'LOCAL',
      expectedWorkspaceStateHash: 'c'.repeat(64), rollbackSummary: '恢复旧进程',
    }, {
      type: 'PRODUCTION_WRITE', environment: 'production', resourceReference: 'database/orders',
      expectedProductionStateHash: 'd'.repeat(64),
    }];

    for (const target of targets) {
      await expect(client.propose('wb-1', 'stable-key', {
        sourceRunId: 'run-1', phase: 'IMPLEMENT_TEST',
        safeSummary: '已人工核对目标与风险', target,
      })).resolves.toMatchObject({ status: 'PROPOSED', version: 0 });
    }

    for (const call of fetcher.mock.calls) {
      expect(call[0]).toBe('/api/workbenches/wb-1/operations');
      expect(call[1]).toEqual(expect.objectContaining({
        method: 'POST', credentials: 'same-origin',
        headers: {
          Accept: 'application/json', 'Content-Type': 'application/json',
          'Idempotency-Key': 'stable-key',
        },
      }));
      const body = JSON.parse(String(call[1]?.body)) as Record<string, unknown>;
      expect(Object.keys(body).sort()).toEqual(['phase', 'safeSummary', 'sourceRunId', 'target']);
      expect(JSON.stringify(body)).not.toMatch(/command|shell|args|CUSTOM/);
    }
  });

  it('rejects unknown, shell-like, malformed path/ref and non-LOCAL proposal fields before fetch', async () => {
    const fetcher = vi.fn<WorkbenchOperationFetch>();
    const client = createWorkbenchOperationApiClient(fetcher);
    const base = { sourceRunId: 'run-1', phase: 'IMPLEMENT_TEST', safeSummary: '人工预览' };
    const invalid: unknown[] = [{
      ...base,
      target: { type: 'GIT_COMMIT', repositoryKey: 'agent-web', branch: 'master',
        expectedHead: 'a'.repeat(40), expectedStateHash: 'b'.repeat(64),
        includedPaths: ['/home/user/secret'], messageHash: 'c'.repeat(64), safeMessagePreview: 'feat' },
    }, {
      ...base,
      target: { type: 'GIT_PUSH', repositoryKey: 'agent-web', remoteName: 'origin',
        localBranch: 'master', remoteRef: ':delete', expectedLocalHead: 'a'.repeat(40) },
    }, {
      ...base,
      target: { type: 'LOCAL_DEPLOY', templateId: 'x', templateVersion: '1',
        templateHash: 'b'.repeat(64), repositoryTargets: ['agent-web'], environment: 'PRODUCTION',
        expectedWorkspaceStateHash: 'c'.repeat(64), rollbackSummary: 'rollback' },
    }, { ...base, target: { type: 'CUSTOM', command: 'git push' } }, {
      ...base,
      command: 'git commit',
      target: { type: 'PRODUCTION_WRITE', environment: 'production',
        resourceReference: 'database/orders', expectedProductionStateHash: 'd'.repeat(64) },
    }, {
      ...base,
      target: { type: 'GIT_PUSH', repositoryKey: 'outside/repository', remoteName: 'origin',
        localBranch: 'master', remoteRef: 'refs/heads/master', expectedLocalHead: 'A'.repeat(40) },
    }, {
      ...base,
      target: { type: 'GIT_COMMIT', repositoryKey: 'agent-web', branch: 'master',
        expectedHead: 'a'.repeat(40), expectedStateHash: 'b'.repeat(64), includedPaths: ['README.md'],
        messageHash: 'c'.repeat(64), safeMessagePreview: 'feat', shell: 'git commit -am all' },
    }];

    for (const input of invalid) {
      await expect(client.propose('wb-1', 'key-1', input as WorkbenchOperationProposalInput))
        .rejects.toMatchObject({ code: 'WORKBENCH_OPERATION_REQUEST_INVALID' });
    }
    expect(fetcher).not.toHaveBeenCalled();
  });

  it('rejects a successful proposal projection unless the response status is exactly 201', async () => {
    const proposed = { ...authorizedOperation(), status: 'PROPOSED', version: 0 };
    const client = createWorkbenchOperationApiClient(
      vi.fn<WorkbenchOperationFetch>().mockResolvedValue(jsonResponse(200, proposed)),
    );

    await expect(client.propose('wb-1', 'key-1', {
      sourceRunId: 'run-1',
      phase: 'IMPLEMENT_TEST',
      safeSummary: '人工核对 Push 目标',
      target: {
        type: 'GIT_PUSH', repositoryKey: 'agent-web', remoteName: 'origin', localBranch: 'master',
        remoteRef: 'refs/heads/master', expectedLocalHead: 'a'.repeat(40),
      },
    })).rejects.toMatchObject({ code: 'WORKBENCH_OPERATION_RESPONSE_INVALID' });
  });

  it('preserves safe source-Run and idempotency conflict codes from proposal failures', async () => {
    const fetcher = vi.fn<WorkbenchOperationFetch>()
      .mockResolvedValueOnce(jsonResponse(404, { code: 'WORKBENCH_OPERATION_SOURCE_RUN_NOT_FOUND' }))
      .mockResolvedValueOnce(jsonResponse(409, { code: 'IDEMPOTENCY_CONFLICT' }));
    const client = createWorkbenchOperationApiClient(fetcher);
    const input: WorkbenchOperationProposalInput = {
      sourceRunId: 'run-1',
      phase: 'IMPLEMENT_TEST',
      safeSummary: '人工核对生产目标',
      target: {
        type: 'PRODUCTION_WRITE', environment: 'production',
        resourceReference: 'database/orders', expectedProductionStateHash: 'd'.repeat(64),
      },
    };

    await expect(client.propose('wb-1', 'key-1', input)).rejects.toMatchObject({
      status: 404,
      code: 'WORKBENCH_OPERATION_SOURCE_RUN_NOT_FOUND',
    });
    await expect(client.propose('wb-1', 'key-1', input)).rejects.toMatchObject({
      status: 409,
      code: 'IDEMPOTENCY_CONFLICT',
    });
  });

  it('lists and loads owner-scoped typed operation projections', async () => {
    const fetcher = vi.fn<WorkbenchOperationFetch>()
      .mockResolvedValueOnce(jsonResponse(200, [authorizedOperation()]))
      .mockResolvedValueOnce(jsonResponse(200, authorizedOperation()));
    const client = createWorkbenchOperationApiClient(fetcher);

    await expect(client.list('wb/一 二')).resolves.toEqual([
      expect.objectContaining({
        operationId: 'operation-1',
        status: 'AUTHORIZED',
        executionAvailable: false,
      }),
    ]);
    await expect(client.get('wb/一 二', 'operation-1')).resolves.toMatchObject({
      target: {
        type: 'GIT_COMMIT',
        repositoryKeys: ['agent-web'],
        details: expect.objectContaining({ branch: 'master' }),
      },
    });

    expect(fetcher.mock.calls[0]).toEqual([
      '/api/workbenches/wb%2F%E4%B8%80%20%E4%BA%8C/operations',
      expect.objectContaining({
        method: 'GET',
        credentials: 'same-origin',
        headers: { Accept: 'application/json' },
      }),
    ]);
    expect(fetcher.mock.calls[1]?.[0]).toBe(
      '/api/workbenches/wb%2F%E4%B8%80%20%E4%BA%8C/operations/operation-1',
    );
  });

  it('sends an exact If-Match decision without actor or execution fields', async () => {
    const fetcher = vi.fn<WorkbenchOperationFetch>()
      .mockResolvedValue(jsonResponse(200, authorizedOperation()));
    const client = createWorkbenchOperationApiClient(fetcher);

    await expect(client.decide('wb-1', 'operation-1', 0, {
      decision: 'APPROVE',
      reason: '已核对仓库、分支和状态',
    })).resolves.toMatchObject({
      status: 'AUTHORIZED',
      executionAvailable: false,
      executionMode: 'MANUAL_OR_DEFERRED',
    });

    const init = fetcher.mock.calls[0]?.[1] as RequestInit;
    expect(init).toEqual(expect.objectContaining({
      method: 'POST',
      credentials: 'same-origin',
      headers: {
        Accept: 'application/json',
        'Content-Type': 'application/json',
        'If-Match': '0',
      },
      body: JSON.stringify({
        decision: 'APPROVE',
        reason: '已核对仓库、分支和状态',
      }),
    }));
    expect(init.body).not.toContain('actor');
    expect(init.body).not.toContain('executionAvailable');
  });

  it('rejects malformed target paths, hashes, versions, and execution claims', async () => {
    const malformedPath = authorizedOperation();
    malformedPath.target = {
      ...(malformedPath.target as Record<string, unknown>),
      details: {
        ...((malformedPath.target as Record<string, unknown>).details as Record<string, unknown>),
        includedPaths: ['/home/user/.codex/auth.json'],
      },
    };
    const executable = { ...authorizedOperation(), executionAvailable: true };
    const fetcher = vi.fn<WorkbenchOperationFetch>()
      .mockResolvedValueOnce(jsonResponse(200, malformedPath))
      .mockResolvedValueOnce(jsonResponse(200, executable));
    const client = createWorkbenchOperationApiClient(fetcher);

    await expect(client.get('wb-1', 'operation-1')).rejects.toMatchObject({
      code: 'WORKBENCH_OPERATION_RESPONSE_INVALID',
    });
    await expect(client.get('wb-1', 'operation-1')).rejects.toMatchObject({
      code: 'WORKBENCH_OPERATION_RESPONSE_INVALID',
    });
    await expect(client.decide('wb-1', 'operation-1', -1, {
      decision: 'APPROVE',
      reason: 'x',
    })).rejects.toThrow();
  });

  it('preserves only a safe current projection on version conflict', async () => {
    const fetcher = vi.fn<WorkbenchOperationFetch>()
      .mockResolvedValue(jsonResponse(409, {
        code: 'WORKBENCH_OPERATION_VERSION_CONFLICT',
        message: 'secret backend detail',
        path: '/home/user/project',
        current: authorizedOperation(),
      }));
    const client = createWorkbenchOperationApiClient(fetcher);

    let thrown: unknown;
    try {
      await client.decide('wb-1', 'operation-1', 2, {
        decision: 'REJECT',
        reason: '目标变化',
      });
    } catch (error) {
      thrown = error;
    }

    expect(thrown).toBeInstanceOf(WorkbenchOperationApiError);
    expect(thrown).toMatchObject({
      status: 409,
      code: 'WORKBENCH_OPERATION_VERSION_CONFLICT',
      current: expect.objectContaining({ version: 3 }),
    });
    expect(JSON.stringify(thrown)).not.toContain('secret backend detail');
    expect(JSON.stringify(thrown)).not.toContain('/home/user/project');
  });

  it('fails closed on unknown error codes and malformed lists', async () => {
    const failed = vi.fn<WorkbenchOperationFetch>()
      .mockResolvedValueOnce(jsonResponse(422, {
        code: 'LEAK_/home/user/token',
        message: 'raw secret',
      }))
      .mockResolvedValueOnce(jsonResponse(200, [authorizedOperation(), { bad: true }]));
    const client = createWorkbenchOperationApiClient(failed);

    await expect(client.list('wb-1')).rejects.toMatchObject({
      status: 422,
      code: 'WORKBENCH_OPERATION_REQUEST_INVALID',
      message: 'Workbench high-impact operation request failed',
    });
    await expect(client.list('wb-1')).rejects.toMatchObject({
      code: 'WORKBENCH_OPERATION_RESPONSE_INVALID',
    });
  });
});
